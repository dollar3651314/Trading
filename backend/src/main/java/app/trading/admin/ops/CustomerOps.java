package app.trading.admin.ops;

import app.trading.account.api.CustomerAdminApi;
import app.trading.admin.security.AdminAuthService;
import app.trading.admin.security.AdminContext;
import app.trading.admin.security.AdminNames;
import app.trading.admin.security.Audit;
import app.trading.common.config.ConfigKey;
import app.trading.common.health.AlertProperties;
import app.trading.notification.api.MailApi;
import app.trading.common.config.SystemConfig;
import app.trading.common.error.BizException;
import app.trading.common.error.ValidationException;
import app.trading.trading.api.Bucket;
import app.trading.trading.api.FundsApi;
import app.trading.trading.api.LedgerApi;
import app.trading.trading.api.LedgerCommand;
import app.trading.trading.api.LedgerEntryType;
import app.trading.trading.api.TradingAdminApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台"客户"（S16b）：列表、详情、冻结 / 解冻（AC-04）、调余额（后台方案 G5）；v2：编辑资料（E1）、详情显示最近登录 IP（E6）。
 * 调余额：单笔不超过 {@code adjust.singleLimit}、每个管理员当天（UTC）累计不超过 {@code adjust.dailyLimit}；
 * 扣减不能超过客户的可提金额（浮动盈利不算，和提现同一口径），所以余额不会被扣成负数；记账本 {@code ADMIN_ADJUST}。
 */
@Service
class CustomerOps {

  /** 字段和原型 CustomerSummary 一致；TG 机器人第 1 批：email 可空（纯 Telegram 客户），加 telegramId、telegramUsername（列表显示"TG @用户名"） */
  record Summary(long id, String name, String email, String nationality, String status, String kycStatus, String balance, int openPositions,
      String registeredAt, String lastLoginAt, String telegramId, String telegramUsername) {}

  record Kyc(String status, String docType, String docNumber, String reviewedAt, String rejectReason) {}

  record Account(String balance, String usedMargin, String available, String floatingPnl, String frozenWithdraw) {}

  /** 字段和原型 Position 一致；没有报价时 currentPrice、pnl、marginLevel 为空 */
  record PositionView(String id, long customerId, String customerName, String symbol, String side, String lots, String openPrice, String currentPrice,
      int leverage, String margin, String pnl, String marginLevel, String liquidationPrice, String stopLoss, String takeProfit, String openedAt) {}

  record LedgerView(String id, String type, String amount, String balanceAfter, String ref, String time) {}

  /** 字段和原型 CustomerDetail 一致；lastLoginIp、lastLoginIpAt 是 v2 E6 加的（最近一个登录会话的 IP 和时间） */
  record Detail(long id, String name, String email, String nationality, String status, String kycStatus, String balance, int openPositions,
      String registeredAt, String lastLoginAt, String lastLoginIp, String lastLoginIpAt, String firstName, String middleName, String lastName,
      String birthDate, String frozenReason, Kyc kyc, Account account, List<PositionView> positions, List<LedgerView> ledger,
      List<TradingOps.ClosedView> closedPositions, String telegramId, String telegramUsername, WithdrawSafety withdrawSafety,
      List<HeldView> heldWithdrawals) {}

  /**
   * 冻结时暂停的已审核待放款提现（第 4 批 4b 审查 🟡1：解除冻结后不自动继续，后台逐笔选"继续放款"或"取消并退回"）。
   * id 是对外编号；sentBefore：交过钱包提供方（可能已在付款），不能直接取消退回
   */
  record HeldView(String id, String amount, String chain, String address, String heldAt, String reason, boolean sentBefore) {}

  /**
   * 提现安全（TG 机器人第 4 批）：提现密码设了没有、锁到什么时候、提现有没有被冻结（来源：PIN_LOCKS 30 天锁 2 次 / WEB_REQUEST / BOT_SELF / DISPUTE / RECOVERY）
   */
  record WithdrawSafety(boolean pinSet, String pinLockedUntil, boolean withdrawFrozen, String frozenSource, String frozenAt, List<FreezeView> freezes) {}

