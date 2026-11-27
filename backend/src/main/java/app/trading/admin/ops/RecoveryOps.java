package app.trading.admin.ops;

import app.trading.account.api.CustomerAdminApi;
import app.trading.account.api.RecoveryApi;
import app.trading.admin.security.AdminAuthService;
import app.trading.admin.security.AdminContext;
import app.trading.admin.security.AdminNames;
import app.trading.admin.security.Audit;
import app.trading.common.error.BizException;
import app.trading.common.health.AlertProperties;
import app.trading.notification.api.MailApi;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 后台"找回申请"（第 4 批 4b，需求文档 TG机器人-08 页第 1、3、5 节，TB-19 v14）：Amos 一人审核（权限 {@code recovery:review}，默认只有超管）。
 * 批准、维持（争议路径上收到撤销请求后）要<b>再输一次自己的后台密码</b>（输错计入登录失败锁定）、写审计；批准后给告警邮箱发 {@value #APPROVED_MAIL}。
 * 驳回、撤销要原因、写审计。业务规则在账户模块 {@link RecoveryApi}。
 */
@Service
class RecoveryOps {

  private static final Logger log = LoggerFactory.getLogger(RecoveryOps.class);
  static final String APPROVED_MAIL = "OPS_RECOVERY_APPROVED";
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

  /** 列表和详情：申请 + 被找回的客户（姓名、TG、KYC 状态） */
  record View(String id, String kind, long customerId, String customerName, String customerTelegram, String kycStatus, String applicantTgId,
      String applicantTgUsername, String status, boolean disputed, boolean disputePinOk, boolean disputeFrozen, String objectionEndsAt, String proofNote,
      String approvedBy, String approvedAt, String executeAt, boolean revokeRequested, String revokeRequestedAt, String keptAt, String closedBy, String closeReason,
      String closedAt, String createdAt) {}

  record EventView(String action, String actor, String note, String at) {}

  /**
   * deposits（第 4 批 4c，KYC 核来源，08 页第 0 节）：这个客户最近 10 笔充值，带交易哈希和区块浏览器链接，审核员按它查付款地址、判断"合格来源地址"
   * （早于申请 30 天以上并且早于最近一次安全事件、没有交易所标签、不是多人共用、最近 30 天没出现新的付款地址）
   */
  record Detail(View recovery, List<EventView> events, List<FundsOps.Recent> deposits) {}

  private final RecoveryApi recovery;
  private final CustomerAdminApi customers;
  private final AdminAuthService auth;
  private final AdminNames names;
  private final Audit audit;
  private final MailApi mail;
  private final AlertProperties alerts;

  private final app.trading.wallet.api.WalletAdminApi wallet;
  private final OwnerGuard owner;
  private final java.time.Clock clock;

  RecoveryOps(RecoveryApi recovery, CustomerAdminApi customers, AdminAuthService auth, AdminNames names, Audit audit, MailApi mail, AlertProperties alerts,
      app.trading.wallet.api.WalletAdminApi wallet, OwnerGuard owner, java.time.Clock clock) {
    this.wallet = wallet;
    this.owner = owner;
    this.clock = clock;
    this.recovery = recovery;
    this.customers = customers;
    this.auth = auth;
    this.names = names;
    this.audit = audit;
    this.mail = mail;
    this.alerts = alerts;
  }

  List<View> list(String status) {
    List<RecoveryApi.View> rows = recovery.list(status);
    return views(rows);
  }

  Detail detail(long id) {
    RecoveryApi.Detail d = recovery.detail(id).orElseThrow(() -> new BizException("NOT_FOUND", "申请不存在"));
    var funds = wallet.customerFunds(d.recovery().userId(), 10);
    return new Detail(views(List.of(d.recovery())).getFirst(),
        d.events().stream().map(e -> new EventView(e.action().toLowerCase(), e.actor(), e.note(), Ops.time(e.at()))).toList(),
        funds.recentDeposits().stream().map(x -> new FundsOps.Recent(Ops.time(x.creditedAt() == null ? x.detectedAt() : x.creditedAt()), x.chain(),
            Ops.money(x.amount()), x.txHash(), FundsOps.explorer(x.chain(), x.txHash()))).toList());
  }

  Detail approve(AdminContext actor, long id, String note, String password) {
    String n = note == null ? "" : note.strip();
    if (n.isEmpty()) throw new BizException("PROOF_NOTE_REQUIRED", "要写证明核对记录（来源地址 / 视频共享 App / P2P 等）");
    auth.requirePassword(actor, password);
    RecoveryApi.View before = recovery.detail(id).orElseThrow(() -> new BizException("NOT_FOUND", "申请不存在")).recovery();
    LocalDateTime[] at = new LocalDateTime[1];
    audit.run("recovery.approve", String.valueOf(id), true, n, () -> {
      at[0] = recovery.approve(id, actor.adminId(), n);
      return Audit.Change.of(null, Map.of("status", "proof"), Map.of("status", "approved", "executeAt", Ops.time(at[0])));
    });
    String who = names.of(List.of(actor.adminId())).getOrDefault(actor.adminId(), actor.username());
    boolean rebind = "REBIND".equals(before.kind());
    // 第 6 批 TB-37 ②：告警邮件带撤销入口（一次性链接 + 服务器命令）
    RecoveryApi.RevokeLines lines = revokeLines(id);
    alert(Map.of("id", String.valueOf(id), "kindZh", rebind ? "人工换绑" : "人工重设提现密码", "kindEn", rebind ? "Telegram rebind" : "manual PIN reset",
        "customerId", String.valueOf(before.userId()), "admin", who, "note", n, "executeAt", at[0].format(TIME), "revokeLine", lines.zh(), "revokeLineEn",
        lines.en()));
    return detail(id);
  }

  Detail reject(AdminContext actor, long id, String reason) {
    String r = Audit.requireReason(reason);
    audit.run("recovery.reject", String.valueOf(id), true, r, () -> {
      recovery.reject(id, actor.adminId(), r);
      return Audit.Change.of(null, null, Map.of("status", "rejected"));
    });
    return detail(id);
  }

  /** Amos 本人撤销：直接生效 */
  Detail revoke(AdminContext actor, long id, String reason) {
    String r = Audit.requireReason(reason);
    audit.run("recovery.revoke", String.valueOf(id), true, r, () -> {
      recovery.revokeByAdmin(id, actor.adminId(), r);
      return Audit.Change.of(null, null, Map.of("status", "cancelled"));
    });
    return detail(id);
  }

  /** 争议路径上的撤销请求：维持批准（要再输管理员密码） */
  Detail keep(AdminContext actor, long id, String reason, String password) {
    String r = Audit.requireReason(reason);
    auth.requirePassword(actor, password);
    audit.run("recovery.keep", String.valueOf(id), true, r, () -> {
      recovery.keep(id, actor.adminId(), r);
      return Audit.Change.of(null, Map.of("revokeRequested", true), Map.of("revokeRequested", false));
    });
    return detail(id);
  }

  private List<View> views(List<RecoveryApi.View> rows) {
    Collection<Long> ids = rows.stream().map(RecoveryApi.View::userId).collect(Collectors.toSet());
    Map<Long, CustomerAdminApi.Customer> cs = ids.isEmpty() ? Map.of() : customers.customers(ids);
    Map<Long, String> admins = names.of(rows.stream().map(RecoveryApi.View::approvedBy).filter(java.util.Objects::nonNull).distinct().toList());
    return rows.stream().map(v -> {
      CustomerAdminApi.Customer c = cs.get(v.userId());
      String tg = c == null || c.telegramId() == null ? null : (c.telegramUsername() == null ? "" : "@" + c.telegramUsername() + " · ") + c.telegramId();
      return new View(String.valueOf(v.id()), v.kind().toLowerCase(), v.userId(), c == null ? null : c.name(), tg,
          c == null || c.kycStatus() == null ? null : c.kycStatus().toLowerCase(), v.applicantTgId() == null ? null : String.valueOf(v.applicantTgId()),
          v.applicantTgUsername(), v.status().toLowerCase(), v.disputed(), v.disputePinOk(), v.disputeFrozen(), Ops.time(v.objectionEndsAt()), v.proofNote(),
          v.approvedBy() == null ? null : admins.getOrDefault(v.approvedBy(), "#" + v.approvedBy()), Ops.time(v.approvedAt()), Ops.time(v.executeAt()),
          v.revokeRequestedBy() != null, Ops.time(v.revokeRequestedAt()), Ops.time(v.keptAt()), v.closedBy(), v.closeReason(), Ops.time(v.closedAt()), Ops.time(v.createdAt()));
    }).toList();
  }

  /** 撤销入口；做链接失败时只放服务器命令（邮件照发） */
  RecoveryApi.RevokeLines revokeLines(long id) {
    try {
      return recovery.revokeLines(id);
    } catch (RuntimeException e) {
      log.error("找回申请 #{} 的撤销链接没做成，告警邮件里只放服务器命令", id, e);
      return new RecoveryApi.RevokeLines("在服务器上运行：sudo trading cancel-recovery " + id + " \"原因\"", "On the server run: sudo trading cancel-recovery " + id);
    }
  }

  // ---------- 第 6 批 TB-38：暂停受理人工申请 ----------

  static final String INTAKE_MAIL = "OPS_RECOVERY_INTAKE";

  record IntakeChangeView(long id, boolean paused, String actor, String actorName, String reason, String at) {}

  record IntakeView(boolean paused, String changedAt, String changedBy, String reason, boolean canOperate, boolean ownerConfigured, List<IntakeChangeView> history) {}

  IntakeView intake(AdminContext a) {
    RecoveryApi.Intake s = recovery.intake();
    return new IntakeView(s.paused(), Ops.time(s.changedAt()), s.changedBy(), s.reason(), owner.isOwner(a), owner.configured(),
        recovery.intakeHistory(50).stream().map(c -> new IntakeChangeView(c.id(), c.paused(), c.actor(), c.actorName(), c.reason(), Ops.time(c.at()))).toList());
  }

  /** 只有 Amos 的后台账号（{@link OwnerGuard}）；原因、再输后台密码；审计 recovery.intake_pause / intake_resume；告警邮件 */
  IntakeView setIntake(AdminContext a, boolean paused, String reason, String password) {
    String action = paused ? "recovery.intake_pause" : "recovery.intake_resume";
    if (!owner.isOwner(a)) {
      audit.record(action, "recovery.intake", true, "FAILURE", "NOT_OWNER", null, null);
      throw new BizException("FORBIDDEN", "只有 Amos 的后台账号能暂停或恢复受理人工申请");
    }
    String r = Audit.requireReason(reason);
    auth.requirePassword(a, password);
    audit.run(action, "recovery.intake", true, r, () -> {
      recovery.setIntakePaused(paused, "admin:" + a.adminId(), a.username(), r);
      return Audit.Change.of(null, Map.of("paused", !paused), Map.of("paused", paused));
    });
    Map<String, String> p = Map.of("actionZh", paused ? "暂停受理" : "恢复受理", "actionEn", paused ? "paused" : "resumed",
        "admin", a.username() + "（后台账号 #" + a.adminId() + "）", "reason", r,
        "at", LocalDateTime.now(clock).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toInstant(java.time.ZoneOffset.UTC).toString());
    List<String> to = alerts.recipients();
    if (to.isEmpty()) log.warn("没有配置告警邮箱（FX_ALERT_EMAILS），人工申请{}只记了审计和日志", paused ? "暂停受理" : "恢复受理");
    for (String email : to) {
      try {
        mail.send(email, INTAKE_MAIL, p);
      } catch (RuntimeException e) {
        log.error("暂停受理人工申请的告警邮件写入发送任务失败", e);
      }
    }
    return intake(a);
  }

  private void alert(Map<String, String> p) {
    List<String> to = alerts.recipients();
    if (to.isEmpty()) log.warn("没有配置告警邮箱（FX_ALERT_EMAILS），找回申请 #{} 批准只记了日志", p.get("id"));
    for (String email : to) {
      try {
        mail.send(email, APPROVED_MAIL, p);
      } catch (RuntimeException e) {
        log.error("找回申请批准的告警邮件写入发送任务失败", e);
      }
    }
  }
}
