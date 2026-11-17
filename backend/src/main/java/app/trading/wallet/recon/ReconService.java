package app.trading.wallet.recon;

import app.trading.common.error.BizException;
import app.trading.common.error.ValidationException;
import app.trading.risk.api.RiskApi;
import app.trading.trading.api.Bucket;
import app.trading.trading.api.LedgerEntryType;
import app.trading.trading.api.LedgerQueryApi;
import app.trading.wallet.Chain;
import app.trading.payment.api.PaymentApi;
import app.trading.wallet.api.WalletReconApi;
import app.trading.wallet.chain.NodeException;
import app.trading.wallet.deposit.ChainClients;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 对账（需求 4.4-7，S14；第 1 批需求 02 第四节）：钱包记录、账本、钱包提供方三方对一遍，不一致的记成问题、通知风控人员，由人工处理后标记已处理（修复 F5）。
 * 只读，不自动改任何金额。规则见 内部文档 第 16 节。
 * <p>和提供方对（第 1 批）：TopUP 还不能按时间段拉流水（TG-2），补上之前<b>按单号逐笔核对</b>——
 * 近 7 天有到账的客户逐个向提供方拉这个客户的到账，按交易哈希对金额、链、地址；进行中和近 7 天结束的提现逐笔按付款编号查状态和金额。
 * 提供方有、Trading 完全没有记录的客户的到账查不出来（后台对账页写明）。提供方不通时这一部分没跑成，记在 {@link #lastRun()}。
 */
@Service
class ReconService implements WalletReconApi {

  private static final Logger log = LoggerFactory.getLogger(ReconService.class);
  static final Set<String> IN_FLIGHT = Set.of("COOLING", "REVIEWING", "APPROVED", "DELAYED", "BROADCASTING");
  static final Set<String> REFUNDED = Set.of("CANCELLED", "REJECTED", "FAILED_REFUNDED");
  /** 已入账的充值，近 7 天的再去链上核对一次区块哈希（入账后 2 倍确认数内的由充值服务自己复查） */
  static final Duration CHAIN_RECHECK = Duration.ofDays(7);

  /** 和提供方逐笔核对的范围：近 7 天 */
  static final Duration PROVIDER_WINDOW = Duration.ofDays(7);

  private final ReconMapper mapper;
  private final LedgerQueryApi ledger;
  private final ChainClients clients;
  private final RiskApi risk;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final PaymentApi payments;
  private volatile RunInfo lastRun;

  /** 测试用：不和钱包提供方核对 */
  ReconService(ReconMapper mapper, LedgerQueryApi ledger, ChainClients clients, RiskApi risk, TransactionTemplate tx, Clock clock) {
    this(mapper, ledger, clients, risk, tx, clock, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  ReconService(ReconMapper mapper, LedgerQueryApi ledger, ChainClients clients, RiskApi risk, TransactionTemplate tx, Clock clock, PaymentApi payments) {
    this.payments = payments;
    this.mapper = mapper;
    this.ledger = ledger;
    this.clients = clients;
    this.risk = risk;
    this.tx = tx;
    this.clock = clock;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
  }

  /** 发现的一条差异 */
  record Finding(String kind, String refId, Long userId, BigDecimal expected, BigDecimal actual, String detail) {}


  @Override
  public int run() {
    List<Finding> found = new ArrayList<>();
    checkDeposits(found);
    checkWithdrawals(found);
    int[] checked = {0};
    String providerError = null;
    try {
      if (payments != null) checkProvider(found, checked);
    } catch (PaymentApi.Unavailable | UnsupportedOperationException e) {
      providerError = e.getMessage();
      log.warn("对账时钱包提供方不通，按单号核对这一部分没跑成：{}", e.getMessage());
    }
    int fresh = 0;
    for (Finding f : found) {
      // 归集的三类是链上已经发生的事实，处理过（RESOLVED）以后不再重复列出；其他类型只看有没有待处理的
      boolean permanent = f.kind().startsWith("SWEEP_");
      if ((permanent ? mapper.issueCount(f.kind(), f.refId()) : mapper.openIssueCount(f.kind(), f.refId())) > 0) continue;
      mapper.insertIssue(f.kind(), f.refId(), f.userId(), f.expected(), f.actual(), f.detail());
      fresh++;
    }
    if (fresh > 0) {
      log.warn("对账发现 {} 个新问题", fresh);
      risk.notifyStaff("RECON_ISSUES", Map.of("count", String.valueOf(fresh)));
    }
    lastRun = new RunInfo(now(), checked[0], providerError == null, providerError, fresh);
    return fresh;
  }

  @Override
  public Optional<RunInfo> lastRun() {
    return Optional.ofNullable(lastRun);
  }

  /**
   * 和钱包提供方按单号逐笔核对（TG-2 前的降级）。
   * <ul>
   *   <li>到账：近 7 天提供方回调入账的充值，按客户向提供方拉到账，按（交易哈希、金额）配对；配不上的报 DEPOSIT_PROVIDER_MISSING；
   *       交易哈希一样、金额 / 链 / 地址不一样的报 DEPOSIT_PROVIDER_MISMATCH；提供方近 7 天有、Trading 没有的报 DEPOSIT_TRADING_MISSING</li>
   *   <li>提现：已交提供方、进行中或近 7 天结束的，按付款编号查：查不到 PAYOUT_PROVIDER_MISSING；金额不对 PAYOUT_AMOUNT_MISMATCH；
   *       状态对不上（Trading 已完成而提供方没成功、提供方成功而 Trading 没完成、提供方没付而 Trading 没退）PAYOUT_STATUS_MISMATCH</li>
   * </ul>
   */
  private void checkProvider(List<Finding> out, int[] checked) {
    LocalDateTime since = now().minus(PROVIDER_WINDOW);
    Map<String, List<ReconMapper.DepositRow>> byCustomer = new java.util.LinkedHashMap<>();
    for (ReconMapper.DepositRow d : mapper.creditedDeposits()) {
      if (d.providerRef() == null || d.createdAt().isBefore(since)) continue;
      byCustomer.computeIfAbsent(d.provider() + "|" + d.userId(), k -> new ArrayList<>()).add(d);
    }
    for (var e : byCustomer.entrySet()) {
      String provider = e.getKey().substring(0, e.getKey().indexOf('|'));
      long userId = e.getValue().getFirst().userId();
      List<PaymentApi.ProviderDeposit> theirs = new ArrayList<>(payments.depositsOf(userId, provider));
      for (ReconMapper.DepositRow d : e.getValue()) {
        checked[0]++;
        PaymentApi.ProviderDeposit match = null;
        for (PaymentApi.ProviderDeposit t : theirs) if (t.txHash().equalsIgnoreCase(d.txHash()) && t.amount().compareTo(d.amount()) == 0) { match = t; break; }
        if (match == null) for (PaymentApi.ProviderDeposit t : theirs) if (t.txHash().equalsIgnoreCase(d.txHash())) { match = t; break; }
        if (match == null) {
          out.add(new Finding("DEPOSIT_PROVIDER_MISSING", d.key(), userId, d.amount(), BigDecimal.ZERO, provider + " 查不到这笔到账（交易 " + d.txHash() + "）"));
          continue;
        }
        theirs.remove(match);
        boolean sameNet = match.network() != null && match.network().name().equals(d.chain());
        boolean sameAddr = match.address() == null || d.toAddress() == null || d.toAddress().isBlank() || match.address().equalsIgnoreCase(d.toAddress());
        if (match.amount().compareTo(d.amount()) != 0 || !sameNet || !sameAddr) {
          out.add(new Finding("DEPOSIT_PROVIDER_MISMATCH", d.key(), userId, d.amount(), match.amount(),
              "Trading：" + d.amount().stripTrailingZeros().toPlainString() + " " + d.chain() + "；" + provider + "：" + match.amount().stripTrailingZeros().toPlainString() + " "
                  + match.network() + (sameAddr ? "" : "，地址不一样")));
        }
      }
      for (PaymentApi.ProviderDeposit t : theirs) {
        if (t.time() != null && t.time().isBefore(since.toInstant(ZoneOffset.UTC))) continue;
        out.add(new Finding("DEPOSIT_TRADING_MISSING", provider + ":" + t.ref(), userId, t.amount(), BigDecimal.ZERO,
            provider + " 有这笔到账（交易 " + t.txHash() + "），Trading 没有入账"));
      }
    }
    List<ReconMapper.RecentWithdrawal> ws = mapper.recentWithdrawals(since);
    Map<Long, PaymentApi.PayoutInfo> infos = new HashMap<>();
    for (PaymentApi.PayoutInfo i : payments.payoutInfos(ws.stream().map(ReconMapper.RecentWithdrawal::id).toList())) infos.put(i.withdrawalId(), i);
    for (ReconMapper.RecentWithdrawal w : ws) {
      PaymentApi.PayoutInfo i = infos.get(w.id());
      if (i == null || i.providerRef() == null) continue;
      checked[0]++;
      String ref = w.publicNo() + " / " + i.providerRef();
      Optional<PaymentApi.ProviderPayout> p = payments.lookupPayout(i.provider(), i.providerRef());
      if (p.isEmpty()) {
        out.add(new Finding("PAYOUT_PROVIDER_MISSING", ref, w.userId(), w.amount().subtract(w.fee()), BigDecimal.ZERO, i.provider() + " 查不到这笔付款"));
        continue;
      }
      BigDecimal net = w.amount().subtract(w.fee());
      if (p.get().amount().compareTo(net) != 0) {
        out.add(new Finding("PAYOUT_AMOUNT_MISMATCH", ref, w.userId(), net, p.get().amount(), "付款金额和提现金额 − 手续费不一致"));
      }
      var st = p.get().state();
      boolean bad = ("COMPLETED".equals(w.status()) && st != app.trading.payment.api.PayoutState.COMPLETED)
          || ("FAILED_REFUNDED".equals(w.status()) && !st.notPaid())
          || ("BROADCASTING".equals(w.status()) && st.terminal());
      if (bad) {
        out.add(new Finding("PAYOUT_STATUS_MISMATCH", ref, w.userId(), null, null, "Trading：" + w.status() + "；" + i.provider() + "：" + st));
      }
    }
  }

  /** 充值：钱包记录 ↔ 账本 ↔ 链上；挂账 */
  private void checkDeposits(List<Finding> out) {
    Map<String, BigDecimal> credited = ledger.totals("DEPOSIT", LedgerEntryType.DEPOSIT, Bucket.BALANCE);
    Map<String, BigDecimal> reversed = ledger.totals("DEPOSIT", LedgerEntryType.DEPOSIT_REVERSAL, Bucket.BALANCE);
    Map<Long, BigDecimal> suspenseByDeposit = new HashMap<>();
    for (ReconMapper.SuspenseRow s : mapper.suspenses()) {
      suspenseByDeposit.merge(s.depositId(), s.amount(), BigDecimal::add);
      if ("OPEN".equals(s.status())) {
        out.add(new Finding("SUSPENSE_OPEN", String.valueOf(s.id()), s.userId(), BigDecimal.ZERO, s.amount(), "充值冲正时余额不够扣的差额，待处理（充值 #" + s.depositId() + "）"));
      }
    }
    Set<String> known = new HashSet<>();
    LocalDateTime recheckFrom = now().minus(CHAIN_RECHECK);
    for (ReconMapper.DepositRow d : mapper.creditedDeposits()) {
      String key = d.key();
      known.add(key);
      BigDecimal in = credited.getOrDefault(key, BigDecimal.ZERO);
      if (in.compareTo(d.amount()) != 0) {
        out.add(new Finding("DEPOSIT_LEDGER_MISMATCH", key, d.userId(), d.amount(), in, "充值记录的金额和账本入账不一致"));
      }
      if ("REVERSED".equals(d.status())) {
        BigDecimal back = reversed.getOrDefault(key, BigDecimal.ZERO).negate().add(suspenseByDeposit.getOrDefault(d.id(), BigDecimal.ZERO));
        if (back.compareTo(d.amount()) != 0) {
          out.add(new Finding("DEPOSIT_REVERSAL_MISMATCH", key, d.userId(), d.amount(), back, "冲正金额 + 挂账 应该等于充值金额"));
        }
      } else if (d.providerRef() == null && !d.createdAt().isBefore(recheckFrom)) {
        chainCheck(d, key, out);
      }
    }
    for (Map.Entry<String, BigDecimal> e : credited.entrySet()) {
      if (!known.contains(e.getKey())) {
        out.add(new Finding("DEPOSIT_UNKNOWN_CREDIT", e.getKey(), null, BigDecimal.ZERO, e.getValue(), "账本里有充值入账，钱包里没有对应的链上充值"));
      }
    }
  }

  /** 已入账的充值，区块哈希还和入账时一样吗（链上被深度回滚、超出充值服务复查范围时在这里发现） */
  private void chainCheck(ReconMapper.DepositRow d, String key, List<Finding> out) {
    var client = clients.get(Chain.valueOf(d.chain()));
    if (client.isEmpty()) return;
    try {
      Optional<String> hash = client.get().blockHash(d.blockNumber());
      if (hash.isEmpty() || !hash.get().equalsIgnoreCase(d.blockHash())) {
        out.add(new Finding("DEPOSIT_NOT_ON_CHAIN", key, d.userId(), d.amount(), BigDecimal.ZERO, "入账时的区块哈希和链上现在的不一致"));
      }
    } catch (NodeException | IllegalStateException e) {
      log.warn("对账时查不到链上区块 {}：{}", key, e.getMessage());
    }
  }

  /** 提现：每笔的账本记录和状态一致；每个用户的提现冻结 = 进行中的提现合计 */
  private void checkWithdrawals(List<Finding> out) {
    Map<String, BigDecimal> freeze = ledger.totals("WITHDRAW", LedgerEntryType.WITHDRAW_FREEZE, Bucket.WITHDRAW_FROZEN);
    Map<String, BigDecimal> unfreeze = ledger.totals("WITHDRAW", LedgerEntryType.WITHDRAW_UNFREEZE, Bucket.WITHDRAW_FROZEN);
    Map<String, BigDecimal> payout = ledger.totals("WITHDRAW", LedgerEntryType.WITHDRAW_PAYOUT, Bucket.WITHDRAW_FROZEN);
    Map<Long, BigDecimal> inFlight = new HashMap<>();
    for (ReconMapper.WithdrawalRow w : mapper.withdrawals()) {
      String id = String.valueOf(w.id());
      BigDecimal f = freeze.getOrDefault(id, BigDecimal.ZERO);
      BigDecimal u = unfreeze.getOrDefault(id, BigDecimal.ZERO).negate();
      BigDecimal p = payout.getOrDefault(id, BigDecimal.ZERO).negate();
      BigDecimal zero = BigDecimal.ZERO;
      boolean ok;
      if (IN_FLIGHT.contains(w.status())) {
        ok = f.compareTo(w.amount()) == 0 && u.signum() == 0 && p.signum() == 0;
        inFlight.merge(w.userId(), w.amount(), BigDecimal::add);
      } else if ("COMPLETED".equals(w.status())) {
        ok = f.compareTo(w.amount()) == 0 && u.signum() == 0 && p.compareTo(w.amount()) == 0;
      } else if (REFUNDED.contains(w.status())) {
        ok = f.compareTo(w.amount()) == 0 && u.compareTo(w.amount()) == 0 && p.compareTo(zero) == 0;
      } else {
        ok = false;
      }
      if (!ok) {
        out.add(new Finding("WITHDRAW_LEDGER_MISMATCH", id, w.userId(), w.amount(), f.subtract(u).subtract(p),
            "状态 " + w.status() + "：冻结 " + f.toPlainString() + "、退回 " + u.toPlainString() + "、转出 " + p.toPlainString()));
      }
    }
    Map<Long, BigDecimal> frozen = ledger.withdrawFrozenByUser();
    Set<Long> users = new HashSet<>(frozen.keySet());
    users.addAll(inFlight.keySet());
    for (Long u : users) {
      BigDecimal expected = inFlight.getOrDefault(u, BigDecimal.ZERO);
      BigDecimal actual = frozen.getOrDefault(u, BigDecimal.ZERO);
      if (expected.compareTo(actual) != 0) {
        out.add(new Finding("WITHDRAW_FROZEN_MISMATCH", String.valueOf(u), u, expected, actual, "账户的提现冻结应该等于进行中的提现合计"));
      }
    }
  }

  @Override
  public List<Issue> issues(String status, int limit) {
    return mapper.issues(status == null ? "OPEN" : status, Math.min(Math.max(limit, 1), 500));
  }

  @Override
  public List<Issue> issues(String status, int offset, int limit) {
    return mapper.issuePage(status == null || status.isBlank() ? null : status, Math.max(0, offset), Math.min(Math.max(limit, 1), 500));
  }

  @Override
  public long countIssues(String status) {
    return mapper.countIssues(status == null || status.isBlank() ? null : status);
  }

  @Override
  public void resolve(long issueId, long adminId, String note) {
    if (note == null || note.isBlank()) throw new ValidationException(List.of(new ValidationException.Field("note", "required")));
    if (note.length() > 500) throw new ValidationException(List.of(new ValidationException.Field("note", "tooLong")));
    tx.executeWithoutResult(st -> {
      Issue i = mapper.lockIssue(issueId);
      if (i == null) throw new BizException("NOT_FOUND", "对账问题不存在");
      if (mapper.resolveIssue(issueId, adminId, note.trim(), now()) != 1) throw new BizException("ISSUE_NOT_OPEN", "只有待处理的问题可以标记为已处理");
      if ("SUSPENSE_OPEN".equals(i.kind())) mapper.resolveSuspense(Long.parseLong(i.refId()), now(), note.trim());
    });
  }
}