  /** 4b 审查 🔴R2：每个来源一条（来源小写、原因、时间） */
  record FreezeView(String source, String reason, String at) {}

  /** 能在后台单独解除的冻结来源（小写），空 = 全部 */
  static final java.util.Set<String> SOURCES = java.util.Set.of("pin_locks", "web_request", "bot_self", "dispute", "recovery", "admin");

  /** 客户详情里最近的已平仓记录条数（部分平仓拆出来的记录标出原持仓编号） */
  static final int RECENT_CLOSED = 20;

  /**
   * 编辑资料的请求体（v2 E1）。email、status 只用来拒绝：编辑里不能改邮箱，状态只能用冻结 / 解冻改。
   */
  record ProfileBody(String firstName, String middleName, String lastName, String birthDate, String nationality, String email, String status,
      String reason) {}

  record Limits(String singleLimit, String dailyLimit, String usedToday) {}

  private final CustomerAdminApi customers;
  private final TradingAdminApi trading;
  private final LedgerApi ledger;
  private final FundsApi funds;
  private final SystemConfig config;
  private final AdminNames names;
  private final AdjustMapper adjusts;
  private final Audit audit;
  private final Clock clock;
  private final app.trading.account.api.WithdrawPinApi pins;
  private final AdminAuthService auth;
  private final app.trading.account.api.RecoveryApi recovery;
  private final app.trading.wallet.api.WithdrawAdminApi withdraws;
  private final MailApi mail;
  private final AlertProperties alerts;
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CustomerOps.class);
  /** 解除提现冻结时发给告警邮箱的邮件模板（迁移 V38） */
  static final String UNFREEZE_MAIL = "OPS_WITHDRAW_UNFREEZE";

  CustomerOps(CustomerAdminApi customers, TradingAdminApi trading, LedgerApi ledger, FundsApi funds, SystemConfig config, AdminNames names,
      AdjustMapper adjusts, Audit audit, Clock clock, app.trading.account.api.WithdrawPinApi pins, AdminAuthService auth, MailApi mail,
      AlertProperties alerts, app.trading.wallet.api.WithdrawAdminApi withdraws, app.trading.account.api.RecoveryApi recovery) {
    this.recovery = recovery;
    this.withdraws = withdraws;
    this.pins = pins;
    this.auth = auth;
    this.mail = mail;
    this.alerts = alerts;
    this.customers = customers;
    this.trading = trading;
    this.ledger = ledger;
    this.funds = funds;
    this.config = config;
    this.names = names;
    this.adjusts = adjusts;
    this.audit = audit;
    this.clock = clock;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }

  static String apiStatus(String s) {
    return s == null ? null : s.toLowerCase();
  }

  private static String dbStatus(String api) {
    return api == null || api.isBlank() ? null : api.strip().toUpperCase();
  }

  Summary summary(CustomerAdminApi.Customer c, BigDecimal balance, Integer positions) {
    return new Summary(c.id(), c.name(), c.email(), c.nationality(), apiStatus(c.status()), apiStatus(c.kycStatus()), Ops.money(balance == null ? BigDecimal.ZERO : balance),
        positions == null ? 0 : positions, Ops.time(c.registeredAt()), Ops.time(c.lastLoginAt()), tgId(c), c.telegramUsername());
  }

  /** TG 用户编号按字符串给前端（超过 JS 安全整数也不丢精度） */
  static String tgId(CustomerAdminApi.Customer c) {
    return c.telegramId() == null ? null : String.valueOf(c.telegramId());
  }

  @Transactional(readOnly = true)
  Ops.Page<Summary> list(String keyword, String status, String kycStatus, int page, int pageSize) {
    Ops.Slice s = Ops.slice(page, pageSize);
    var p = customers.customers(Ops.blank(keyword), dbStatus(status), dbStatus(kycStatus), s.offset(), s.size());
    List<Long> ids = p.items().stream().map(CustomerAdminApi.Customer::id).toList();
    Map<Long, BigDecimal> balances = trading.balances(ids);
    Map<Long, Integer> counts = trading.openPositionCounts(ids);
    return new Ops.Page<>(p.items().stream().map(c -> summary(c, balances.get(c.id()), counts.get(c.id()))).toList(), p.total(), s.page(), s.size());
  }

  /** 发通知时按客户搜索：最多 20 个 */
  @Transactional(readOnly = true)
  List<Summary> search(String keyword) {
    return list(keyword, null, null, 1, 20).items();
  }

  @Transactional(readOnly = true)
  Detail detail(long id) {
    CustomerAdminApi.CustomerDetail d = customers.customer(id).orElseThrow(() -> new BizException("NOT_FOUND", "客户不存在"));
    CustomerAdminApi.Customer c = d.customer();
    TradingAdminApi.Metrics m = trading.metrics(id);
    List<PositionView> positions = trading.openPositions(id).stream().map(p -> new PositionView(String.valueOf(p.no()), id, c.name(), p.symbol(),
        p.side().toLowerCase(), Ops.money(p.lots()), Ops.money(p.openPrice()), Ops.money(p.currentPrice()), p.leverage(), Ops.money(p.margin()),
        p.pnl() == null ? null : p.pnl().setScale(2, RoundingMode.HALF_UP).toPlainString(),
        p.marginLevel() == null ? null : p.marginLevel().multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.DOWN).toPlainString(),
        Ops.money(p.liquidationPrice()), Ops.money(p.stopLoss()), Ops.money(p.takeProfit()), Ops.time(p.openedAt()))).toList();
    List<LedgerView> lines = trading.balanceLedger(id, 50).stream().map(l -> new LedgerView(String.valueOf(l.id()), ledgerType(l.type()), Ops.money(l.amount()),
        Ops.money(l.balanceAfter()), l.bizType() + ":" + l.bizId(), Ops.time(l.time()))).toList();
    List<TradingOps.ClosedView> closed = trading.closedPositions(null, null, List.of(id), null, 0, RECENT_CLOSED).items().stream()
        .map(p -> TradingOps.closed(p, c)).toList();
    Kyc kyc = new Kyc(apiStatus(c.kycStatus()), docType(d.docType()), d.docNumber(), Ops.time(d.kycReviewedAt()), d.kycRejectReason());
    Account account = new Account(Ops.money(m.balance()), Ops.money(m.usedMargin()), Ops.money(m.available()), Ops.money(m.floatingPnl()),
        Ops.money(m.withdrawFrozen()));
    LocalDate birth = d.birthDate();
    return new Detail(c.id(), c.name(), c.email(), c.nationality(), apiStatus(c.status()), apiStatus(c.kycStatus()), Ops.money(m.balance()), positions.size(),
        Ops.time(c.registeredAt()), Ops.time(c.lastLoginAt()), d.lastLoginIp(), Ops.time(d.lastLoginIpAt()), d.firstName(), d.middleName(), d.lastName(),
        birth == null ? null : birth.toString(), d.frozenReason(), kyc, account, positions, lines, closed, tgId(c), c.telegramUsername(), safety(c.id()),
        withdraws.held(id).stream().map(h -> new HeldView(String.valueOf(h.publicNo()), Ops.money(h.amount()), h.network(), h.address(), Ops.time(h.heldAt()),
            h.reason(), h.sentBefore())).toList());
  }

  private WithdrawSafety safety(long userId) {
    var s = pins.status(userId);
    return new WithdrawSafety(s.set(), Ops.time(s.lockedUntil()), s.withdrawFrozen(), s.frozenSource() == null ? null : s.frozenSource().toLowerCase(),
        Ops.time(s.frozenAt()), s.freezes().stream().map(f -> new FreezeView(f.source().toLowerCase(), f.reason(), Ops.time(f.at()))).toList());
  }

  /**
   * 人工解冻提现（第 4 批：冻结提现只能人工解冻）。高危：先核对管理员自己的登录密码（不对 {@code CURRENT_PASSWORD_WRONG}），原因必填、写审计，
   * 解除以后给告警邮箱（{@code FX_ALERT_EMAILS}）发邮件 {@value #UNFREEZE_MAIL}（收件人为空只记日志，写邮件任务失败不影响解除）。没冻结时报 NOT_FROZEN。
   * 4b 审查 🔴R2：source 为空解除全部来源；给了来源（小写）只解除那一个，别的来源照旧冻结。注意：解除 RECOVERY / DISPUTE 会让进行中的找回申请失去冻结保护，
   * 后台页面要明确选择
   */
  @Transactional
  void unfreezeWithdraw(AdminContext actor, long id, String reason, String password, String source) {
    String r = Audit.requireReason(reason);
    String src = source == null || source.isBlank() || "all".equalsIgnoreCase(source) ? null : source.strip().toLowerCase();
    if (src != null && !SOURCES.contains(src)) throw new ValidationException(List.of(new ValidationException.Field("source", "invalid")));
    auth.requirePassword(actor, password);
    String[] was = new String[1];
    audit.run("customer.withdraw_unfreeze", String.valueOf(id), true, r, () -> {
      customers.customer(id).orElseThrow(() -> new BizException("NOT_FOUND", "客户不存在"));
      List<String> before = pins.status(id).freezes().stream().map(f -> f.source().toLowerCase()).toList();
      List<String> removed = pins.unfreezeWithdraw(id, src == null ? null : src.toUpperCase());
      if (removed.isEmpty()) throw new BizException("NOT_FROZEN", src == null ? "这个客户的提现没有被冻结" : "这个客户没有这个来源的提现冻结");
      was[0] = String.join(",", removed);
      List<String> after = before.stream().filter(x -> !removed.contains(x.toUpperCase())).toList();
      return Audit.Change.of(null, Map.of("withdrawFrozen", String.join(",", before)), Map.of("withdrawFrozen", after.isEmpty() ? "no" : String.join(",", after)));
    });
    String who = names.of(List.of(actor.adminId())).getOrDefault(actor.adminId(), actor.username());
    Map<String, String> p = Map.of("customerId", String.valueOf(id), "source", was[0], "admin", who, "reason", r, "at",
        now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toInstant(java.time.ZoneOffset.UTC).toString());
    List<String> to = alerts.recipients();
    if (to.isEmpty()) log.warn("没有配置告警邮箱（FX_ALERT_EMAILS），客户 {} 解除提现冻结只记了日志（操作人 {}）", id, who);
    for (String email : to) {
      try {
        mail.send(email, UNFREEZE_MAIL, p);
      } catch (RuntimeException e) {
        log.error("解除提现冻结的告警邮件写入发送任务失败", e);
      }
    }
  }

  /** 告警邮件模板：后台解除 / 更换 Telegram 关联（迁移 V40） */
  static final String TG_LINK_MAIL = "OPS_TELEGRAM_LINK";

  /**
   * 后台"解除关联 / 更换 Telegram 关联"（第 4 批 4c，需求文档 08 页第 5 节）：<b>只有超管</b>（不看权限点）、再输自己的后台密码、原因必填、写审计、
   * 发告警邮件；账户模块记安全事件（作废全部网页会话含 BOT 会话）、冻结提现（来源 ADMIN，人工核对后解冻），更换时提现密码和白名单清空。
   * newTelegramId 为空是解除关联
   */
  @Transactional
  void changeTelegram(AdminContext actor, long id, String newTelegramId, String newUsername, String reason, String password) {
    if (!actor.superAdmin()) throw new BizException("FORBIDDEN", "只有超级管理员可以解除或更换 Telegram 关联");
    String r = Audit.requireReason(reason);
    Long tg = null;
    if (newTelegramId != null && !newTelegramId.isBlank()) {
      if (!newTelegramId.strip().matches("\\d{1,19}")) throw new ValidationException(List.of(new ValidationException.Field("telegramId", "invalid")));
      tg = Long.valueOf(newTelegramId.strip());
    }
    String uname = newUsername == null || newUsername.isBlank() ? null : newUsername.strip().replaceFirst("^@", "");
    if (uname != null && !uname.matches("[A-Za-z0-9_]{3,32}")) throw new ValidationException(List.of(new ValidationException.Field("username", "invalid")));
    auth.requirePassword(actor, password);
    Long newTg = tg;
    Long[] old = new Long[1];
    String action = newTg == null ? "customer.telegram_unlink" : "customer.telegram_relink";
    audit.run(action, String.valueOf(id), true, r, () -> {
      customers.customer(id).orElseThrow(() -> new BizException("NOT_FOUND", "客户不存在"));
      old[0] = recovery.adminChangeTelegram(id, newTg, uname, actor.adminId(), r);
      return Audit.Change.of(null, Map.of("telegramId", old[0] == null ? "" : String.valueOf(old[0])), Map.of("telegramId", newTg == null ? "" : String.valueOf(newTg)));
    });
    String who = names.of(List.of(actor.adminId())).getOrDefault(actor.adminId(), actor.username());
    Map<String, String> p = Map.of("customerId", String.valueOf(id), "actionZh", newTg == null ? "解除" : "更换", "actionEn", newTg == null ? "removed" : "changed",
        "oldTg", old[0] == null ? "—" : String.valueOf(old[0]), "newTg", newTg == null ? "—" : String.valueOf(newTg), "admin", who, "reason", r, "at",
        now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toInstant(java.time.ZoneOffset.UTC).toString());
    List<String> to = alerts.recipients();
    if (to.isEmpty()) log.warn("没有配置告警邮箱（FX_ALERT_EMAILS），客户 {} 的 Telegram 关联变更只记了日志（操作人 {}）", id, who);
    for (String email : to) {
      try {
        mail.send(email, TG_LINK_MAIL, p);
      } catch (RuntimeException e) {
        log.error("Telegram 关联变更的告警邮件写入发送任务失败", e);
      }
    }
  }

  /**
   * 编辑客户资料（v2 E1，高危，必须填原因）：姓名（名、中间名、姓）、出生日期、国籍。
   * 审计只记改了的字段；KYC 已通过的客户也允许改，但审计里标出 {@code kycApproved: true}（修改后和证件可能不一致），页面上也会提示。
   */
  @Transactional
  Detail editProfile(long id, ProfileBody b) {
    ProfileBody body = b == null ? new ProfileBody(null, null, null, null, null, null, null, null) : b;
    String r = Audit.requireReason(body.reason());
    ValidationException.Collector notEditable = new ValidationException.Collector();
    if (body.email() != null) notEditable.add("email", "notEditable");
    if (body.status() != null) notEditable.add("status", "notEditable");
    notEditable.throwIfAny();
    audit.run("customer.edit", String.valueOf(id), true, r, () -> {
      CustomerAdminApi.CustomerDetail d = customers.customer(id).orElseThrow(() -> new BizException("NOT_FOUND", "客户不存在"));
      CustomerAdminApi.ProfileEdit before = customers.updateProfile(id, new CustomerAdminApi.ProfileEdit(body.firstName(), body.middleName(), body.lastName(),
          body.birthDate(), body.nationality()));
      CustomerAdminApi.CustomerDetail now = customers.customer(id).orElseThrow();
      CustomerAdminApi.ProfileEdit after = new CustomerAdminApi.ProfileEdit(now.firstName(), now.middleName(), now.lastName(),
          now.birthDate() == null ? null : now.birthDate().toString(), now.customer().nationality());
      Map<String, Object> was = new java.util.LinkedHashMap<>();
      Map<String, Object> is = new java.util.LinkedHashMap<>();
      for (var comp : CustomerAdminApi.ProfileEdit.class.getRecordComponents()) {
        try {
          Object x = comp.getAccessor().invoke(before);
          Object y = comp.getAccessor().invoke(after);
          if (!java.util.Objects.equals(x == null ? "" : x, y == null ? "" : y)) {
            was.put(comp.getName(), x);
            is.put(comp.getName(), y);
          }
        } catch (ReflectiveOperationException e) {
          throw new IllegalStateException(e);
        }
      }
      if ("APPROVED".equals(d.customer().kycStatus())) {
        // KYC 已通过：允许改，但在审计里标出（改姓名、出生日期会和证件不一致）
        is.put("kycApproved", true);
        if (is.containsKey("firstName") || is.containsKey("middleName") || is.containsKey("lastName") || is.containsKey("birthDate")) {
          is.put("kycMismatch", "KYC 已通过，修改后和证件不一致");
        }
      }
      return Audit.Change.of(null, was, is);
    });
    return detail(id);
  }

  static String docType(String db) {
    return db == null ? null : "ID_CARD".equals(db) ? "id_card" : db.toLowerCase();
  }

  /** 账本类型 → 原型的流水类型；没有对应的用小写原名 */
  static String ledgerType(LedgerEntryType t) {
    return switch (t) {
      case DEPOSIT, DEPOSIT_REVERSAL -> "deposit";
      case WITHDRAW_FREEZE, WITHDRAW_PAYOUT -> "withdraw";
      case WITHDRAW_UNFREEZE -> "refund";
      case TRADE_FEE -> "fee";
      case REALIZED_PNL -> "pnl";
      case SWAP -> "swap";
      case ADMIN_ADJUST -> "adjust";
      default -> t.name().toLowerCase();
    };
  }

  /** 冻结（必须填原因，客户所有登录立即失效，AC-04）或解冻 */
  @Transactional
  void setFrozen(long id, boolean frozen, String reason) {
    String r = Audit.requireReason(reason);
    audit.run(frozen ? "customer.freeze" : "customer.unfreeze", String.valueOf(id), true, r, () -> {
      CustomerAdminApi.CustomerDetail d = customers.customer(id).orElseThrow(() -> new BizException("NOT_FOUND", "客户不存在"));
      customers.setFrozen(id, frozen, frozen ? r : null);
      return Audit.Change.of(null, Map.of("status", apiStatus(d.customer().status())), Map.of("status", frozen ? "frozen" : "active"));
    });
  }

  private LocalDateTime startOfToday() {
    return now().toLocalDate().atStartOfDay();
  }

  @Transactional(readOnly = true)
  Limits limits(AdminContext actor) {
    return new Limits(Ops.money(config.decimal(ConfigKey.ADJUST_SINGLE_LIMIT, new BigDecimal("5000"))),
        Ops.money(config.decimal(ConfigKey.ADJUST_DAILY_LIMIT, new BigDecimal("20000"))), Ops.money(adjusts.usedSince(actor.adminId(), startOfToday())));
  }

  /** 调余额（高危，必须填原因）。direction：credit 加 / debit 减 */
  @Transactional
  Detail adjust(AdminContext actor, long id, String direction, String amount, String reason) {
    String r = Audit.requireReason(reason);
    boolean credit = "credit".equals(direction);
    if (!credit && !"debit".equals(direction)) throw new ValidationException(List.of(new ValidationException.Field("direction", "invalid")));
    BigDecimal amt = Ops.amount("amount", amount);
    audit.run("customer.adjust_balance", String.valueOf(id), true, r, () -> {
      customers.customer(id).orElseThrow(() -> new BizException("NOT_FOUND", "客户不存在"));
      BigDecimal single = config.decimal(ConfigKey.ADJUST_SINGLE_LIMIT, new BigDecimal("5000"));
      BigDecimal daily = config.decimal(ConfigKey.ADJUST_DAILY_LIMIT, new BigDecimal("20000"));
      if (amt.compareTo(single) > 0) throw new BizException("OVER_SINGLE_LIMIT", "超过单笔调整上限");
      names.lock(actor.adminId());
      if (adjusts.usedSince(actor.adminId(), startOfToday()).add(amt).compareTo(daily) > 0) throw new BizException("OVER_DAILY_LIMIT", "超过今天的累计调整上限");
      BigDecimal before = ledger.lock(id).balance();
      if (!credit && funds.withdrawable(id).compareTo(amt) < 0) throw new BizException("INSUFFICIENT_AVAILABLE", "扣减金额超过客户的可用余额");
      BigDecimal signed = credit ? amt : amt.negate();
      AdjustMapper.Row row = new AdjustMapper.Row();
      row.adminId = actor.adminId();
      row.userId = id;
      row.amount = signed;
      row.reason = r;
      row.createdAt = now();
      adjusts.insert(row);
      ledger.apply(new LedgerCommand(id, Bucket.BALANCE, signed, LedgerEntryType.ADMIN_ADJUST, "ADMIN_ADJUST", String.valueOf(row.id),
          "后台调余额：" + (r.length() > 200 ? r.substring(0, 200) : r)));
      return Audit.Change.of(null, Map.of("balance", Ops.money(before)), Map.of("balance", Ops.money(before.add(signed)), "amount", Ops.money(signed)));
    });
    return detail(id);
  }
}
