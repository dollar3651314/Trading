package app.trading.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/** 后台（TG 机器人第 4 批）：客户详情看提现密码和冻结提现，人工解冻提现（高危、要原因、写审计、要冻结权限）。 */
class AdminTgSafetyTest extends AdminTestSupport {

  @Autowired ObjectMapper json;

  JsonNode body(MockHttpServletResponse r) throws Exception {
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    return json.readTree(r.getContentAsString());
  }

  @Test
  void 客户详情显示提现安全_人工解冻提现() throws Exception {
    long uid = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 + FLOOR(RAND() * 1000) FROM account_user", Long.class);
    jdbc.update("INSERT INTO account_user (id, email, status) VALUES (?, ?, 'ACTIVE')", uid, "sf" + uid + "@example.com");
    jdbc.update("INSERT INTO trading_account (user_id) VALUES (?)", uid);
    app.trading.support.PinTestSupport.setPin(jdbc, uid, "135790");
    jdbc.update("INSERT INTO account_withdraw_freeze (user_id, source, reason, created_at) VALUES (?, 'PIN_LOCKS', '30 天内提现密码被锁 2 次', '2026-10-14 09:00:00')", uid);
    Admin viewer = withRoles(role(Permission.CUSTOMER_VIEW));
    Admin a = withRoles(role(Permission.CUSTOMER_VIEW, Permission.CUSTOMER_FREEZE));
    JsonNode d = body(call(get("/api/admin/customers/" + uid), viewer.cookie(), null));
    assertThat(d.get("withdrawSafety").toString()).isEqualTo("{\"pinSet\":true,\"pinLockedUntil\":null,\"withdrawFrozen\":true,\"frozenSource\":\"pin_locks\",\"frozenAt\":\"2026-10-14T09:00:00Z\"}");
    assertThat(call(post("/api/admin/customers/" + uid + "/withdraw-unfreeze"), viewer.cookie(), "{\"reason\":\"x\"}").getStatus()).isEqualTo(403);
    assertThat(call(post("/api/admin/customers/" + uid + "/withdraw-unfreeze"), a.cookie(), "{}").getStatus()).as("必须填原因").isEqualTo(400);
    JsonNode after = body(call(post("/api/admin/customers/" + uid + "/withdraw-unfreeze"), a.cookie(), "{\"reason\":\"视频核对了本人\"}"));
    assertThat(after.get("withdrawSafety").get("withdrawFrozen").asBoolean()).isFalse();
    assertThat(audits("customer.withdraw_unfreeze")).anyMatch(l -> l.contains("视频核对了本人"));
    assertThat(call(post("/api/admin/customers/" + uid + "/withdraw-unfreeze"), a.cookie(), "{\"reason\":\"再来\"}").getContentAsString()).contains("NOT_FROZEN");
  }
}
