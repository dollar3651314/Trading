package app.trading.admin.ops;

import app.trading.account.api.AccountApi;
import app.trading.account.api.CustomerAdminApi;
import app.trading.admin.security.AdminContext;
import app.trading.admin.security.AdminNames;
import app.trading.admin.security.Audit;
import app.trading.common.error.BizException;
import app.trading.common.task.TaskItem;
import app.trading.common.task.TaskService;
import app.trading.common.task.TaskStatus;
import app.trading.trading.api.TradingAdminApi;
import app.trading.payment.api.PaymentApi;
import app.trading.wallet.api.HotWalletApi;
import app.trading.wallet.api.WalletAdminApi;
import app.trading.wallet.api.WalletReconApi;
import app.trading.wallet.api.WithdrawAdminApi;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台"资金"和"KYC 审核"（S16b）：入金记录、出金审核（审核通过、驳回、紧急取消，AC-21）、对账、任务；KYC 列表、审核（AC-05）。
 * 业务都在各模块里（{@code WithdrawAdminApi}、{@code WalletReconApi}、{@code AccountApi}），这里只做查询组装、权限和审计。
 */
@Service
class FundsOps {

  /** 交易哈希：以太坊体系 0x + 64 位，TRON 64 位十六进制 */
  private static final Pattern TX_HASH = Pattern.compile("(0x)?[0-9a-fA-F]{64}");

  /** 字段和原型 Deposit 一致；第 1 批加 provider 提供方、providerRef 提供方到账编号（原型 P5） */
  record DepositView(String id, long customerId, String customerName, String email, String chain, String txHash, String amount, int confirmations,
      int requiredConfirmations, String status, String detectedAt, String creditedAt, String provider, String providerRef) {}

  /** 字段和原型 Withdrawal 一致；autoReview 是最近一次自动审批判定的摘要（v2），没有判定记录时为空。自动通过的 reviewer 为空 */
  /**
   * 第 1 批加（原型 P4）：provider、payoutNo 提供方付款编号、payoutStatus 付款状态（submitting 提交中 / pending 待放款审核 / approved、broadcast 放款中 /
   * completed 成功 / failed、rejected、cancelled / unknown 未知，标红、不能退款）、payoutReason、statusAt 进入当前状态的时间（到秒）
   */
  record WithdrawalView(String id, long customerId, String customerName, String email, String amount, String fee, String chain, String address,
      boolean firstUse, String whitelistAddedAt, String status, Long delayRemainingMinutes, String submittedAt, String reviewer, String reason,
      String txHash, AutoApproveOps.ReviewView autoReview, String provider, String payoutNo, String payoutStatus, String payoutReason, String statusAt,
      String ruleType, String source, boolean firstCheck, boolean firstChecked) {}

  record Recent(String time, String chain, String amount) {}

  /** 字段和原型 WithdrawalDetail 一致；autoReviewDetail 是完整的自动审批判定记录（v2），没有时为空 */
  record WithdrawalDetail(WithdrawalView withdrawal, String kycStatus, long registeredDays, String totalDeposit, String balance, int openPositions,
      List<Recent> recentDeposits, List<Recent> recentWithdrawals, AutoApproveOps.ReviewDetailView autoReviewDetail) {}

  record Bucket(long count, String amount) {}

  /** 字段和原型 WithdrawalSummary 一致 */
  record Summary(Bucket pending, Bucket delayed, Bucket cooling, Bucket completedToday) {}

  /** 对账问题（字段按后端的问题类型，见后台方案 v1.2 第 7 节） */
  record ReconView(String id, String type, String ref, Long customerId, String customerName, String expected, String actual, String diff, String detail,
      String status, String note, String handler, String detectedAt, String resolvedAt) {}

  /**
   * 热钱包余额（对账页上方，2026-10-17）。status：ok 正常、low 低于下限（在告警）、off 下限为 0 不告警、unknown 还没查到余额。
   * threshold 是后台系统配置里的下限（{@code hotWallet.*}）。error 是最近一次查询失败的原因（不含节点地址）
   */
  /** 最近一次对账（对账页顶部）：checked 和提供方逐笔核对的笔数；providerOk = false 时提供方不通、按单号核对没跑成 */
  record ReconStatus(String lastRunAt, int checked, boolean providerOk, String providerError, long open) {}

  record HotWalletView(String chain, String asset, boolean gas, String address, String balance, String threshold, String status, String checkedAt,
      String error) {}

