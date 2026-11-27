package app.trading.admin.ops;

import app.trading.account.api.CustomerAdminApi;
import app.trading.admin.security.AdminContext;
import app.trading.admin.security.AdminNames;
import app.trading.admin.security.Audit;
import app.trading.common.error.BizException;
import app.trading.common.error.ValidationException;
import app.trading.notification.api.NotifyAdminApi;
import app.trading.notification.api.NotifyChannel;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 后台"通知"（S16b）：模板（只改文案，内置模板不能停用）、手动发送给单个客户（高危）、通知记录。 */
@Service
class NotifyOps {

  /** 字段和原型 NotifyTemplate 一致（id 就是模板编号） */
  record TemplateView(String id, String code, String nameZh, String nameEn, boolean builtIn, boolean enabled, List<String> channels, String titleZh,
      String bodyZh, String titleEn, String bodyEn, List<String> variables, String updatedAt, boolean locked, boolean security) {}

  record TemplatePatch(String titleZh, String bodyZh, String titleEn, String bodyEn, Boolean enabled) {}

  /** 字段和原型 ManualNotifyInput 一致 */
  record ManualInput(Long customerId, List<String> channels, String title, String body, String reason) {}

  /** 字段和原型 NotifyRecord 一致；发给风控人员、按邮箱发的没有客户号，customerName 是收件人 */
  record RecordView(String id, Long customerId, String customerName, String source, String templateCode, String channel, String title, String status,
      String error, String operator, String time) {}

  private final NotifyAdminApi notify;
  private final CustomerAdminApi customers;
  private final AdminNames names;
  private final Audit audit;

  private final app.trading.notification.api.MailApi mail;
  private final app.trading.common.health.AlertProperties alerts;
  private final java.time.Clock clock;

  NotifyOps(NotifyAdminApi notify, CustomerAdminApi customers, AdminNames names, Audit audit, app.trading.notification.api.MailApi mail,
      app.trading.common.health.AlertProperties alerts, java.time.Clock clock) {
    this.mail = mail;
    this.alerts = alerts;
    this.clock = clock;
    this.notify = notify;
    this.customers = customers;
    this.names = names;
    this.audit = audit;
  }

  private static TemplateView view(NotifyAdminApi.Template t) {
    return new TemplateView(t.code(), t.code(), t.nameZh(), t.nameEn(), t.builtIn(), t.enabled(), t.channels().stream().map(c -> c.name().toLowerCase()).toList(),
        t.titleZh(), t.bodyZh(), t.titleEn(), t.bodyEn(), t.variables(), Ops.time(t.updatedAt()), "STAFF".equals(t.audience()),
        NotifyAdminApi.securityTemplate(t.code()));
  }

  List<TemplateView> templates() {
    return notify.templates().stream().map(NotifyOps::view).toList();
  }

  TemplateView updateTemplate(String code, TemplatePatch p) {
    TemplatePatch patch = p == null ? new TemplatePatch(null, null, null, null, null) : p;
    Map<String, Object> before = new LinkedHashMap<>();
    notify.templates().stream().filter(t -> t.code().equals(code)).findFirst().ifPresent(t -> {
      before.put("titleZh", t.titleZh());
      before.put("bodyZh", t.bodyZh());
      before.put("titleEn", t.titleEn());
      before.put("bodyEn", t.bodyEn());
      before.put("enabled", t.enabled());
    });
    NotifyAdminApi.Template[] saved = new NotifyAdminApi.Template[1];
    TemplateView v = audit.run("notify.template_update", code, NotifyAdminApi.securityTemplate(code), null, () -> {
      NotifyAdminApi.Template t = notify.updateTemplate(code, patch.titleZh(), patch.bodyZh(), patch.titleEn(), patch.bodyEn(), patch.enabled());
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("titleZh", t.titleZh());
      after.put("bodyZh", t.bodyZh());
      after.put("titleEn", t.titleEn());
      after.put("bodyEn", t.bodyEn());
      after.put("enabled", t.enabled());
      saved[0] = t;
      return Audit.Change.of(view(t), before, after);
    });
    if (NotifyAdminApi.securityTemplate(code)) alertSecurityEdit(saved[0], before);
    return v;
  }

  static final String SECURITY_TEMPLATE_MAIL = "OPS_SECURITY_TEMPLATE";

