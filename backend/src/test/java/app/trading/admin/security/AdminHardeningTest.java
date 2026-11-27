package app.trading.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import app.trading.account.api.RecoveryApi;
import app.trading.account.api.TelegramAccountApi;
import app.trading.notification.api.MailApi;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 第 6 批后台加固（TB-37、TB-38）：批准找回申请的告警邮件带一次性撤销链接，用链接不用登录就能撤销（只能撤销、只能一次、写审计）；
 * 暂停受理人工申请只有 Amos 的账号能开关（后台密码 + 原因、审计、告警邮件；别的超管 403 并记失败审计）；告警邮件模板不能在后台改；
 * 告警收件人只能在服务器配（后台系统配置里没有任何收件地址的项）。
 */
@TestPropertySource(properties = {"trading.alert.emails=ops@example.com", "trading.admin.owner-id=" + AdminHardeningTest.OWNER_ID,
    "trading.admin.public-url=https://admin.example.test"})
class AdminHardeningTest extends AdminTestSupport {

  // 不和 AdminEmergencyTest 的 9_000_000_001 挨着：那边插进去以后自增编号从 9_000_000_002 起，会被别的测试建的管理员占掉
  static final long OWNER_ID = 8_000_000_001L;
  static final Pattern LINK = Pattern.compile("https://admin\\.example\\.test/admin/recovery-revoke#t=([A-Za-z0-9_-]{43})");

  @Autowired ObjectMapper json;
  @Autowired TelegramAccountApi telegram;
  @Autowired RecoveryApi recovery;
  @MockitoBean MailApi mail;

  @AfterEach
  void resume() {
    if (recovery.intake().paused()) recovery.setIntakePaused(false, "admin:" + OWNER_ID, "test", "测试结束");
  }

  Admin owner() throws Exception {
    return fixedSuperAdmin(OWNER_ID, "amos_owner_h");
  }

  JsonNode body(MockHttpServletResponse r) throws Exception {
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    return json.readTree(r.getContentAsString());
  }

  @Test
  void 批准的告警邮件带撤销链接_不用登录就能撤销_只能一次_写审计() throws Exception {
    long uid = telegram.openAccount(6_300_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L), "h").userId();
    jdbc.update("INSERT INTO account_recovery (kind, user_id, applicant_tg_id, status, objection_ends_at, created_at) VALUES ('REBIND', ?, 1, 'PROOF', NOW(3), NOW(3))", uid);
    long id = jdbc.queryForObject("SELECT MAX(id) FROM account_recovery WHERE user_id = ?", Long.class, uid);
    Admin s = superAdmin();
    body(call(post("/api/admin/recoveries/" + id + "/approve"), s.cookie(), "{\"note\":\"视频核对\",\"password\":\"" + PASSWORD + "\"}"));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> p = ArgumentCaptor.forClass(Map.class);
    verify(mail).send(eq("ops@example.com"), eq("OPS_RECOVERY_APPROVED"), p.capture());
    Matcher m = LINK.matcher(p.getValue().get("revokeLine"));
    assertThat(m.find()).as(p.getValue().get("revokeLine")).isTrue();
    assertThat(p.getValue().get("revokeLine")).contains("sudo trading cancel-recovery " + id);
    assertThat(p.getValue().get("revokeLineEn")).contains(m.group(0));
    String t = m.group(1);