  /** 字段和原型 TaskItem 一致 */
  record TaskView(String id, String type, String bizRef, String status, int attempts, int maxAttempts, String nextRetryAt, String lastError,
      String createdAt, String updatedAt) {}

  /** 字段和原型 KycApplication 一致 */
  record KycView(String id, long customerId, String name, String email, String nationality, String birthDate, String docType, String docNumber,
      String status, String submittedAt, String reviewer, String reviewedAt, String rejectReason) {}

  private final WalletAdminApi wallet;
  private final WithdrawAdminApi withdraws;
  private final WalletReconApi recon;
  private final HotWalletApi hot;
  private final CustomerAdminApi customers;
  private final AccountApi accounts;
  private final TradingAdminApi trading;
  private final TaskService tasks;
  private final AdminNames names;
  private final Audit audit;
  private final AutoApproveOps autoApprove;
  private final Clock clock;
  private final PaymentApi payments;

  FundsOps(WalletAdminApi wallet, WithdrawAdminApi withdraws, WalletReconApi recon, HotWalletApi hot, CustomerAdminApi customers, AccountApi accounts,
      TradingAdminApi trading, TaskService tasks, AdminNames names, Audit audit, AutoApproveOps autoApprove, Clock clock, PaymentApi payments) {
    this.payments = payments;
    this.wallet = wallet;
    this.withdraws = withdraws;
    this.recon = recon;
    this.hot = hot;
    this.customers = customers;
    this.accounts = accounts;
    this.trading = trading;
    this.tasks = tasks;
    this.names = names;
    this.audit = audit;
    this.autoApprove = autoApprove;
    this.clock = clock;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }

  /** 按客户搜索：关键词为空返回 null（不筛选），否则返回匹配的客户号（可能为空） */
  private List<Long> userFilter(String keyword) {
    String kw = Ops.blank(keyword);
    return kw == null ? null : customers.search(kw, 500);
  }

  // ---------- 入金 ----------

  /** 原型状态 → 数据库状态：confirming 待确认、credited 已入账、ignored 已作废或低于最小金额、reversed 已冲正 */
  static List<String> depositStatuses(String api) {
    if (api == null || api.isBlank()) return null;
    return switch (api) {
      case "confirming" -> List.of("PENDING");
      case "credited" -> List.of("CREDITED");
      case "ignored" -> List.of("DROPPED", "BELOW_MIN");
      case "reversed" -> List.of("REVERSED");
      default -> List.of("-");
    };
  }

  static String depositStatus(String db) {
    return switch (db) {
      case "PENDING" -> "confirming";
      case "DROPPED", "BELOW_MIN" -> "ignored";
      default -> db.toLowerCase();
    };
  }

  @Transactional(readOnly = true)
  Ops.Page<DepositView> deposits(String chain, String status, String keyword, int page, int pageSize) {
    return deposits(chain, status, keyword, null, null, null, page, pageSize);
  }

  /** providerRef：按提供方到账编号查；from、to：到账时间（到秒，"yyyy-MM-dd HH:mm:ss" 或 ISO，UTC） */
  @Transactional(readOnly = true)
  Ops.Page<DepositView> deposits(String chain, String status, String keyword, String providerRef, String from, String to, int page, int pageSize) {
    Ops.Slice s = Ops.slice(page, pageSize);
    String kw = Ops.blank(keyword);
    String tx = kw != null && TX_HASH.matcher(kw).matches() ? kw : null;
    List<Long> users = tx == null ? userFilter(kw) : null;
    var p = wallet.deposits(Ops.blank(chain), depositStatuses(status), tx, users, tx == null ? Ops.number(kw) : null, Ops.blank(providerRef), when(from), when(to),
        s.offset(), s.size());
    var cs = customers.customers(p.items().stream().map(WalletAdminApi.Deposit::userId).toList());
    return new Ops.Page<>(p.items().stream().map(d -> {
      var c = cs.get(d.userId());
      return new DepositView(String.valueOf(d.no()), d.userId(), c == null ? null : c.name(), c == null ? null : c.email(), d.chain(), d.txHash(),
          Ops.money(d.amount()), d.confirmations(), d.requiredConfirmations(), depositStatus(d.status()), Ops.time(d.detectedAt()), Ops.time(d.creditedAt()),
          d.provider(), d.providerRef());
    }).toList(), p.total(), s.page(), s.size());
  }