  /** #46 审查：客户安全提醒模板的文案改了就给告警邮箱发邮件（只改了的字段；写邮件任务失败只记日志） */
  private void alertSecurityEdit(NotifyAdminApi.Template t, Map<String, Object> before) {
    List<String> fields = new java.util.ArrayList<>();
    if (!java.util.Objects.equals(before.get("titleZh"), t.titleZh())) fields.add("titleZh");
    if (!java.util.Objects.equals(before.get("bodyZh"), t.bodyZh())) fields.add("bodyZh");
    if (!java.util.Objects.equals(before.get("titleEn"), t.titleEn())) fields.add("titleEn");
    if (!java.util.Objects.equals(before.get("bodyEn"), t.bodyEn())) fields.add("bodyEn");
    if (fields.isEmpty()) return;
    AdminContext a = AdminContext.find().orElse(null);
    Map<String, String> p = Map.of("code", t.code(), "nameZh", t.nameZh(), "nameEn", t.nameEn(), "admin", a == null ? "?" : a.username() + "（后台账号 #" + a.adminId() + "）",
        "fields", String.join(", ", fields), "at", java.time.LocalDateTime.now(clock).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toInstant(java.time.ZoneOffset.UTC).toString(),
        "bodyZh", t.bodyZh(), "bodyEn", t.bodyEn());
    List<String> to = alerts.recipients();
    if (to.isEmpty()) org.slf4j.LoggerFactory.getLogger(NotifyOps.class).warn("没有配置告警邮箱（FX_ALERT_EMAILS），安全提醒模板 {} 被改只记了审计", t.code());
    for (String email : to) {
      try {
        mail.send(email, SECURITY_TEMPLATE_MAIL, p);
      } catch (RuntimeException e) {
        org.slf4j.LoggerFactory.getLogger(NotifyOps.class).error("安全提醒模板修改的告警邮件写入发送任务失败", e);
      }
    }
  }

  /** 手动发送给单个客户（高危，必须填原因）；推送和邮件仍然遵守客户的通知偏好 */
  @Transactional
  void send(AdminContext actor, ManualInput in) {
    ManualInput input = in == null ? new ManualInput(null, null, null, null, null) : in;
    String r = Audit.requireReason(input.reason());
    if (input.customerId() == null) throw new ValidationException(List.of(new ValidationException.Field("customerId", "required")));
    Set<NotifyChannel> chs = EnumSet.noneOf(NotifyChannel.class);
    for (String c : input.channels() == null ? List.<String>of() : input.channels()) {
      try {
        chs.add(NotifyChannel.valueOf(c.toUpperCase()));
      } catch (IllegalArgumentException e) {
        throw new ValidationException(List.of(new ValidationException.Field("channels", "invalid")));
      }
    }
    audit.run("notify.manual_send", String.valueOf(input.customerId()), true, r, () -> {
      customers.customer(input.customerId()).orElseThrow(() -> new BizException("NOT_FOUND", "客户不存在"));
      notify.manual(input.customerId(), chs, input.title(), input.body(), actor.adminId());
      return Audit.Change.of(null, null, Map.of("channels", chs.stream().map(c -> c.name().toLowerCase()).toList(), "title",
          input.title() == null ? "" : input.title()));
    });
  }

  @Transactional(readOnly = true)
  Ops.Page<RecordView> records(String channel, String status, String keyword, int page, int pageSize) {
    Ops.Slice s = Ops.slice(page, pageSize);
    NotifyChannel ch = null;
    if (Ops.blank(channel) != null) {
      try {
        ch = NotifyChannel.valueOf(channel.strip().toUpperCase());
      } catch (IllegalArgumentException e) {
        throw new ValidationException(List.of(new ValidationException.Field("channel", "invalid")));
      }
    }
    String kw = Ops.blank(keyword);
    List<Long> users = kw == null ? null : customers.search(kw, 500);
    var p = notify.records(ch, Ops.blank(status) == null ? null : status.strip().toUpperCase(), users, s.offset(), s.size());
    var cs = customers.customers(p.items().stream().map(NotifyAdminApi.Record::userId).filter(Objects::nonNull).toList());
    Map<Long, String> ops = names.of(p.items().stream().map(NotifyAdminApi.Record::operatorId).filter(Objects::nonNull).toList());
    return new Ops.Page<>(p.items().stream().map(r -> {
      var c = r.userId() == null ? null : cs.get(r.userId());
      return new RecordView(String.valueOf(r.id()), r.userId(), c != null ? c.name() : r.recipient(), r.source().toLowerCase(), r.templateCode(),
          r.channel().name().toLowerCase(), r.title(), r.status().toLowerCase(), r.error(), r.operatorId() == null ? null : ops.get(r.operatorId()),
          Ops.time(r.createdAt()));
    }).toList(), p.total(), s.page(), s.size());
  }
}
