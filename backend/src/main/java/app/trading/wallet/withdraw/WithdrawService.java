package app.trading.wallet.withdraw;

import app.trading.account.api.AccountApi;
import app.trading.account.api.KycStatus;
import app.trading.account.api.UserStatus;
import app.trading.account.api.WithdrawPinApi;
import app.trading.common.error.BizException;
import app.trading.common.error.ValidationException;
import app.trading.common.id.Snowflake;
import app.trading.trading.api.Bucket;
import app.trading.trading.api.FundsApi;
import app.trading.trading.api.LedgerApi;
import app.trading.trading.api.LedgerCommand;
import app.trading.trading.api.LedgerEntryType;
import app.trading.notification.api.NotifyApi;
import app.trading.trading.api.UserEventsApi;
import app.trading.wallet.Chain;
import app.trading.wallet.api.AutoApprove;
import app.trading.wallet.api.WithdrawAdminApi;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 提现（内部文档）。状态：
 * 冷静期 → 待审核 → 已审核待上链（大额先延迟 6 小时）→ 上链中 → 已完成；
 * 任何失败路径（撤销、驳回、紧急取消、确定没上链）都把冻结金额完整退回。
 * 每次状态变化都在锁住提现单之后按"从哪个状态到哪个状态"条件更新，并记一条提现事件。
 * <p>v2 提现自动审批（内部文档）：提交后预判一次（只做参考）；冷静期结束时在同一个事务里复判，已启用的规则全部通过（并且总开关打开）时由系统代替审核人员
 * "通过"（{@value #AUTO_ACTOR}），之后和人工通过完全一样（大额延迟 6 小时、上链）；否则转人工，和以前一样。规则只决定要不要人工看，不放宽任何限额。
 */
@Service
@EnableConfigurationProperties(WithdrawProperties.class)
public class WithdrawService implements WithdrawAdminApi {

  private static final Logger log = LoggerFactory.getLogger(WithdrawService.class);
  static final String BIZ_WITHDRAW = "WITHDRAW";
  /** 自动通过时提现事件的操作人（事件表 actor 列 VARCHAR(32)） */
  static final String AUTO_ACTOR = "system:auto-approve";
  private static final Pattern AMOUNT = Pattern.compile("\\d{1,12}(\\.\\d{1,8})?");

  private final WithdrawMapper mapper;
  private final LedgerApi ledger;
  private final FundsApi funds;
  private final AccountApi account;
  private final UserEventsApi events;
  private final NotifyApi notify;
  private final WithdrawSenders senders;
  private final WithdrawLimits limits;
  private final AutoApproveService auto;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final Snowflake ids;

  private final TelegramWithdrawGate tgGate;
  private final WithdrawPinApi pins;

  /** 冻结时直接取消并退回的在途状态（第 4 批审查 🔴4a-1）；已审核待放款（APPROVED）不取消，只暂停交出去，见 {@link #blockInFlight} */
  static final List<String> CANCEL_ON_FREEZE = List.of("COOLING", "REVIEWING", "DELAYED");

  WithdrawService(WithdrawMapper mapper, LedgerApi ledger, FundsApi funds, AccountApi account, UserEventsApi events, NotifyApi notify, WithdrawSenders senders,
      WithdrawLimits limits, AutoApproveService auto, TransactionTemplate tx, Clock clock, Snowflake ids, TelegramWithdrawGate tgGate, WithdrawPinApi pins) {
    this.tgGate = tgGate;
    this.pins = pins;
    this.mapper = mapper;
    this.ledger = ledger;
    this.funds = funds;
    this.account = account;
    this.events = events;
    this.notify = notify;
    this.senders = senders;
    this.limits = limits;
    this.auto = auto;
    this.tx = tx;
    this.clock = clock;
    this.ids = ids;
  }

  LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
  }

  /** 今天（UTC）已经用掉的额度 */
  BigDecimal usedToday(long userId) {
    return mapper.usedSince(userId, now().toLocalDate().atStartOfDay());
  }

  // ---------- 客户 ----------

  /**
   * 提交提现（AC-17）。顺序：字段 → KYC → 白名单（存在、已生效）→ 最小、单笔 → 锁账户后查当日累计和可提金额 → 冻结。
   */
  WithdrawalRow submit(long userId, String whitelistId, String amountText) {
    return submit(userId, whitelistId, amountText, null, "WEB");
  }

  /**
   * TG 客户（第 4 批，{@link TelegramWithdrawGate}）：总开关 → 字段 → 提现密码 → KYC → 开户满 24 小时、安全事件禁提 → 白名单 → 锁账户后查 TG 每天额度。
   * 记规则（TG：一律人工审核）、入口（WEB / BOT）、没邮箱的首笔标人工核实。
   *
   * @param source 提交入口：WEB 网页登录的会话 / BOT 从机器人进入的会话
   */
  WithdrawalRow submit(long userId, String whitelistId, String amountText, app.trading.account.api.WithdrawPinApi.PinInput pin, String source) {
    tgGate.check(userId); // TB-1：TG 客户提现默认关（第 4 批验收后打开）
    boolean tg = tgGate.isTg(userId);
    ValidationException.Collector errs = new ValidationException.Collector();
    BigDecimal amount = null;
    String a = amountText == null ? "" : amountText.trim();
    if (a.isEmpty()) errs.add("amount", "required");
    else if (!AMOUNT.matcher(a).matches() || new BigDecimal(a).signum() <= 0) errs.add("amount", "positiveNumber");
    else if (new BigDecimal(a).scale() > 2) errs.add("amount", "amountDigits");
    else amount = new BigDecimal(a);
    Long wid = null;
    try {
      wid = whitelistId == null ? null : Long.valueOf(whitelistId);
    } catch (NumberFormatException e) {
      wid = null;
    }
    if (wid == null) errs.add("whitelistId", "selectAddress");
    errs.throwIfAny();

    // TG 规则：先核对提现密码（输错计数），再查其他条件
    TelegramWithdrawGate.TgContext tgc = tg ? tgGate.context(userId) : null;
    if (tg) tgGate.verifyPin(userId, pin);
    if (account.kycStatus(userId) != KycStatus.APPROVED) throw new BizException("KYC_REQUIRED", "提现前需要通过身份认证");
    if (tg) tgGate.checkHold(userId, now());
    WhitelistRow w = mapper.whitelistById(userId, wid);
    if (w == null) throw new BizException("NOT_WHITELISTED", "只能提到白名单地址");
    if (w.activeAt.isAfter(now())) throw new BizException("WHITELIST_NOT_ACTIVE", "白名单地址添加满 24 小时后才能使用");
    if (amount.compareTo(props().minAmount()) < 0) throw new BizException("AMOUNT_BELOW_MIN", "低于最小提现金额");
    if (amount.compareTo(props().maxPerTx()) > 0) throw new BizException("AMOUNT_ABOVE_MAX", "超过单笔提现上限");
    Chain network = Chain.valueOf(w.network);
    BigDecimal fee = props().fee(network);
    if (amount.compareTo(fee) <= 0) throw new BizException("AMOUNT_BELOW_MIN", "金额不够付手续费");
    final BigDecimal amt = amount;

    WithdrawalRow r = tx.execute(st -> {
      ledger.lock(userId);
      // 当日累计必须严格小于上限（需求 4.4-4，和客户端一致）；锁住账户后再算，并发提交不会一起超过
      BigDecimal used = usedToday(userId);
      if (used.add(amt).compareTo(props().dailyLimit()) >= 0) throw new BizException("DAILY_LIMIT_EXCEEDED", "超过当日提现累计上限");
      // TG 每天额度（网页和机器人合并：今天所有没撤销、没驳回、没失败的提现都算），和上面同一把锁里
      if (tgc != null && used.add(amt).compareTo(tgc.dailyQuota()) > 0) {
        throw new BizException("TG_DAILY_LIMIT", "超过今天的提现额度（每天 " + tgc.dailyQuota().stripTrailingZeros().toPlainString() + " USDT，还能提 "
            + tgc.dailyQuota().subtract(used).max(BigDecimal.ZERO).stripTrailingZeros().toPlainString() + "）");
      }
      if (amt.compareTo(funds.withdrawable(userId)) > 0) throw new BizException("INSUFFICIENT_BALANCE", "可提金额不足");
      WithdrawalRow n = new WithdrawalRow();
      n.publicNo = ids.next();
      n.userId = userId;
      n.network = network.name();
      n.address = w.address;
      n.amount = amt;
      n.fee = fee;
      n.status = "COOLING";
      n.ruleType = tg ? "TG" : "WEB";
      n.source = "BOT".equals(source) ? "BOT" : "WEB";
      // 没邮箱的 TG 客户：首笔（还没有完成过的提现）一律人工核实，没有豁免（03 页第 0 节、08 页第 0 节）
      n.firstCheck = tgc != null && !tgc.hasEmail() && mapper.completedCount(userId) == 0;
      n.createdAt = now();
      n.coolingEndsAt = n.createdAt.plus(props().cooling());
      mapper.insertWithdrawal(n);
      ledger.apply(new LedgerCommand(userId, Bucket.BALANCE, amt.negate(), LedgerEntryType.WITHDRAW_FREEZE, BIZ_WITHDRAW, String.valueOf(n.id), "提现冻结"));
      ledger.apply(new LedgerCommand(userId, Bucket.WITHDRAW_FROZEN, amt, LedgerEntryType.WITHDRAW_FREEZE, BIZ_WITHDRAW, String.valueOf(n.id), "提现冻结"));
      mapper.insertEvent(n.id, null, "COOLING", "user:" + userId, null);
      return n;
    });
    notify(r);
    // 提交时预判（不占额度、不决定结果，出错只记日志）
    auto.precheck(r.id);
    return r;
  }

  /** 冷静期内撤销（AC-18）：冻结立即退回；过了冷静期不能撤销 */
  WithdrawalRow cancel(long userId, long id) {
    WithdrawalRow r = tx.execute(st -> {
      WithdrawalRow w = mapper.lock(id);
      if (w == null || w.userId != userId) throw new BizException("NOT_FOUND", "提现不存在");
      if (!"COOLING".equals(w.status) || !now().isBefore(w.coolingEndsAt)) throw new BizException("WITHDRAW_NOT_CANCELLABLE", "冷静期已过，不能撤销");
      move(w, "CANCELLED", "user:" + userId, null);
      refund(w);
      return w;
    });
    notify(r);
    return r;
  }

  // ---------- 后台 ----------

  @Override
  public void approve(long withdrawalId, long adminId) {
    approve(withdrawalId, adminId, false);
  }

  /** 首笔人工核实（第 4 批）：标了 first_check 的，审核员要确认"已核实"（firstChecked = true）才能通过 */
  @Override
  public void approve(long withdrawalId, long adminId, boolean firstChecked) {
    WithdrawalRow r = tx.execute(st -> {
      WithdrawalRow w = reviewing(withdrawalId);
      // 第 4 批审查 🔴4a-1：账户冻结或提现冻结时不能通过（在途的也算，08 页第 2 节）
      String frozen = frozenReason(w.userId);
      if (frozen != null) throw new BizException("WITHDRAW_FROZEN", "客户已冻结提现，请先核对（" + frozen + "）");
      if (w.firstCheck) {
        if (!firstChecked) throw new BizException("FIRST_CHECK_REQUIRED", "这是没邮箱客户的首笔提现：要先人工核实（视频核对本人、来源证明）再通过");
        mapper.markFirstChecked(w.id, adminId);
      }
      boolean large = w.amount.compareTo(props().largeAmount()) >= 0;
      LocalDateTime delay = large ? now().plus(props().largeDelay()) : null;
      mapper.setReview(w.id, adminId, now(), null, delay);
      move(w, large ? "DELAYED" : "APPROVED", "admin:" + adminId, large ? "大额，延迟到 " + delay : null);
      w.delayEndsAt = delay;
      return w;
    });
    notify(r);
    if ("APPROVED".equals(r.status)) send(r.id);
  }

  @Override
  public void reject(long withdrawalId, long adminId, String reason) {
    if (reason == null || reason.isBlank()) throw new ValidationException(List.of(new ValidationException.Field("reason", "required")));
    if (reason.length() > 500) throw new ValidationException(List.of(new ValidationException.Field("reason", "tooLong")));
    WithdrawalRow r = tx.execute(st -> {
      WithdrawalRow w = reviewing(withdrawalId);
      mapper.setReview(w.id, adminId, now(), reason.trim(), null);
      move(w, "REJECTED", "admin:" + adminId, reason.trim());
      refund(w);
      w.rejectReason = reason.trim();
      return w;
    });
    notify(r);
  }

  @Override
  public void emergencyCancel(long withdrawalId, long adminId, String reason) {
    if (reason == null || reason.isBlank()) throw new ValidationException(List.of(new ValidationException.Field("reason", "required")));
    WithdrawalRow r = tx.execute(st -> {
      WithdrawalRow w = mapper.lock(withdrawalId);
      if (w == null) throw new BizException("NOT_FOUND", "提现不存在");
      if (!"DELAYED".equals(w.status)) throw new BizException("WITHDRAW_NOT_CANCELLABLE", "只有延迟期内的大额提现可以紧急取消");
      move(w, "CANCELLED", "admin:" + adminId, "紧急取消：" + reason.trim());
      refund(w);
      return w;
    });
    notify(r);
  }

  private WithdrawalRow reviewing(long id) {
    WithdrawalRow w = mapper.lock(id);
    if (w == null) throw new BizException("NOT_FOUND", "提现不存在");
    if (!"REVIEWING".equals(w.status)) throw new BizException("WITHDRAW_NOT_REVIEWABLE", "只能审核待审核的提现");
    return w;
  }

  // ---------- 系统（定时任务） ----------

  /** 推进到期的状态：冷静期满 → 待审核；大额延迟满 → 待上链（修复 F1：直接进入上链，不依赖别的事件）；然后发送所有待上链的 */
  void advance() {
    LocalDateTime now = now();
    for (Long id : mapper.due("COOLING", "cooling_ends_at", now)) coolingEnded(id);
    for (Long id : mapper.due("DELAYED", "delay_ends_at", now)) delayEnded(id);
    for (Long id : mapper.withStatus("APPROVED")) send(id);
  }

  /**
   * 冷静期结束（v2 自动审批，方案第 3、5 节）：锁住提现单、确认还在冷静期 → 复判（总开关打开时锁全局锁，R2 / R3 在锁里现算）→ 冷静期 → 待审核（system）；
   * 复判全部通过时同一个事务里再 待审核 → 已审核待上链 / 延迟中（{@value #AUTO_ACTOR}），写 review_mode = AUTO。提交以后才上链（不在事务里访问节点）。
   * 复判整个出错时转人工（尽量记一条 ERROR 判定），不会因为出错而放行，也不会卡在冷静期。
   */
  void coolingEnded(long id) {
    WithdrawalRow r;
    try {
      // 配置在事务外一次读出：事务里第一条一致性读要发生在拿到全局锁之后，才能看到前一笔刚提交的自动通过
      AutoApprove.Settings s = auto.settings();
      r = tx.execute(st -> {
        WithdrawalRow w = mapper.lock(id);
        if (w == null || !"COOLING".equals(w.status) || now().isBefore(w.coolingEndsAt)) return null;
        AutoApproveService.Decision d = auto.decideFinal(w, s);
        // 第 4 批审查 🔴4a-1：冻结了就取消退回，不进审核。要在复判之后查：复判在全局锁里做第一条一致性读（R2 / R3 现算），
        // 先读冻结会提前建立快照，看不到前一笔刚提交的自动通过（并发测试会超 R3）
        String frozen = frozenReason(w.userId);
        if (frozen != null) return cancelFrozen(w, frozen);
        // TG 规则一律人工审核（TB-20、TB-22）：判定照记（后台参考），不自动通过
        boolean tgRule = "TG".equals(w.ruleType);
        move(w, "REVIEWING", "system", tgRule ? "冷静期结束（TG 规则，一律人工审核）" : "冷静期结束");
        if (d.approved() && !tgRule) autoApprove(w, d);
        return w;
      });
    } catch (RuntimeException e) {
      log.error("提现 {} 冷静期结束时自动审批判定出错，转人工", id, e);
      r = coolingEndedManually(id, e);
    }
    if (r == null) return;
    notify(r);
    if ("APPROVED".equals(r.status)) send(r.id);
  }

  /** 自动通过：和人工通过一样，< 大额阈值直接待上链，≥ 大额阈值延迟 6 小时；审核人为空，事件操作人是 system:auto-approve */
  private void autoApprove(WithdrawalRow w, AutoApproveService.Decision d) {
    boolean large = w.amount.compareTo(props().largeAmount()) >= 0;
    LocalDateTime delay = large ? now().plus(props().largeDelay()) : null;
    auto.markApproved(w.id, now(), delay);
    String note = "自动审批通过（判定 #" + d.checkId() + "，配置第 " + d.configVersion() + " 版）" + (large ? "；大额，延迟到 " + delay : "");
    move(w, large ? "DELAYED" : "APPROVED", AUTO_ACTOR, note);
    w.delayEndsAt = delay;
  }

  /** 复判出错后的兜底：记一条 ERROR 判定并转人工；连这也失败就只转人工 */
  private WithdrawalRow coolingEndedManually(long id, RuntimeException cause) {
    try {
      AutoApprove.Settings s = auto.settings();
      return tx.execute(st -> {
        WithdrawalRow w = mapper.lock(id);
        if (w == null || !"COOLING".equals(w.status) || now().isBefore(w.coolingEndsAt)) return null;
        auto.recordError(w, s, "判定出错：" + cause.getClass().getSimpleName());
        move(w, "REVIEWING", "system", "冷静期结束（自动审批判定出错，转人工）");
        return w;
      });
    } catch (RuntimeException e) {
      log.error("提现 {} 记录自动审批出错失败，直接转人工", id, e);
      return tx.execute(st -> {
        WithdrawalRow w = mapper.lock(id);
        if (w == null || !"COOLING".equals(w.status)) return null;
        move(w, "REVIEWING", "system", "冷静期结束（自动审批判定出错，转人工）");
        return w;
      });
    }
  }

  /** 大额延迟期满 → 待上链；冻结了就取消退回（第 4 批审查 🔴4a-1） */
  private void delayEnded(long id) {
    WithdrawalRow r = tx.execute(st -> {
      WithdrawalRow w = mapper.lock(id);
      if (w == null || !"DELAYED".equals(w.status)) return null;
      String frozen = frozenReason(w.userId);
      if (frozen != null) return cancelFrozen(w, frozen);
      move(w, "APPROVED", "system", "延迟期结束，进入上链");
      return w;
    });
    if (r != null) notify(r);
  }

  /** 待上链 → 交给广播器。没有广播器或广播器说"等一等"时留在待上链（W4）；确定没上链时失败退款 */
  void send(long id) {
    WithdrawalRow w = mapper.byId(id);
    if (w == null || !"APPROVED".equals(w.status)) return;
    if (w.heldAt != null) return; // 暂停中（第 4 批 4b 审查 🟡1）：等后台逐笔选"继续放款"或"取消并退回"
    // 第 4 批审查 🔴4a-1：冻结期间不交钱包提供方，标成暂停（解除冻结后也不自动继续）。不在这里取消退回：
    // 另一个线程可能正在交出去（审核通过的线程和定时任务都会调 send），这时退回会重复出金
    String frozen = frozenReason(w.userId);
    if (frozen != null) {
      if (mapper.hold(id, frozen, now()) == 1) log.warn("提现 {} 的客户{}，暂停交钱包提供方", id, frozen);
      return;
    }
    var sender = senders.get(Chain.valueOf(w.network));
    if (sender.isEmpty()) {
      log.debug("{} 没有广播器，提现 {} 留在待上链", w.network, id);
      return;
    }
    // 认领：只有待放款、没暂停的才交出去，次数 +1；后台"取消并退回"只允许从没认领过的（防重复出金）
    if (mapper.claimSend(id) != 1) return;
    WithdrawSender.Outcome out;
    try {
      out = sender.get().send(new WithdrawSender.Order(w.id, w.publicNo, w.userId, w.network, w.address, w.amount.subtract(w.fee)));
    } catch (RuntimeException e) {
      // 付款器出了意外（例如钱包提供方没配置）：审核结果已经保存，留在待放款，下次定时任务再试；不退款
      log.error("提现 {} 交出去付款时出错，留在待放款：{}", id, e.toString());
      out = new WithdrawSender.Wait(e.getClass().getSimpleName());
    }
    switch (out) {
      case WithdrawSender.Submitted sub -> {
        WithdrawalRow r = tx.execute(st -> {
          WithdrawalRow l = mapper.lock(id);
          if (!"APPROVED".equals(l.status)) return null;
          move(l, "BROADCASTING", "system", "已交钱包提供方，付款编号 " + sub.providerRef() + "，等待放款审核");
          return l;
        });
        if (r != null) notify(r);
      }
      case WithdrawSender.Sent s -> {
        WithdrawalRow r = tx.execute(st -> {
          WithdrawalRow l = mapper.lock(id);
          if (!"APPROVED".equals(l.status)) return null;
          mapper.setTx(id, s.txHash());
          move(l, "BROADCASTING", "system", s.txHash());
          return l;
        });
        if (r != null) notify(r);
      }
      case WithdrawSender.Wait wait -> log.warn("提现 {} 暂时不能上链：{}", id, wait.reason());
      case WithdrawSender.Rejected rej -> failed(id, "APPROVED", rej.reason());
    }
  }

  /** 上链中的提现查链上结果（S13b）：确认够了完成；确定转账没发生则失败退款；其他情况继续等（加价重发、过期重建在广播器里） */
  void track() {
    for (Long id : mapper.withStatus("BROADCASTING")) {
      WithdrawalRow w = mapper.byId(id);
      var sender = senders.get(Chain.valueOf(w.network));
      if (sender.isEmpty()) continue;
      switch (sender.get().check(id)) {
        case WithdrawSender.Confirmed c -> {
          mapper.setTx(id, c.txHash());
          confirmed(id);
        }
        case WithdrawSender.Failed f -> failed(id, "BROADCASTING", f.reason());
        case WithdrawSender.Pending p -> { }
      }
    }
  }

  /**
   * 钱包提供方的付款状态变了（回调或后台"重新查询"，在调用方的事务里）：
   * <ul>
   *   <li>还在处理（等待放款审核、放款中）：提现单还是"已审核待放款"时（回调比提交的返回先到）改成"放款中"</li>
   *   <li>成功：记交易哈希，→ 已完成，扣掉冻结</li>
   *   <li>失败、驳回、取消：→ 失败退回，冻结全部退回客户（AC-W5）</li>
   *   <li>未知：什么都不做（不退款，后台标红等人查）</li>
   * </ul>
   */
  public void payoutUpdate(long id, String providerRef, String state, String txHash, String reason) {
    WithdrawalRow w = mapper.byId(id);
    if (w == null) return;
    switch (state) {
      case "UNKNOWN", "SUBMITTING" -> { }
      case "COMPLETED" -> {
        submittedByProvider(id, providerRef);
        if (txHash != null) mapper.setTx(id, txHash);
        confirmed(id);
      }
      case "FAILED", "REJECTED", "CANCELLED" -> {
        String why = "钱包提供方" + switch (state) {
          case "REJECTED" -> "驳回放款";
          case "CANCELLED" -> "取消放款";
          default -> "放款失败";
        } + (reason == null ? "" : "：" + reason);
        if ("APPROVED".equals(w.status)) failed(id, "APPROVED", why);
        else failed(id, "BROADCASTING", why);
      }
      default -> submittedByProvider(id, providerRef);
    }
  }

  /** 提供方已经收下这笔（回调先到）：已审核待放款 → 放款中 */
  private void submittedByProvider(long id, String providerRef) {
    WithdrawalRow r = tx.execute(st -> {
      WithdrawalRow l = mapper.lock(id);
      if (l == null || !"APPROVED".equals(l.status)) return null;
      move(l, "BROADCASTING", "system", "已交钱包提供方，付款编号 " + providerRef + "，等待放款审核");
      return l;
    });
    if (r != null) notify(r);
  }

  /** 链上确认：上链中 → 已完成，扣掉提现冻结（S13b 的确认任务调用） */
  public void confirmed(long id) {
    WithdrawalRow r = tx.execute(st -> {
      WithdrawalRow w = mapper.lock(id);
      if (w == null || !"BROADCASTING".equals(w.status)) return null;
      move(w, "COMPLETED", "system", null);
      mapper.setCompleted(id, now());
      ledger.apply(new LedgerCommand(w.userId, Bucket.WITHDRAW_FROZEN, w.amount.negate(), LedgerEntryType.WITHDRAW_PAYOUT, BIZ_WITHDRAW,
          String.valueOf(w.id), "提现完成（含手续费 " + w.fee.stripTrailingZeros().toPlainString() + "）"));
      return w;
    });
    if (r != null) notify(r);
  }

  /** 确定没有上链（广播被拒、TRON 交易过期查不到）：失败退款（AC-20）。只能从待上链或上链中进入 */
  public void failed(long id, String expectedStatus, String reason) {
    WithdrawalRow r = tx.execute(st -> {
      WithdrawalRow w = mapper.lock(id);
      if (w == null || !expectedStatus.equals(w.status)) return null;
      move(w, "FAILED_REFUNDED", "system", reason);
      refund(w);
      return w;
    });
    if (r != null) {
      log.warn("提现 {} 上链失败，已退回：{}", id, reason);
      notify(r);
    }
  }

  // ---------- 公共 ----------

  private void move(WithdrawalRow w, String to, String actor, String note) {
    if (mapper.transit(w.id, w.status, to) != 1) throw new IllegalStateException("提现状态已变化：" + w.id);
    mapper.insertEvent(w.id, w.status, to, actor, note);
    String from = w.status;
    w.status = to;
    if (notifyCustomer(from, to, actor)) {
      Map<String, String> p = new LinkedHashMap<>();
      // 对外编号（雪花 ID，约定 25）：和客户端提现记录上显示的编号一致（体验优化 1021 UX-11，以前传的是内部自增 id）
      p.put("orderId", String.valueOf(w.publicNo));
      p.put("amount", w.amount.stripTrailingZeros().toPlainString());
      p.put("status", to);
      // 只有驳回把原因告诉客户；紧急取消、上链失败的原因是内部信息
      if ("REJECTED".equals(to) && note != null) p.put("reason", note);
      notify.toUser(w.userId, "WITHDRAW_STATUS", p);
    }
  }

  /**
   * 哪些状态变化通知客户（通知方案 N6）：客户自己的操作（提交、撤销）不通知，冷静期结束进入审核不通知；
   * 审核通过（含大额延迟）、驳回、开始上链、完成、失败退回、紧急取消都通知。延迟期满进入上链时不再通知"审核通过"，紧接着会通知"开始上链"。
   */
  static boolean notifyCustomer(String from, String to, String actor) {
    if (actor != null && actor.startsWith("user:")) return false;
    return switch (to) {
      case "APPROVED" -> "REVIEWING".equals(from);
      case "DELAYED", "REJECTED", "BROADCASTING", "COMPLETED", "FAILED_REFUNDED", "CANCELLED" -> true;
      default -> false;
    };
  }

  /** 账户冻结或提现冻结时的原因（"账户已冻结" / "提现已冻结"）；都没有时为空。冻结只能人工解除 */
  String frozenReason(long userId) {
    if (account.status(userId) == UserStatus.FROZEN) return "账户已冻结";
    if (pins.status(userId).withdrawFrozen()) return "提现已冻结";
    return null;
  }

  /** 在调用方的事务里（已锁住提现单）：冻结 → 取消并退回余额，通知客户（CANCELLED 会通知） */
  private WithdrawalRow cancelFrozen(WithdrawalRow w, String reason) {
    move(w, "CANCELLED", "system", "冻结，自动取消并退回：" + reason);
    refund(w);
    return w;
  }

  /**
   * 冻结时处理这个客户在途的提现（第 4 批审查 🔴4a-1，由 {@link WithdrawFreezeHandler} 在冻结的事务提交以后调用）：
   * 冷静期、待审核、大额延迟中的取消并退回余额（每笔一个事务，锁住再看状态）；已审核待放款的不取消——可能正有线程在交给钱包提供方，
   * 退回会重复出金——标成暂停（4b 审查 🟡1：解除冻结后也不自动继续，要后台逐笔选"继续放款"或"取消并退回"，见 {@link #releaseHeld}、
   * {@link #cancelHeld}）；放款中（已交提供方）的拦不住，后台同样标出来，提供方那边还有第二道人工放款审核。返回取消的笔数。
   */
  public int blockInFlight(long userId, String reason) {
    // 在冻结事务的 afterCommit 里被调用：原事务的资源还绑着，必须开新事务（REQUIRES_NEW），推送也直接推（不能再登记 afterCommit）
    TransactionTemplate fresh = new TransactionTemplate(tx.getTransactionManager());
    fresh.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    int n = 0;
    for (Long id : mapper.idsOfUserIn(userId, List.of("APPROVED"))) {
      fresh.executeWithoutResult(st -> mapper.hold(id, reason, now()));
    }
    for (Long id : mapper.idsOfUserIn(userId, CANCEL_ON_FREEZE)) {
      WithdrawalRow r = fresh.execute(st -> {
        WithdrawalRow w = mapper.lock(id);
        if (w == null || !CANCEL_ON_FREEZE.contains(w.status)) return null;
        return cancelFrozen(w, reason);
      });
      if (r != null) {
        push(r);
        n++;
      }
    }
    return n;
  }

  /** 这个客户暂停中的已审核待放款提现（后台解除冻结以后逐笔处理） */
  @Override
  public List<WithdrawAdminApi.Held> held(long userId) {
    return mapper.heldOf(userId).stream().map(w -> new WithdrawAdminApi.Held(w.id, w.publicNo, w.amount, w.network, w.address, w.heldAt, w.heldReason,
        w.sendAttempts > 0)).toList();
  }

  /**
   * 后台选"继续放款"（4b 审查 🟡1）：客户还冻结着时不行（{@code WITHDRAW_FROZEN}）；去掉暂停，提交以后马上交钱包提供方（之后定时任务也会交）
   */
  @Override
  public void releaseHeld(long withdrawalId, long adminId, String reason) {
    WithdrawalRow r = tx.execute(st -> {
      WithdrawalRow w = heldRow(withdrawalId);
      String frozen = frozenReason(w.userId);
      if (frozen != null) throw new BizException("WITHDRAW_FROZEN", "客户已冻结提现，请先核对（" + frozen + "）");
      mapper.release(w.id);
      mapper.insertEvent(w.id, w.status, w.status, "admin:" + adminId, "解除暂停，继续放款：" + reason);
      return w;
    });
    send(r.id);
  }

  /**
   * 后台选"取消并退回"（4b 审查 🟡1）：只允许从没交过钱包提供方的（send_attempts = 0；交过的可能已在付款，退回会重复出金，
   * 报 {@code WITHDRAW_SENT_BEFORE}，要先在钱包提供方核对）。交出去之前都要先认领（{@link #send}），暂停后不能再认领，所以这里锁住检查就够了
   */
  @Override
  public void cancelHeld(long withdrawalId, long adminId, String reason) {
    WithdrawalRow r = tx.execute(st -> {
      WithdrawalRow w = heldRow(withdrawalId);
      if (w.sendAttempts > 0) throw new BizException("WITHDRAW_SENT_BEFORE", "这笔已经交过钱包提供方（可能已在付款），不能直接退回，请先在钱包提供方核对");
      move(w, "CANCELLED", "admin:" + adminId, "暂停中取消并退回：" + reason);
      refund(w);
      return w;
    });
    notify(r);
  }

  private WithdrawalRow heldRow(long id) {
    WithdrawalRow w = mapper.lock(id);
    if (w == null) throw new BizException("NOT_FOUND", "提现不存在");
    if (!"APPROVED".equals(w.status) || w.heldAt == null) throw new BizException("WITHDRAW_NOT_HELD", "这笔提现不在暂停中");
    return w;
  }

  /** 退回：提现冻结 → 余额 */
  private void refund(WithdrawalRow w) {
    ledger.apply(new LedgerCommand(w.userId, Bucket.WITHDRAW_FROZEN, w.amount.negate(), LedgerEntryType.WITHDRAW_UNFREEZE, BIZ_WITHDRAW,
        String.valueOf(w.id), "提现退回"));
    ledger.apply(new LedgerCommand(w.userId, Bucket.BALANCE, w.amount, LedgerEntryType.WITHDRAW_UNFREEZE, BIZ_WITHDRAW, String.valueOf(w.id), "提现退回"));
  }

  /**
   * 推给客户 {"type":"withdraw","status":"cooling","amount":"100"} 和 account 事件，客户端收到就重新拉提现记录和余额。<b>要在状态提交以后推</b>：在调用方的事务里（钱包提供方的回调
   * → payoutUpdate，"回调比提交的返回先到"时改成放款中）就等事务提交再推，不然客户端拉到的还是旧状态，之后 send() 看到状态已经变了
   * 不再推，客户端就一直停在"已审核待放款"（PR #29 第一次 CI 端到端偶发失败的原因，PayoutNotifyCommitTest 复现）。
   * 不在事务里（各处 tx.execute 返回之后）照旧马上推。和 DepositService 的推送同一个做法
   */
  private void notify(WithdrawalRow w) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("type", "withdraw");
    e.put("status", apiStatus(w.status));
    e.put("amount", w.amount.stripTrailingZeros().toPlainString());
    long userId = w.userId;
    Runnable push = () -> push(userId, e);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          push.run();
        }
      });
    } else {
      push.run();
    }
  }

  /** 马上推 withdraw 和 account 事件（见 {@link #notify}） */
  private void push(WithdrawalRow w) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("type", "withdraw");
    e.put("status", apiStatus(w.status));
    e.put("amount", w.amount.stripTrailingZeros().toPlainString());
    push(w.userId, e);
  }

  private void push(long userId, Map<String, Object> e) {
    events.send(userId, e);
    events.send(userId, Map.of("type", "account"));
  }

  /** 数据库状态 → 客户端状态名（WithdrawStatus） */
  static String apiStatus(String s) {
    return switch (s) {
      case "FAILED_REFUNDED" -> "failedRefunded";
      default -> s.toLowerCase();
    };
  }

  /** 当前的提现参数（后台可改，S16a） */
  private WithdrawProperties props() {
    return limits.current();
  }
}