  /** 时间参数：空 → 不筛选；格式不对 → 400 */
  static LocalDateTime when(String v) {
    String t = Ops.blank(v);
    if (t == null) return null;
    try {
      return t.contains("T") ? java.time.OffsetDateTime.parse(t.endsWith("Z") || t.matches(".*[+-]\\d\\d:\\d\\d$") ? t : t + "Z").withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime()
          : LocalDateTime.parse(t.replace(' ', 'T'));
    } catch (java.time.format.DateTimeParseException e) {
      throw new app.trading.common.error.ValidationException(List.of(new app.trading.common.error.ValidationException.Field("time", "invalid")));
    }
  }

  // ---------- 出金 ----------

  /** 原型状态 → 数据库状态。open = 冷静期 + 待审核 + 延迟期；broadcasting 包含"已审核待上链" */
  static List<String> withdrawStatuses(String api) {
    if (api == null || api.isBlank()) return null;
    return switch (api) {
      case "open" -> List.of("COOLING", "REVIEWING", "DELAYED");
      case "pending" -> List.of("REVIEWING");
      case "broadcasting" -> List.of("APPROVED", "BROADCASTING");
      case "failed" -> List.of("FAILED_REFUNDED");
      default -> List.of(api.toUpperCase());
    };
  }

  static String withdrawStatus(String db) {
    return switch (db) {
      case "REVIEWING" -> "pending";
      case "APPROVED" -> "broadcasting";
      case "FAILED_REFUNDED" -> "failed";
      default -> db.toLowerCase();
    };
  }

  private List<WithdrawalView> views(List<WalletAdminApi.Withdrawal> ws) {
    var cs = customers.customers(ws.stream().map(WalletAdminApi.Withdrawal::userId).toList());
    Map<Long, String> reviewers = names.of(ws.stream().map(WalletAdminApi.Withdrawal::reviewerId).filter(Objects::nonNull).toList());
    Map<Long, AutoApproveOps.ReviewView> reviews = autoApprove.summaries(ws.stream().map(WalletAdminApi.Withdrawal::id).toList());
    Map<Long, PaymentApi.PayoutInfo> pays = new java.util.HashMap<>();
    for (PaymentApi.PayoutInfo i : payments.payoutInfos(ws.stream().map(WalletAdminApi.Withdrawal::id).toList())) pays.put(i.withdrawalId(), i);
    LocalDateTime now = now();
    return ws.stream().map(w -> {
      PaymentApi.PayoutInfo pay = pays.get(w.id());
      var c = cs.get(w.userId());
      Long remaining = "DELAYED".equals(w.status()) && w.delayEndsAt() != null ? Math.max(0, Duration.between(now, w.delayEndsAt()).toMinutes()) : null;
      return new WithdrawalView(String.valueOf(w.no()), w.userId(), c == null ? null : c.name(), c == null ? null : c.email(), Ops.money(w.amount()),
          Ops.money(w.fee()), w.chain(), w.address(), w.firstUse(), Ops.time(w.whitelistAddedAt()), withdrawStatus(w.status()), remaining,
          Ops.time(w.submittedAt()), w.reviewerId() == null ? null : reviewers.get(w.reviewerId()), w.rejectReason(), w.txHash(), reviews.get(w.id()),
          pay == null ? null : pay.provider(), pay == null ? null : pay.providerRef(), pay == null ? null : pay.state().name().toLowerCase(),
          pay == null ? null : pay.reason(), Ops.time(w.statusAt() == null ? w.submittedAt() : w.statusAt()),
          w.ruleType() == null ? "web" : w.ruleType().toLowerCase(), w.source() == null ? "web" : w.source().toLowerCase(), w.firstCheck(), w.firstCheckedBy() != null);
    }).toList();
  }

  @Transactional(readOnly = true)
  Ops.Page<WithdrawalView> withdrawals(String status, String chain, String minAmount, String maxAmount, String keyword, String autoReview, int page,
      int pageSize) {
    Ops.Slice s = Ops.slice(page, pageSize);
    String auto = AutoApproveOps.filter(autoReview);
    var p = wallet.withdrawals(withdrawStatuses(status), Ops.blank(chain), decimal(minAmount), decimal(maxAmount), userFilter(keyword),
        Ops.number(keyword), auto, s.offset(), s.size());
    return new Ops.Page<>(views(p.items()), p.total(), s.page(), s.size());
  }

