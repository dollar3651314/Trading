package app.trading.admin.ops;

import app.trading.account.api.TelegramEmergencyApi;
import app.trading.admin.security.AdminAuthService;
import app.trading.admin.security.AdminContext;
import app.trading.admin.security.Audit;
import app.trading.common.error.BizException;
import app.trading.common.health.AlertProperties;
import app.trading.notification.api.MailApi;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 后台"应急开关"（TG 机器人第 5 批，技术方案 TG机器人-11 页第 3 节，TB-34）：
 * <ul>
 *   <li><b>只有 Amos 的后台账号</b>能开、关：配置 {@code trading.admin.owner-id}（{@code FX_ADMIN_OWNER_ID}）写死他后台账号的<b>编号</b>，不写用户名、不靠权限点；
 *       还要是超管。没配编号时谁都不能在后台操作（只剩服务器备用办法 {@code sudo trading set TR_TG_EMERGENCY_LOGIN on|off}）。
 *       别人点了记一条失败审计（NOT_OWNER）。</li>
 *   <li>开、关都要二次确认：再输一次自己的后台密码（输错和登录失败共用计数、到次数锁定）+ 写原因；写审计；给告警邮箱发邮件。</li>
 *   <li>看页面要 {@code emergency:view}（默认只有超管）。页面列出开关记录（后台和服务器终端的都在）。</li>
 * </ul>
 */
@Service
class EmergencyOps {

  private static final Logger log = LoggerFactory.getLogger(EmergencyOps.class);
  static final String MAIL = "OPS_TG_EMERGENCY";

  record ChangeView(long id, boolean on, String actor, String actorName, String reason, String at) {}

  record View(boolean on, String changedAt, String changedBy, String reason, boolean canOperate, boolean ownerConfigured, List<ChangeView> history) {}

  private final TelegramEmergencyApi emergency;
  private final AdminAuthService auth;
  private final Audit audit;
  private final MailApi mail;
  private final AlertProperties alerts;
  private final Clock clock;
  private final OwnerGuard owner;

  EmergencyOps(TelegramEmergencyApi emergency, AdminAuthService auth, Audit audit, MailApi mail, AlertProperties alerts, Clock clock, OwnerGuard owner) {
    this.emergency = emergency;
    this.auth = auth;
    this.audit = audit;
    this.mail = mail;
    this.alerts = alerts;
    this.clock = clock;
    this.owner = owner;
  }

  boolean canOperate(AdminContext a) {
    return owner.isOwner(a);
  }

  View view(AdminContext a) {
    TelegramEmergencyApi.State s = emergency.state();
    List<ChangeView> h = emergency.history(50).stream()
        .map(c -> new ChangeView(c.id(), c.on(), c.actor(), c.actorName(), c.reason(), Ops.time(c.at()))).toList();
    return new View(s.on(), Ops.time(s.changedAt()), s.changedBy(), s.reason(), canOperate(a), owner.configured(), h);
  }

  void set(AdminContext a, boolean on, String reason, String password) {
    String action = on ? "telegram.emergency_on" : "telegram.emergency_off";
    if (!canOperate(a)) {
      audit.record(action, "telegram.emergency", true, "FAILURE", "NOT_OWNER", null, null);
      throw new BizException("FORBIDDEN", "只有 Amos 的后台账号能开关应急开关");
    }
    String r = Audit.requireReason(reason);
    auth.requirePassword(a, password);
    audit.run(action, "telegram.emergency", true, r, () -> {
      boolean before = emergency.state().on();
      emergency.set(on, "admin:" + a.adminId(), a.username(), r);
      return Audit.Change.of(null, Map.of("on", before), Map.of("on", on));
    });
    Map<String, String> p = Map.of(
        "actionZh", on ? "打开" : "关闭", "actionEn", on ? "on" : "off",
        "effectZh", on ? "网页登录页出现「用 Telegram 登录」，这样登录算安全事件、24 小时不能提现" : "网页上的「用 Telegram 登录」消失，用它登录的会话全部作废",
        "effectEn", on ? "the web sign-in page now shows Sign in with Telegram; such sign-ins are security events with a 24-hour withdrawal hold"
            : "Sign in with Telegram is gone from the web and all sessions signed in that way were ended",
        "admin", a.username() + "（后台账号 #" + a.adminId() + "）", "reason", r,
        "at", LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS).toInstant(ZoneOffset.UTC).toString());
    List<String> to = alerts.recipients();
    if (to.isEmpty()) log.warn("没有配置告警邮箱（FX_ALERT_EMAILS），应急开关{}只记了审计和日志", on ? "打开" : "关闭");
    for (String email : to) {
      try {
        mail.send(email, MAIL, p);
      } catch (RuntimeException e) {
        log.error("应急开关的告警邮件写入发送任务失败", e);
      }
    }
  }
}
