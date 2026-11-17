package app.trading.wallet.deposit;

import app.trading.common.id.Snowflake;
import app.trading.notification.api.NotifyApi;
import app.trading.payment.api.PaymentListener;
import app.trading.risk.api.RiskApi;
import app.trading.trading.api.Bucket;
import app.trading.trading.api.FundsApi;
import app.trading.trading.api.LedgerApi;
import app.trading.trading.api.LedgerCommand;
import app.trading.trading.api.LedgerEntryType;
import app.trading.trading.api.UserEventsApi;
import app.trading.wallet.Chain;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 充值入账（第 1 批）：钱包提供方（TopUP）确认到账后回调，支付接口层验签、按事件 ID 去重后交到这里（{@link #credit}）。
 * 自带钱包的扫块、确认数、区块回滚判断都去掉了（确认数由 TopUP 决定，Trading 只认"已确认"，需求 02 第二节）。
 * <ul>
 *   <li>入账：充值记录（按 提供方 + 提供方到账编号 唯一）、账本 DEPOSIT、到账通知（站内信、推送、邮件）在同一个事务里；同一笔重复通知只入账一次</li>
 *   <li>冲正：余额够就扣，不够不扣成负数，差额挂账并通知风控（F-11 / AC-W3）。TopUP 还没有冲正通知（TG-11），先由测试替身提供方触发</li>
 *   <li>低于 1 USDT：TopUP 不入账、不通知（TD-2 B），Trading 收不到，充值记录里不会出现</li>
 * </ul>
 * 充值记录每次变化（入账、冲正）都在提交以后推给本人 {"type":"deposit","status":"credited","network":"ERC20","amount":"100","confirmations":0}；
 * 推送失败只打日志。
 */
@Service
public class DepositService {

  private static final Logger log = LoggerFactory.getLogger(DepositService.class);
  static final String BIZ_DEPOSIT = "DEPOSIT";

  private final DepositMapper mapper;
  private final LedgerApi ledger;
  private final TransactionTemplate tx;
  private final FundsApi funds;
  private final RiskApi risk;
  private final NotifyApi notify;
  private final UserEventsApi events;
  private final Snowflake ids;

  DepositService(DepositMapper mapper, LedgerApi ledger, TransactionTemplate tx, FundsApi funds, RiskApi risk, NotifyApi notify, UserEventsApi events, Snowflake ids) {
    this.mapper = mapper;
    this.ledger = ledger;
    this.tx = tx;
    this.funds = funds;
    this.risk = risk;
    this.notify = notify;
    this.events = events;
    this.ids = ids;
  }

  /** 账本流水的业务号：提供方:到账编号（和对账一致） */
  static String bizId(String provider, String ref) {
    return provider + ":" + ref;
  }

  /**
   * 提供方确认的一笔到账：记充值、入账、通知，同一个事务（在回调的事务里执行）；同一笔（提供方 + 到账编号）只入账一次。返回这次是否入了账。
   */
  public boolean credit(PaymentListener.Deposit d) {
    Chain chain = Chain.valueOf(d.network().name());
    BigDecimal amount = d.amount().setScale(8, RoundingMode.DOWN);
    Boolean done = tx.execute(st -> {
      if (mapper.insertCredited(ids.next(), chain.name(), d.provider(), d.ref(), d.txHash() == null ? "" : d.txHash(), d.address() == null ? "" : d.address(),
          d.userId(), amount, d.providerFee() == null ? BigDecimal.ZERO : d.providerFee()) != 1) return false;
      ledger.apply(new LedgerCommand(d.userId(), Bucket.BALANCE, amount, LedgerEntryType.DEPOSIT, BIZ_DEPOSIT, bizId(d.provider(), d.ref()), "充值 " + chain));
      // 到账通知（AC-W2），和入账同一事务
      notify.toUser(d.userId(), "DEPOSIT_CREDITED", Map.of("amount", amount.stripTrailingZeros().toPlainString(), "chain", chain.name()));
      return true;
    });
    if (!Boolean.TRUE.equals(done)) {
      log.info("重复的到账通知，不再入账 provider={} ref={}", d.provider(), d.ref());
      return false;
    }
    afterCommit(() -> {
      push(d.userId(), chain, "CREDITED", amount);
      send(d.userId(), Map.of("type", "account"));
    });
    log.info("充值入账 provider={} ref={} chain={} tx={} user={} amount={}", d.provider(), d.ref(), chain, d.txHash(), d.userId(), amount);
    return true;
  }

  /** 提供方通知某笔到账被链上回滚：冲正（AC-W3）。不认识的到账编号只打日志 */
  public void reverseByProviderRef(String provider, String ref) {
    DepositMapper.DepositRow d = mapper.byProviderRef(provider, ref);
    if (d == null) {
      log.warn("冲正通知找不到对应的充值 provider={} ref={}", provider, ref);
      return;
    }
    reverse(d);
  }

  /**
   * 冲正（AC-09 / AC-W3）：只扣客户可以自由动用的钱（不动持仓保证金和挂单冻结），余额不会变负；
   * 没扣到的差额记挂账，通知风控人员。
   */
  void reverse(DepositMapper.DepositRow d) {
    BigDecimal shortfall = tx.execute(st -> {
      ledger.lock(d.userId());
      if (mapper.markReversed(d.id()) != 1) return null;
      BigDecimal free = funds.withdrawable(d.userId()).max(BigDecimal.ZERO).setScale(8, RoundingMode.DOWN);
      BigDecimal take = d.amount().min(free);
      if (take.signum() > 0) {
        ledger.apply(new LedgerCommand(d.userId(), Bucket.BALANCE, take.negate(), LedgerEntryType.DEPOSIT_REVERSAL, BIZ_DEPOSIT, d.ledgerKey(),
            "充值被链上回滚，冲正 " + d.chain()));
      }
      BigDecimal rest = d.amount().subtract(take);
      if (rest.signum() > 0) mapper.insertSuspense(d.userId(), d.id(), rest, "冲正时可用余额不足");
      return rest;
    });
    if (shortfall == null) return;
    afterCommit(() -> push(d.userId(), Chain.valueOf(d.chain()), "REVERSED", d.amount()));
    log.warn("充值被链上回滚，已冲正 chain={} tx={} user={} amount={} 挂账={}", d.chain(), d.txHash(), d.userId(), d.amount(), shortfall);
    if (shortfall.signum() > 0) {
      risk.notifyStaff("DEPOSIT_SUSPENSE", Map.of("chain", d.chain(), "txHash", d.txHash(), "userId", String.valueOf(d.userId()),
          "amount", d.amount().toPlainString(), "shortfall", shortfall.toPlainString()));
    }
  }

  /** 客户看到的一条充值记录；no 是对外编号（雪花 ID，旧数据等于内部 id） */
  public record DepositRecord(long no, Chain chain, String txHash, BigDecimal amount, String status, int confirmations, java.time.LocalDateTime createdAt) {}

  /** 客户的充值记录（最近的在前） */
  public java.util.List<DepositRecord> history(long userId, int limit) {
    return mapper.byUser(userId, limit).stream()
        .map(d -> new DepositRecord(d.publicNo(), Chain.valueOf(d.chain()), d.txHash(), d.amount(), d.status(), d.confirmations(), d.createdAt())).toList();
  }

  /** 在事务里调用时等提交以后再推（客户端收到推送后重新拉取，要能查到）；不在事务里直接推 */
  private static void afterCommit(Runnable r) {
    if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
      org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
          new org.springframework.transaction.support.TransactionSynchronization() {
            @Override
            public void afterCommit() {
              r.run();
            }
          });
    } else {
      r.run();
    }
  }

  /**
   * 推给本人：充值记录变了。status 和充值记录接口（WalletController）的口径一致：credited / reversed。
   * 推送只是提醒客户端重新拉取充值记录，失败只打日志。
   */
  private void push(long userId, Chain chain, String dbStatus, BigDecimal amount) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("type", "deposit");
    e.put("status", apiStatus(dbStatus));
    e.put("network", chain.name());
    e.put("amount", amount.stripTrailingZeros().toPlainString());
    e.put("confirmations", 0);
    send(userId, e);
  }

  private void send(long userId, Map<String, Object> e) {
    try {
      events.send(userId, e);
    } catch (RuntimeException ex) {
      log.debug("充值推送失败 user={}", userId, ex);
    }
  }

  /** 数据库状态 → 客户端状态名（DepositStatus） */
  public static String apiStatus(String s) {
    return switch (s) {
      case "BELOW_MIN" -> "belowMin";
      default -> s.toLowerCase();
    };
  }
}
