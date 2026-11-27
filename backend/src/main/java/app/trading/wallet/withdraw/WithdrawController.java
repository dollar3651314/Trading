package app.trading.wallet.withdraw;

import app.trading.account.api.AccountApi;
import app.trading.common.ratelimit.UserWriteLimited;
import app.trading.common.web.CurrentUser;
import app.trading.trading.api.FundsApi;
import app.trading.payment.api.PaymentApi;
import app.trading.wallet.Chain;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 客户的提现接口（S13a），字段和客户端 WhitelistAddress、Withdrawal、WithdrawInfo 一致。 */
@RestController
class WithdrawController {

  record WhitelistView(String id, String network, String address, String label, String createdAt, String activeAt) {}

  /** pin：TG 客户要输提现密码（随机键盘，第 4 批） */
  record AddWhitelist(String network, String address, String label, app.trading.account.api.WithdrawPinApi.PinInput pin) {}

  /**
   * 一笔提现。第 1 批加：payoutNo 钱包提供方付款编号；payoutStatus 两道审的第二道走到哪了——awaitingRelease 等待放款审核 / paying 放款中
   * （提供方那边"未知"的客户也看到放款中，S-2-6）；steps 每一步进入的时间（到秒，资金记录页展开看）。
   */
  record WithdrawalView(String id, String amount, String fee, String network, String address, String status, String createdAt, String coolingEndsAt,
      String delayEndsAt, String txHash, String rejectReason, String payoutNo, String payoutStatus, List<Step> steps) {}

  record Step(String status, String at) {}

  record SubmitWithdraw(String whitelistId, String amount, app.trading.account.api.WithdrawPinApi.PinInput pin) {}

  record Page<T>(List<T> items, long total, int page, int pageSize) {}

  private final WhitelistService whitelist;
  private final WithdrawService withdraws;
  private final WithdrawMapper mapper;
  private final WithdrawLimits limits;
  private final AccountApi account;
  private final FundsApi funds;
  private final PaymentApi payments;
  private final TelegramWithdrawGate tgGate;
  private final app.trading.account.api.WithdrawPinApi pins;
  private final app.trading.common.config.SystemConfig config;

  WithdrawController(WhitelistService whitelist, WithdrawService withdraws, WithdrawMapper mapper, WithdrawLimits limits, AccountApi account,
      FundsApi funds, PaymentApi payments, TelegramWithdrawGate tgGate, app.trading.account.api.WithdrawPinApi pins,
      app.trading.common.config.SystemConfig config) {
    this.tgGate = tgGate;
    this.pins = pins;
    this.config = config;
    this.payments = payments;
    this.whitelist = whitelist;
    this.withdraws = withdraws;
    this.mapper = mapper;
    this.limits = limits;
    this.account = account;
    this.funds = funds;
  }

  private boolean isOpen() {
    return "true".equalsIgnoreCase(config.text(app.trading.common.config.ConfigKey.TELEGRAM_WITHDRAW_ENABLED, "false").strip());
  }

  @GetMapping("/api/wallet/whitelist")
  List<WhitelistView> whitelist() {
    return whitelist.list(CurrentUser.id()).stream().map(WithdrawController::view).toList();
  }

  @UserWriteLimited
  @PostMapping("/api/wallet/whitelist")
  WhitelistView addWhitelist(@RequestBody AddWhitelist req) {
    return view(whitelist.add(CurrentUser.id(), req.network(), req.address(), req.label(), req.pin()));
  }

  @UserWriteLimited
  @DeleteMapping("/api/wallet/whitelist/{id}")
  void removeWhitelist(@PathVariable long id) {
    whitelist.remove(CurrentUser.id(), id);
  }

  /** 提现页需要的参数：KYC 状态、可提金额、今天已用、限额、手续费等 */
  @GetMapping("/api/wallet/withdraw-info")
  Map<String, Object> info() {
    long uid = CurrentUser.id();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("kycStatus", account.kycStatus(uid).name().toLowerCase());
    m.put("available", money(funds.withdrawable(uid).max(BigDecimal.ZERO)));
    m.put("usedToday", money(withdraws.usedToday(uid)));
    m.put("minAmount", money(props().minAmount()));
    m.put("maxPerTx", money(props().maxPerTx()));
    m.put("dailyLimit", money(props().dailyLimit()));
    m.put("largeAmount", money(props().largeAmount()));
    m.put("largeDelayHours", props().largeDelay().toHours());
    m.put("coolingHours", props().cooling().toHours());
    Map<String, String> fees = new LinkedHashMap<>();
    for (Chain c : Chain.values()) fees.put(c.name(), money(props().fee(c)));
    m.put("fees", fees);
    m.put("maxWhitelist", props().maxWhitelist());
    // TG 客户（第 4 批）：按 TG 规则——每天额度（网页和机器人合并）、要提现密码、什么时候以后才能提
    if (tgGate.isTg(uid)) {
      var c = tgGate.context(uid);
      var pin = pins.status(uid);
      LocalDateTime hold = tgGate.holdUntil(uid, withdraws.now());
      Map<String, Object> tg = new LinkedHashMap<>();
      tg.put("dailyLimit", money(c.dailyQuota()));
      tg.put("hasEmail", c.hasEmail());
      tg.put("pinSet", pin.set());
      tg.put("pinLockedUntil", pin.lockedUntil() == null ? null : pin.lockedUntil().toInstant(ZoneOffset.UTC).toString());
      tg.put("withdrawFrozen", pin.withdrawFrozen());
      tg.put("holdUntil", hold == null ? null : hold.toInstant(ZoneOffset.UTC).toString());
      tg.put("enabled", isOpen());
      m.put("tg", tg);
    }
    return m;
  }