  private static BigDecimal decimal(String v) {
    try {
      return v == null || v.isBlank() ? null : new BigDecimal(v.strip());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  @Transactional(readOnly = true)
  Summary summary() {
    var s = wallet.summary(now().toLocalDate().atStartOfDay());
    return new Summary(bucket(s.reviewing()), bucket(s.delayed()), bucket(s.cooling()), bucket(s.completedToday()));
  }

  private static Bucket bucket(WalletAdminApi.Bucket b) {
    return new Bucket(b.count(), Ops.money(b.amount()));
  }

  private WalletAdminApi.Withdrawal require(long id) {
    return wallet.withdrawal(id).orElseThrow(() -> new BizException("NOT_FOUND", "提现不存在"));
  }

  /** 后台接口路径里是对外编号（雪花 ID）→ 内部 id */
  private long internal(long no) {
    return wallet.withdrawalIdByNo(no).orElseThrow(() -> new BizException("NOT_FOUND", "提现不存在"));
  }

  @Transactional(readOnly = true)
  WithdrawalDetail withdrawal(long no) {
    WalletAdminApi.Withdrawal w = require(internal(no));
    WithdrawalView v = views(List.of(w)).getFirst();
    var c = customers.customer(w.userId()).map(CustomerAdminApi.CustomerDetail::customer).orElse(null);
    var f = wallet.customerFunds(w.userId(), 5);
    long days = c == null || c.registeredAt() == null ? 0 : Duration.between(c.registeredAt(), now()).toDays();
    return new WithdrawalDetail(v, c == null ? null : c.kycStatus().toLowerCase(), days, Ops.money(f.totalDeposit()),
        Ops.money(trading.balances(List.of(w.userId())).getOrDefault(w.userId(), BigDecimal.ZERO)),
        trading.openPositionCounts(List.of(w.userId())).getOrDefault(w.userId(), 0),
        f.recentDeposits().stream().map(d -> new Recent(Ops.time(d.creditedAt() == null ? d.detectedAt() : d.creditedAt()), d.chain(), Ops.money(d.amount()))).toList(),
        f.recentWithdrawals().stream().map(x -> new Recent(Ops.time(x.submittedAt()), x.chain(), Ops.money(x.amount()))).toList(),
        autoApprove.detail(w.id()));
  }

  WithdrawalView approve(AdminContext actor, long no) {
    return approve(actor, no, false);
  }

  /** firstChecked：首笔人工核实已做（第 4 批：没邮箱 TG 客户的首笔必须确认），写进审计 */
  WithdrawalView approve(AdminContext actor, long no, boolean firstChecked) {
    long id = internal(no);
    audit.run("withdrawal.approve", String.valueOf(no), false, null, () -> {
      withdraws.approve(id, actor.adminId(), firstChecked);
      return Audit.Change.of(null, Map.of("status", "pending"), Map.of("status", withdrawStatus(require(id).status())));
    });
    return views(List.of(require(id))).getFirst();
  }

  WithdrawalView reject(AdminContext actor, long no, String reason) {
    String r = Audit.requireReason(reason);
    long id = internal(no);
    audit.run("withdrawal.reject", String.valueOf(no), false, r, () -> {
      withdraws.reject(id, actor.adminId(), r);
      return Audit.Change.of(null, Map.of("status", "pending"), Map.of("status", "rejected"));
    });
    return views(List.of(require(id))).getFirst();
  }

  /** 付款状态"未知"或想马上看结果：向钱包提供方查一次（查到成功 → 已完成；失败、驳回 → 退回；还是查不到 → 保持不退款） */
  WithdrawalView requery(AdminContext actor, long no) {
    long id = internal(no);
    audit.run("withdrawal.requery", String.valueOf(no), false, null, () -> {
      String before = payments.payoutInfo(id).map(i -> i.state().name().toLowerCase()).orElse(null);
      String after;
      try {
        after = payments.refreshPayout(id).name().toLowerCase();
      } catch (PaymentApi.Unavailable e) {
        throw new BizException("WALLET_UNAVAILABLE", "钱包提供方暂时不通，请稍后再查");
      }
      return Audit.Change.of(null, Map.of("payoutStatus", String.valueOf(before)), Map.of("payoutStatus", after));
    });
    return views(List.of(require(id))).getFirst();
  }

  /** 大额延迟期内紧急取消（高危，AC-21） */
  WithdrawalView emergencyCancel(AdminContext actor, long no, String reason) {
    String r = Audit.requireReason(reason);
    long id = internal(no);
    audit.run("withdrawal.emergency_cancel", String.valueOf(no), true, r, () -> {
      withdraws.emergencyCancel(id, actor.adminId(), r);
      return Audit.Change.of(null, Map.of("status", "delayed"), Map.of("status", "cancelled"));
    });
    return views(List.of(require(id))).getFirst();
  }

  // ---------- 对账 ----------

  @Transactional(readOnly = true)
  Ops.Page<ReconView> recon(String status, int page, int pageSize) {
    Ops.Slice s = Ops.slice(page, pageSize);
    String st = Ops.blank(status) == null ? null : status.strip().toUpperCase();
    List<WalletReconApi.Issue> items = recon.issues(st, s.offset(), s.size());
    var cs = customers.customers(items.stream().map(WalletReconApi.Issue::userId).filter(Objects::nonNull).toList());
    Map<Long, String> handlers = names.of(items.stream().map(WalletReconApi.Issue::resolvedBy).filter(Objects::nonNull).toList());
    List<ReconView> out = new ArrayList<>();
    for (WalletReconApi.Issue i : items) {
      var c = i.userId() == null ? null : cs.get(i.userId());
      String diff = i.expected() == null || i.actual() == null ? null : Ops.money(i.actual().subtract(i.expected()));
      out.add(new ReconView(String.valueOf(i.id()), i.kind().toLowerCase(), i.refId(), i.userId(), c == null ? null : c.name(), Ops.money(i.expected()),
          Ops.money(i.actual()), diff, i.detail(), i.status().toLowerCase(), i.note(), i.resolvedBy() == null ? null : handlers.get(i.resolvedBy()),
          Ops.time(i.createdAt()), Ops.time(i.resolvedAt())));
    }
    return new Ops.Page<>(out, recon.countIssues(st), s.page(), s.size());
  }

  @Transactional(readOnly = true)
  ReconStatus reconStatus() {
    var r = recon.lastRun().orElse(null);
    long open = recon.countIssues("OPEN");
    return r == null ? new ReconStatus(null, 0, true, null, open) : new ReconStatus(Ops.time(r.at()), r.checked(), r.providerOk(), r.providerError(), open);
  }

  /** 立即对账（对账页按钮）：返回这次新发现的问题数 */
  int runRecon(AdminContext actor) {
    return audit.run("recon.run", "now", false, null, () -> {
      int fresh = recon.run();
      return Audit.Change.of(fresh, Map.of(), Map.of("fresh", String.valueOf(fresh)));
    });
  }

  List<HotWalletView> hotWallets() {
    return hot.hotBalances().stream().map(b -> new HotWalletView(b.chain(), b.asset(), b.gas(), b.address(), Ops.money(b.balance()), Ops.money(b.threshold()),
        b.threshold().signum() <= 0 ? "off" : b.balance() == null ? "unknown" : b.low() ? "low" : "ok",
        b.checkedAt() == null ? null : b.checkedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(), b.error())).toList();
  }

  void resolveRecon(AdminContext actor, long id, String note) {
    String n = Audit.requireReason(note);
    audit.run("recon.resolve", String.valueOf(id), false, n, () -> {
      recon.resolve(id, actor.adminId(), n);
      return Audit.Change.of(null, Map.of("status", "open"), Map.of("status", "resolved"));
    });
  }

  // ---------- 任务 ----------

  static String taskType(String db) {
    return switch (db) {
      case "MAIL_SEND" -> "email";
      case "PUSH_SEND" -> "push";
      default -> db.toLowerCase();
    };
  }

  static String taskDbType(String api) {
    if (api == null || api.isBlank()) return null;
    return switch (api) {
      case "email" -> "MAIL_SEND";
      case "push" -> "PUSH_SEND";
      default -> api.toUpperCase();
    };
  }

  static TaskStatus taskDbStatus(String api) {
    if (api == null || api.isBlank()) return null;
    return switch (api) {
      case "pending" -> TaskStatus.PENDING;
      case "processing" -> TaskStatus.RUNNING;
      case "success" -> TaskStatus.SUCCEEDED;
      case "failed" -> TaskStatus.FAILED;
      default -> throw new app.trading.common.error.ValidationException(
          List.of(new app.trading.common.error.ValidationException.Field("status", "invalid")));
    };
  }

  static String taskStatus(TaskStatus s) {
    return switch (s) {
      case PENDING -> "pending";
      case RUNNING -> "processing";
      case SUCCEEDED -> "success";
      case FAILED -> "failed";
    };
  }

  @Transactional(readOnly = true)
  Ops.Page<TaskView> tasks(String type, String status, int page, int pageSize) {
    Ops.Slice s = Ops.slice(page, pageSize);
    String t = taskDbType(type);
    TaskStatus st = taskDbStatus(status);
    return new Ops.Page<>(tasks.list(t, st, s.offset(), s.size()).stream().map(FundsOps::task).toList(), tasks.count(t, st), s.page(), s.size());
  }

  private static TaskView task(TaskItem i) {
    return new TaskView(String.valueOf(i.id()), taskType(i.taskType()), i.bizRef(), taskStatus(i.status()), i.attempts(), i.maxAttempts(),
        i.status() == TaskStatus.PENDING ? Ops.time(i.nextRunAt()) : null, i.lastError(), Ops.time(i.createdAt()), Ops.time(i.updatedAt()));
  }

  /** 手动重试：只有失败的能重试 */
  void retryTask(long id) {
    audit.run("task.retry", String.valueOf(id), false, null, () -> {
      if (tasks.find(id) == null) throw new BizException("NOT_FOUND", "任务不存在");
      if (!tasks.retry(id)) throw new BizException("TASK_NOT_FAILED", "只有失败的任务能重试");
      return Audit.Change.of(null, Map.of("status", "failed"), Map.of("status", "pending"));
    });
  }

  // ---------- KYC ----------

  private List<KycView> kycViews(List<CustomerAdminApi.KycApplication> ks) {
    Map<Long, String> reviewers = names.of(ks.stream().map(CustomerAdminApi.KycApplication::reviewerId).filter(Objects::nonNull).toList());
    return ks.stream().map(k -> new KycView(String.valueOf(k.id()), k.userId(), k.name(), k.email(), k.nationality(),
        k.birthDate() == null ? null : k.birthDate().toString(), CustomerOps.docType(k.docType()), k.docNumber(), k.status().toLowerCase(),
        Ops.time(k.submittedAt()), k.reviewerId() == null ? null : reviewers.get(k.reviewerId()), Ops.time(k.reviewedAt()), k.rejectReason())).toList();
  }

  @Transactional(readOnly = true)
  Ops.Page<KycView> kycs(String status, String keyword, int page, int pageSize) {
    Ops.Slice s = Ops.slice(page, pageSize);
    var p = customers.kycApplications(Ops.blank(status) == null ? null : status.strip().toUpperCase(), Ops.blank(keyword), s.offset(), s.size());
    return new Ops.Page<>(kycViews(p.items()), p.total(), s.page(), s.size());
  }

  @Transactional(readOnly = true)
  KycView kyc(long id) {
    return kycViews(List.of(customers.kycApplication(id).orElseThrow(() -> new BizException("NOT_FOUND", "申请不存在")))).getFirst();
  }

  CustomerAdminApi.Image kycImage(long id, String side) {
    return customers.kycImage(id, side).orElseThrow(() -> new BizException("NOT_FOUND", "没有这张照片"));
  }

  /** 审核：通过，或驳回（必须填原因，AC-05）；结果通过站内信和邮件通知客户 */
  KycView reviewKyc(AdminContext actor, long id, boolean approve, String reason) {
    String r = approve ? null : Audit.requireReason(reason);
    audit.run(approve ? "kyc.approve" : "kyc.reject", String.valueOf(id), false, r, () -> {
      if (approve) accounts.approveKyc(id, actor.adminId());
      else accounts.rejectKyc(id, actor.adminId(), r);
      return Audit.Change.of(null, Map.of("status", "pending"), Map.of("status", approve ? "approved" : "rejected"));
    });
    return kyc(id);
  }

  @Transactional(readOnly = true)
  Map<String, Long> badges(AdminContext ctx) {
    return Map.of("kyc", ctx.has(app.trading.admin.security.Permission.KYC_VIEW) ? customers.pendingKyc() : 0L, "withdrawals",
        ctx.has(app.trading.admin.security.Permission.WITHDRAWAL_VIEW) ? wallet.pendingReview() : 0L);
  }
}