    // 不带登录 Cookie
    JsonNode peek = body(call(post("/api/admin/public/recovery-revoke/peek"), null, "{\"token\":\"" + t + "\"}"));
    assertThat(peek.get("valid").asBoolean()).isTrue();
    assertThat(peek.get("id").asLong()).isEqualTo(id);
    assertThat(peek.get("status").asString()).isEqualTo("approved");
    JsonNode r = body(call(post("/api/admin/public/recovery-revoke"), null, "{\"token\":\"" + t + "\"}"));
    assertThat(r.get("result").asString()).isEqualTo("cancelled");
    assertThat(jdbc.queryForObject("SELECT status FROM account_recovery WHERE id = ?", String.class, id)).isEqualTo("CANCELLED");
    assertThat(audits("recovery.revoke_link")).anyMatch(l -> l.startsWith("SUCCESS"));
    assertThat(body(call(post("/api/admin/public/recovery-revoke"), null, "{\"token\":\"" + t + "\"}")).get("result").asString()).isEqualTo("invalid");
    assertThat(body(call(post("/api/admin/public/recovery-revoke/peek"), null, "{\"token\":\"" + t + "\"}")).get("valid").asBoolean()).isFalse();
    assertThat(body(call(post("/api/admin/public/recovery-revoke"), null, "{}")).get("result").asString()).isEqualTo("invalid");
  }

  @Test
  void 暂停受理人工申请_只有Amos_要后台密码和原因_审计_告警邮件() throws Exception {
    Admin amos = owner();
    Admin other = superAdmin();
    String pause = "{\"paused\":true,\"reason\":\"出差一周\",\"password\":\"" + PASSWORD + "\"}";
    assertThat(body(call(get("/api/admin/recovery-intake"), other.cookie(), null)).get("canOperate").asBoolean()).isFalse();
    assertThat(body(call(get("/api/admin/recovery-intake"), amos.cookie(), null)).get("canOperate").asBoolean()).isTrue();
    MockHttpServletResponse denied = call(post("/api/admin/recovery-intake"), other.cookie(), pause);
    assertThat(denied.getStatus()).isEqualTo(403);
    assertThat(audits("recovery.intake_pause")).contains("FAILURE " + other.username() + " NOT_OWNER");
    assertThat(call(post("/api/admin/recovery-intake"), amos.cookie(), "{\"paused\":true,\"reason\":\"x\",\"password\":\"wrong\"}").getContentAsString())
        .contains("CURRENT_PASSWORD_WRONG");
    assertThat(call(post("/api/admin/recovery-intake"), amos.cookie(), "{\"paused\":true,\"password\":\"" + PASSWORD + "\"}").getStatus()).isEqualTo(400);
    assertThat(recovery.intake().paused()).isFalse();
    JsonNode v = body(call(post("/api/admin/recovery-intake"), amos.cookie(), pause));
    assertThat(v.get("paused").asBoolean()).isTrue();
    assertThat(v.get("history").get(0).get("reason").asString()).isEqualTo("出差一周");
    assertThat(audits("recovery.intake_pause")).contains("SUCCESS amos_owner_h 出差一周");
    verify(mail).send(eq("ops@example.com"), eq("OPS_RECOVERY_INTAKE"), argThat(p -> p.get("actionZh").equals("暂停受理") && p.get("admin").contains("#" + OWNER_ID)));
    assertThat(call(post("/api/admin/recovery-intake"), amos.cookie(), pause).getContentAsString()).contains("INTAKE_UNCHANGED");
    body(call(post("/api/admin/recovery-intake"), amos.cookie(), "{\"paused\":false,\"reason\":\"回来了\",\"password\":\"" + PASSWORD + "\"}"));
    assertThat(recovery.intake().paused()).isFalse();
    verify(mail).send(eq("ops@example.com"), eq("OPS_RECOVERY_INTAKE"), argThat(p -> p.get("actionZh").equals("恢复受理")));
  }

  @Test
  void 告警邮件模板不能在后台改_列表标出来() throws Exception {
    Admin s = superAdmin();
    MockHttpServletResponse r = call(put("/api/admin/notify/templates/OPS_RECOVERY_APPROVED"), s.cookie(), "{\"bodyZh\":\"批准了 {id}\"}");
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("TEMPLATE_LOCKED");
    assertThat(jdbc.queryForObject("SELECT body_zh FROM notify_template WHERE code = 'OPS_RECOVERY_APPROVED'", String.class)).contains("{revokeLine}");
    JsonNode list = body(call(get("/api/admin/notify/templates"), s.cookie(), null));
    boolean opsLocked = false;
    boolean userUnlocked = false;
    for (JsonNode t : list) {
      if (t.get("code").asString().equals("OPS_TG_EMERGENCY")) opsLocked = t.get("locked").asBoolean();
      if (t.get("code").asString().equals("DEPOSIT_CREDITED")) userUnlocked = !t.get("locked").asBoolean();
    }
    assertThat(opsLocked).isTrue();
    assertThat(userUnlocked).isTrue();
  }

  @Test
  void 告警收件人只能在服务器配_后台系统配置里没有收件地址的项() throws Exception {
    Admin s = superAdmin();
    MockHttpServletResponse r = call(get("/api/admin/configs"), s.cookie(), null);
    assertThat(r.getStatus()).isEqualTo(200);
    // 系统配置里没有任何邮箱地址，也没有"告警 / 收件人"类的项（只有 telegram.withdraw.dailyLimitEmail 这种"有邮箱的客户的额度"）
    assertThat(r.getContentAsString()).doesNotContainPattern("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    for (app.trading.common.config.ConfigKey k : app.trading.common.config.ConfigKey.values()) {
      String key = k.key.toLowerCase();
      assertThat(key).as(k.key).doesNotContain("alertemail").doesNotContain("alert.email").doesNotContain("recipient").doesNotContain("mailto").doesNotContain("emails");
    }
    // 收件人来自 trading.alert.emails（FX_ALERT_EMAILS，服务器 .env），后台没有改它的接口
    assertThat(call(put("/api/admin/configs/alert.emails"), s.cookie(), "{\"value\":\"evil@example.com\",\"reason\":\"x\"}").getStatus()).isNotEqualTo(200);
  }

  @Test
  void 客户安全提醒模板_不能停用_不能删变量_改文案发告警邮件_写高危审计() throws Exception {
    Admin s = superAdmin();
    Map<String, Object> orig = jdbc.queryForMap("SELECT title_zh, body_zh, title_en, body_en, enabled FROM notify_template WHERE code = 'LOGIN_NEW_DEVICE'");
    try {
      MockHttpServletResponse off = call(put("/api/admin/notify/templates/LOGIN_NEW_DEVICE"), s.cookie(), "{\"enabled\":false}");
      assertThat(off.getStatus()).isEqualTo(400);
      assertThat(off.getContentAsString()).contains("SECURITY_TEMPLATE_CANNOT_DISABLE");
      MockHttpServletResponse drop = call(put("/api/admin/notify/templates/LOGIN_NEW_DEVICE"), s.cookie(), "{\"bodyZh\":\"你的账户刚在浏览器里登录：{device}。\"}");
      assertThat(drop.getStatus()).as("删掉了 {ip}").isEqualTo(400);
      assertThat(drop.getContentAsString()).contains("missingVariable").contains("bodyZh");
      assertThat(jdbc.queryForObject("SELECT body_zh FROM notify_template WHERE code = 'LOGIN_NEW_DEVICE'", String.class)).isEqualTo(orig.get("body_zh"));
      verify(mail, org.mockito.Mockito.never()).send(eq("ops@example.com"), eq("OPS_SECURITY_TEMPLATE"), anyMap());

      String body = orig.get("body_zh") + "（客服不会私信你）";
      JsonNode ok = body(call(put("/api/admin/notify/templates/LOGIN_NEW_DEVICE"), s.cookie(), json.writeValueAsString(Map.of("bodyZh", body))));
      assertThat(ok.get("bodyZh").asString()).isEqualTo(body);
      assertThat(ok.get("security").asBoolean()).isTrue();
      verify(mail).send(eq("ops@example.com"), eq("OPS_SECURITY_TEMPLATE"),
          argThat(p -> p.get("code").equals("LOGIN_NEW_DEVICE") && p.get("fields").equals("bodyZh") && p.get("bodyZh").equals(body) && p.get("admin").contains(s.username())));
      assertThat(jdbc.queryForObject("SELECT high_risk FROM admin_audit_log WHERE action = 'notify.template_update' AND target = 'LOGIN_NEW_DEVICE' ORDER BY id DESC LIMIT 1", Boolean.class))
          .as("安全提醒的修改记成高危审计").isTrue();
      // 不是安全提醒的模板：照旧，不发告警邮件
      assertThat(call(get("/api/admin/notify/templates"), s.cookie(), null).getContentAsString()).contains("\"code\":\"DEPOSIT_CREDITED\"");
      assertThat(app.trading.notification.api.NotifyAdminApi.securityTemplate("DEPOSIT_CREDITED")).isFalse();
      for (String c : new String[] {"ACCOUNT_FROZEN", "WITHDRAW_FROZEN", "WITHDRAW_PIN_SET", "WHITELIST_ADDED", "RECOVERY_REQUESTED", "TELEGRAM_LINK_CHANGED",
          "TG_EMERGENCY_LOGIN", "LOGIN_NEW_DEVICE", "PIN_RESET_APPROVED", "EMAIL_CODE"}) {
        assertThat(app.trading.notification.api.NotifyAdminApi.securityTemplate(c)).as(c).isTrue();
      }
    } finally {
      jdbc.update("UPDATE notify_template SET title_zh = ?, body_zh = ?, title_en = ?, body_en = ?, enabled = ? WHERE code = 'LOGIN_NEW_DEVICE'", orig.get("title_zh"),
          orig.get("body_zh"), orig.get("title_en"), orig.get("body_en"), orig.get("enabled"));
    }
  }

  @Test
  void 追保和强平也不能停用_文案变量可以改_迁移把安全提醒追保强平都打开了() throws Exception {
    Admin s = superAdmin();
    for (String c : new String[] {"MARGIN_CALL", "LIQUIDATED"}) {
      MockHttpServletResponse off = call(put("/api/admin/notify/templates/" + c), s.cookie(), "{\"enabled\":false}");
      assertThat(off.getStatus()).as(c).isEqualTo(400);
      assertThat(off.getContentAsString()).as(c).contains("SECURITY_TEMPLATE_CANNOT_DISABLE");
      assertThat(app.trading.notification.api.NotifyAdminApi.cannotDisable(c)).isTrue();
      assertThat(app.trading.notification.api.NotifyAdminApi.securityTemplate(c)).as("只锁停用，不算安全提醒").isFalse();
    }
    Map<String, Object> orig = jdbc.queryForMap("SELECT body_zh FROM notify_template WHERE code = 'MARGIN_CALL'");
    try {
      // 删掉变量也可以（只锁停用）
      body(call(put("/api/admin/notify/templates/MARGIN_CALL"), s.cookie(), "{\"bodyZh\":\"保证金不足，请尽快处理\"}"));
    } finally {
      jdbc.update("UPDATE notify_template SET body_zh = ? WHERE code = 'MARGIN_CALL'", orig.get("body_zh"));
    }
    for (Map<String, Object> r : jdbc.queryForList("SELECT code, enabled FROM notify_template")) {
      String c = (String) r.get("code");
      if (app.trading.notification.api.NotifyAdminApi.cannotDisable(c)) assertThat(r.get("enabled")).as(c + " 启用").isEqualTo(Boolean.TRUE);
    }
  }
}