  @UserWriteLimited
  @PostMapping("/api/wallet/withdrawals")
  WithdrawalView submit(@RequestBody SubmitWithdraw req) {
    return views(List.of(withdraws.submit(CurrentUser.id(), req.whitelistId(), req.amount(), req.pin(), CurrentUser.botSession() ? "BOT" : "WEB"))).getFirst();
  }

  @GetMapping("/api/wallet/withdrawals")
  Page<WithdrawalView> list(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
    long uid = CurrentUser.id();
    int p = Math.max(page, 1);
    int size = Math.min(Math.max(pageSize, 1), 100);
    return new Page<>(views(mapper.byUser(uid, (p - 1) * size, size)), mapper.countByUser(uid), p, size);
  }

  /** 路径里的 id 是对外编号（雪花 ID，字符串）；不是自己的提现当成不存在 */
  @PostMapping("/api/wallet/withdrawals/{id}/cancel")
  WithdrawalView cancel(@PathVariable long id) {
    long uid = CurrentUser.id();
    Long internal = mapper.idByNo(uid, id);
    return views(List.of(withdraws.cancel(uid, internal == null ? -1 : internal))).getFirst();
  }

  static WhitelistView view(WhitelistRow r) {
    return new WhitelistView(String.valueOf(r.id), r.network, r.address, r.label, iso(r.createdAt), iso(r.activeAt));
  }

  /** 一批提现连同付款编号、付款状态、每一步的时间一起查出来（每页两次查询） */
  List<WithdrawalView> views(List<WithdrawalRow> rows) {
    List<Long> ids = rows.stream().map(r -> r.id).toList();
    Map<Long, PaymentApi.PayoutInfo> pay = new java.util.HashMap<>();
    for (PaymentApi.PayoutInfo i : payments.payoutInfos(ids)) pay.put(i.withdrawalId(), i);
    Map<Long, List<Step>> steps = new java.util.HashMap<>();
    if (!ids.isEmpty()) {
      for (WithdrawMapper.EventRow e : mapper.eventsOf(ids)) {
        steps.computeIfAbsent(e.withdrawalId(), k -> new java.util.ArrayList<>()).add(new Step(WithdrawService.apiStatus(e.toStatus()), iso(e.at())));
      }
    }
    return rows.stream().map(r -> view(r, pay.get(r.id), steps.getOrDefault(r.id, List.of()))).toList();
  }

  static WithdrawalView view(WithdrawalRow r, PaymentApi.PayoutInfo pay, List<Step> steps) {
    return new WithdrawalView(String.valueOf(r.publicNo), money(r.amount), money(r.fee), r.network, r.address, WithdrawService.apiStatus(r.status), iso(r.createdAt),
        iso(r.coolingEndsAt), iso(r.delayEndsAt), r.txHash, r.rejectReason, pay == null ? null : pay.providerRef(), payoutStatus(r.status, pay), steps);
  }

  /** 客户看到的第二道审核：已交提供方、还没放款 → 等待放款审核；提供方批准、广播、"未知" → 放款中；其余为空 */
  static String payoutStatus(String status, PaymentApi.PayoutInfo pay) {
    if (pay == null || !"BROADCASTING".equals(status)) return null;
    return switch (pay.state()) {
      case SUBMITTING, PENDING -> "awaitingRelease";
      case APPROVED, BROADCAST, UNKNOWN -> "paying";
      default -> null;
    };
  }

  static String money(BigDecimal v) {
    return v.setScale(2, java.math.RoundingMode.DOWN).toPlainString();
  }

  static String iso(LocalDateTime t) {
    return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
  }

  /** 当前的提现参数（后台可改，S16a） */
  private WithdrawProperties props() {
    return limits.current();
  }
}
