package app.trading.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import app.trading.notification.api.MailApi;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 后台（TG 机器人第 4 批）：客户详情看提现密码和冻结提现；人工解冻提现（第 4 批审查 🟡：单独的权限 withdraw:unfreeze，默认只有超管；
 * 重新输入自己的登录密码；原因必填、写审计；解除后给告警邮箱发邮件）。
 */
@TestPropertySource(properties = "trading.alert.emails=ops@example.com")
class AdminTgSafetyTest extends AdminTestSupport {

  @Autowired ObjectMapper json;
  @MockitoBean MailApi mail;

  JsonNode body(MockHttpServletResponse r) throws Exception {
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    return json.readTree(r.getContentAsString());
  }

  long frozenCustomer() {
    long uid = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 + FLOOR(RAND() * 1000) FROM account_user", Long.class);
    jdbc.update("INSERT INTO account_user (id, email, status) VALUES (?, ?, 'ACTIVE')", uid, "sf" + uid + "@example.com");
    jdbc.update("INSERT INTO trading_account (user_id) VALUES (?)", uid);
    app.trading.support.PinTestSupport.setPin(jdbc, uid, "135790");
    jdbc.update("INSERT INTO account_withdraw_freeze (user_id, source, reason, created_at) VALUES (?, 'PIN_LOCKS', '30 天内提现密码被锁 2 次', '2026-10-14 09:00:00')", uid);
    return uid;
  }

  String unfreeze(long uid, Admin a, String body) throws Exception {
    MockHttpServletResponse r = call(post("/api/admin/customers/" + uid + "/withdraw-unfreeze"), a.cookie(), body);
    return r.getStatus() + " " + r.getContentAsString();
  }

  @Test
  void 客户详情显示提现安全_人工解冻提现要密码_发告警邮件() throws Exception {
    long uid = frozenCustomer();
    Admin viewer = withRoles(role(Permission.CUSTOMER_VIEW));
    Admin a = withRoles(role(Permission.CUSTOMER_VIEW, Permission.WITHDRAW_UNFREEZE));
    JsonNode d = body(call(get("/api/admin/customers/" + uid), viewer.cookie(), null));
    assertThat(d.get("withdrawSafety").toString()).isEqualTo("{\"pinSet\":true,\"pinLockedUntil\":null,\"withdrawFrozen\":true,\"frozenSource\":\"pin_locks\",\"frozenAt\":\"2026-10-14T09:00:00Z\"}");
    assertThat(unfreeze(uid, viewer, "{\"reason\":\"x\",\"password\":\"" + PASSWORD + "\"}")).startsWith("403");
    assertThat(unfreeze(uid, a, "{\"password\":\"" + PASSWORD + "\"}")).as("必须填原因").startsWith("400");
    assertThat(unfreeze(uid, a, "{\"reason\":\"视频核对了本人\"}")).as("没输密码").startsWith("400").contains("CURRENT_PASSWORD_WRONG");
    assertThat(unfreeze(uid, a, "{\"reason\":\"视频核对了本人\",\"password\":\"wrong-password\"}")).as("密码不对").startsWith("400").contains("CURRENT_PASSWORD_WRONG");
    verify(mail, never()).send(anyString(), eq("OPS_WITHDRAW_UNFREEZE"), org.mockito.ArgumentMatchers.anyMap());
    assertThat(body(call(get("/api/admin/customers/" + uid), viewer.cookie(), null)).get("withdrawSafety").get("withdrawFrozen").asBoolean()).as("前面几次都没解开").isTrue();

    clearInvocations(mail);
    JsonNode after = body(call(post("/api/admin/customers/" + uid + "/withdraw-unfreeze"), a.cookie(), "{\"reason\":\"视频核对了本人\",\"password\":\"" + PASSWORD + "\"}"));
    assertThat(after.get("withdrawSafety").get("withdrawFrozen").asBoolean()).isFalse();
    assertThat(audits("customer.withdraw_unfreeze")).anyMatch(l -> l.contains("视频核对了本人"));
    assertThat(audits("customer.withdraw_unfreeze")).noneMatch(l -> l.contains(PASSWORD));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> p = ArgumentCaptor.forClass(Map.class);
    verify(mail).send(eq("ops@example.com"), eq("OPS_WITHDRAW_UNFREEZE"), p.capture());
    assertThat(p.getValue()).containsEntry("customerId", String.valueOf(uid)).containsEntry("source", "PIN_LOCKS").containsEntry("reason", "视频核对了本人");
    assertThat(p.getValue().values()).noneMatch(v -> v.contains(PASSWORD));
    assertThat(unfreeze(uid, a, "{\"reason\":\"再来\",\"password\":\"" + PASSWORD + "\"}")).contains("NOT_FROZEN");
  }

  @Test
  void 出金详情显示冻结和48小时安全事件_冻结时审核通过被拒() throws Exception {
    long uid = frozenCustomer();
    jdbc.update("INSERT INTO account_security_event (user_id, type, created_at) VALUES (?, 'PIN_CHANGE', '2026-10-14 08:30:00'), (?, 'PASSWORD_CHANGE', '2026-10-10 08:00:00')",
        uid, uid);
    long no = app.trading.common.id.Snowflake.shared(1).next();
    jdbc.update("INSERT INTO wallet_withdrawal (public_no, user_id, network, address, amount, fee, status, cooling_ends_at, created_at) "
        + "VALUES (?, ?, 'TRC20', 'TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t', 100, 1, 'REVIEWING', '2026-10-14 08:00:00', '2026-10-14 06:00:00')", no, uid);
    Admin s = superAdmin();
    JsonNode safety = body(call(get("/api/admin/withdrawals/" + no), s.cookie(), null)).get("safety");
    assertThat(safety.get("accountFrozen").asBoolean()).isFalse();
    assertThat(safety.get("withdrawFrozen").asBoolean()).isTrue();
    assertThat(safety.get("withdrawFrozenSource").asString()).isEqualTo("pin_locks");
    assertThat(safety.get("securityEvents48h").toString()).as("只有 48 小时以内的").isEqualTo("[{\"type\":\"pin_change\",\"at\":\"2026-10-14T08:30:00Z\"}]");
    String r = call(post("/api/admin/withdrawals/" + no + "/approve"), s.cookie(), "{}").getContentAsString();
    assertThat(r).contains("WITHDRAW_FROZEN").contains("客户已冻结提现，请先核对");
    assertThat(jdbc.queryForObject("SELECT status FROM wallet_withdrawal WHERE public_no = ?", String.class, no)).isEqualTo("REVIEWING");
  }

  @Test
  void 只有客户冻结权限不能解冻提现_超管可以() throws Exception {
    long uid = frozenCustomer();
    Admin freezer = withRoles(role(Permission.CUSTOMER_VIEW, Permission.CUSTOMER_FREEZE));
    assertThat(unfreeze(uid, freezer, "{\"reason\":\"x\",\"password\":\"" + PASSWORD + "\"}")).as("customer:freeze 不够，要 withdraw:unfreeze").startsWith("403");
    assertThat(unfreeze(uid, superAdmin(), "{\"reason\":\"超管核对后解除\",\"password\":\"" + PASSWORD + "\"}")).startsWith("200");
  }
}
