package app.trading.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import app.trading.account.api.TelegramAccountApi;
import app.trading.common.id.Snowflake;
import app.trading.notification.api.MailApi;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 后台"找回申请"（第 4 批 4b）：权限 recovery:review（默认只有超管）；批准要证明核对记录和再输自己的后台密码、写审计、发告警邮件；
 * 再输密码输错和登录失败共用计数，到次数锁定并退出登录、写审计（4a 复审 🟡2）；冻结时暂停的提现在客户详情里列出、逐笔继续或取消（🟡1）。
 */
@TestPropertySource(properties = "trading.alert.emails=ops@example.com")
class AdminRecoveryTest extends AdminTestSupport {

  @Autowired ObjectMapper json;
  @Autowired TelegramAccountApi telegram;
  @MockitoBean MailApi mail;

  JsonNode body(MockHttpServletResponse r) throws Exception {
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    return json.readTree(r.getContentAsString());
  }

  /** 一个 TG 客户和一份已到证明阶段的换绑申请，返回申请编号 */
  long proofRecovery() {
    long uid = telegram.openAccount(6_500_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L), "orig").userId();
    jdbc.update("INSERT INTO account_recovery (kind, user_id, applicant_tg_id, applicant_tg_username, status, objection_ends_at, created_at) "
        + "VALUES ('REBIND', ?, ?, 'newme', 'PROOF', '2026-10-12 00:00:00', '2026-10-10 00:00:00')", uid, 8_000_000_000L + uid);
    return jdbc.queryForObject("SELECT MAX(id) FROM account_recovery WHERE user_id = ?", Long.class, uid);
  }

  String approve(long id, Admin a, String body) throws Exception {
    MockHttpServletResponse r = call(post("/api/admin/recoveries/" + id + "/approve"), a.cookie(), body);
    return r.getStatus() + " " + r.getContentAsString();
  }

  @Test
  void 只有recovery_review能看能批_批准要核对记录和后台密码_写审计_发告警邮件() throws Exception {
    long id = proofRecovery();
    Admin ops = withRoles(role(Permission.CUSTOMER_VIEW, Permission.CUSTOMER_FREEZE, Permission.WITHDRAW_UNFREEZE));
    assertThat(call(get("/api/admin/recoveries"), ops.cookie(), null).getStatus()).isEqualTo(403);
    assertThat(approve(id, ops, "{\"note\":\"x\",\"password\":\"" + PASSWORD + "\"}")).startsWith("403");

    Admin amos = withRoles(role(Permission.RECOVERY_REVIEW));
    JsonNode list = body(call(get("/api/admin/recoveries?status=proof"), amos.cookie(), null));
    assertThat(list.toString()).contains("\"id\":\"" + id + "\"").contains("\"applicantTgUsername\":\"newme\"");
    assertThat(approve(id, amos, "{\"password\":\"" + PASSWORD + "\"}")).contains("PROOF_NOTE_REQUIRED");
    assertThat(approve(id, amos, "{\"note\":\"视频\",\"password\":\"wrong\"}")).contains("CURRENT_PASSWORD_WRONG");
    verify(mail, never()).send(anyString(), eq("OPS_RECOVERY_APPROVED"), anyMap());

    JsonNode d = body(call(post("/api/admin/recoveries/" + id + "/approve"), amos.cookie(), "{\"note\":\"来源地址小额 1.37 + 视频\",\"password\":\"" + PASSWORD + "\"}"));
    assertThat(d.get("recovery").get("status").asString()).isEqualTo("approved");
    assertThat(d.get("recovery").get("executeAt").asString()).isNotBlank();
    assertThat(d.get("events").toString()).contains("\"action\":\"approve\"");
    assertThat(audits("recovery.approve")).anyMatch(l -> l.contains("来源地址小额 1.37"));
    assertThat(audits("recovery.approve")).noneMatch(l -> l.contains(PASSWORD));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> p = ArgumentCaptor.forClass(Map.class);
    verify(mail).send(eq("ops@example.com"), eq("OPS_RECOVERY_APPROVED"), p.capture());
    assertThat(p.getValue()).containsEntry("id", String.valueOf(id)).containsEntry("kindZh", "人工换绑").containsEntry("note", "来源地址小额 1.37 + 视频");

    // 等待期里 Amos 本人撤销：直接生效
    JsonNode rv = body(call(post("/api/admin/recoveries/" + id + "/revoke"), amos.cookie(), "{\"reason\":\"复核后撤销\"}"));
    assertThat(rv.get("recovery").get("status").asString()).isEqualTo("cancelled");
    assertThat(audits("recovery.revoke")).anyMatch(l -> l.contains("复核后撤销"));
  }

  @Test
  void 驳回要原因_维持要后台密码() throws Exception {
    long id = proofRecovery();
    Admin amos = superAdmin();
    assertThat(call(post("/api/admin/recoveries/" + id + "/reject"), amos.cookie(), "{}").getStatus()).isEqualTo(400);
    JsonNode r = body(call(post("/api/admin/recoveries/" + id + "/reject"), amos.cookie(), "{\"reason\":\"视频对不上\"}"));
    assertThat(r.get("recovery").get("status").asString()).isEqualTo("rejected");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_recovery_ban WHERE recovery_id = ? AND kind = 'TG'", Integer.class, id)).isEqualTo(1);

    long id2 = proofRecovery();
    body(call(post("/api/admin/recoveries/" + id2 + "/approve"), amos.cookie(), "{\"note\":\"视频\",\"password\":\"" + PASSWORD + "\"}"));
    jdbc.update("UPDATE account_recovery SET disputed = 1, revoke_requested_by = 'ORIGINAL_TG', revoke_requested_at = NOW(3) WHERE id = ?", id2);
    MockHttpServletResponse noPw = call(post("/api/admin/recoveries/" + id2 + "/keep"), amos.cookie(), "{\"reason\":\"维持\"}");
    assertThat(noPw.getContentAsString()).contains("CURRENT_PASSWORD_WRONG");
    JsonNode k = body(call(post("/api/admin/recoveries/" + id2 + "/keep"), amos.cookie(), "{\"reason\":\"视频核对后维持\",\"password\":\"" + PASSWORD + "\"}"));
    assertThat(k.get("recovery").get("revokeRequested").asBoolean()).isFalse();
  }

  @Test
  void 再输后台密码输错_和登录失败共用计数_到次数锁定并退出登录_每次写审计() throws Exception {
    long id = proofRecovery();
    Admin amos = withRoles(role(Permission.RECOVERY_REVIEW));
    for (int i = 0; i < 4; i++) assertThat(approve(id, amos, "{\"note\":\"视频\",\"password\":\"wrong-" + i + "\"}")).contains("CURRENT_PASSWORD_WRONG");
    assertThat(jdbc.queryForObject("SELECT failed_count FROM admin_user WHERE id = ?", Integer.class, amos.id())).isEqualTo(4);
    assertThat(approve(id, amos, "{\"note\":\"视频\",\"password\":\"wrong-5\"}")).contains("LOGIN_LOCKED");
    assertThat(jdbc.queryForObject("SELECT locked_until IS NOT NULL FROM admin_user WHERE id = ?", Boolean.class, amos.id())).isTrue();
    assertThat(call(get("/api/admin/recoveries"), amos.cookie(), null).getStatus()).as("锁定时退出全部登录").isEqualTo(401);
    assertThat(audits("admin.reauth").stream().filter(l -> l.contains(amos.username())).count()).isEqualTo(5);
    assertThat(call(post("/api/admin/auth/login"), null, "{\"username\":\"" + amos.username() + "\",\"password\":\"" + PASSWORD + "\"}").getContentAsString())
        .as("锁定期间登录也不行").contains("LOGIN_LOCKED");
    assertThat(jdbc.queryForObject("SELECT status FROM account_recovery WHERE id = ?", String.class, id)).isEqualTo("PROOF");
  }

  @Test
  void 客户详情列出暂停的提现_继续放款_交过的不能取消() throws Exception {
    long uid = telegram.openAccount(6_600_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L), "held").userId();
    long no = Snowflake.shared(1).next();
    jdbc.update("INSERT INTO wallet_withdrawal (public_no, user_id, network, address, amount, fee, status, cooling_ends_at, created_at, held_at, held_reason, send_attempts) "
        + "VALUES (?, ?, 'TRC20', 'TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t', 100, 1, 'APPROVED', '2026-10-14 08:00:00', '2026-10-14 06:00:00', '2026-10-14 09:00:00', '提现已冻结', 1)",
        no, uid);
    Admin s = superAdmin();
    JsonNode held = body(call(get("/api/admin/customers/" + uid), s.cookie(), null)).get("heldWithdrawals");
    assertThat(held.toString()).isEqualTo("[{\"id\":\"" + no + "\",\"amount\":\"100\",\"chain\":\"TRC20\",\"address\":\"TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t\","
        + "\"heldAt\":\"2026-10-14T09:00:00Z\",\"reason\":\"提现已冻结\",\"sentBefore\":true}]");
    Admin viewer = withRoles(role(Permission.CUSTOMER_VIEW, Permission.WITHDRAWAL_VIEW));
    assertThat(call(post("/api/admin/withdrawals/" + no + "/release-held"), viewer.cookie(), "{\"reason\":\"x\"}").getStatus()).isEqualTo(403);
    assertThat(call(post("/api/admin/withdrawals/" + no + "/cancel-held"), s.cookie(), "{\"reason\":\"取消\"}").getContentAsString()).contains("WITHDRAW_SENT_BEFORE");
    assertThat(call(post("/api/admin/withdrawals/" + no + "/release-held"), s.cookie(), "{}").getStatus()).as("要原因").isEqualTo(400);
    body(call(post("/api/admin/withdrawals/" + no + "/release-held"), s.cookie(), "{\"reason\":\"视频核对本人\"}"));
    assertThat(jdbc.queryForObject("SELECT held_at IS NULL FROM wallet_withdrawal WHERE public_no = ?", Boolean.class, no)).isTrue();
    assertThat(audits("withdrawal.release_held")).anyMatch(l -> l.contains("视频核对本人"));
    assertThat(body(call(get("/api/admin/customers/" + uid), s.cookie(), null)).get("heldWithdrawals").size()).isZero();
    // 收尾：继续放款后留在已审核待放款（测试里没有可用的付款器），标成已撤销，免得别的测试类跑提现推进时把它交给自己的假广播器
    jdbc.update("UPDATE wallet_withdrawal SET status = 'CANCELLED' WHERE public_no = ?", no);
  }
}
