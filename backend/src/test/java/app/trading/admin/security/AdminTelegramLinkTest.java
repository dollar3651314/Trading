package app.trading.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import app.trading.account.api.TelegramAccountApi;
import app.trading.common.id.Snowflake;
import app.trading.notification.api.MailApi;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 第 4 批 4c 后台：客户详情"解除关联 / 更换 Telegram 关联"（只有超管、再输后台密码、审计、告警邮件、安全事件作废全部会话、冻结提现 ADMIN、
 * 更换时清空提现密码和白名单）；审核页的充值带交易哈希和区块浏览器链接（KYC 核来源）。
 */
@TestPropertySource(properties = "trading.alert.emails=ops@example.com")
class AdminTelegramLinkTest extends AdminTestSupport {

  @Autowired ObjectMapper json;
  @Autowired TelegramAccountApi telegram;
  @MockitoBean MailApi mail;

  static long newTg() {
    return 6_700_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
  }

  JsonNode body(MockHttpServletResponse r) throws Exception {
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    return json.readTree(r.getContentAsString());
  }

  String link(long uid, Admin a, String body) throws Exception {
    MockHttpServletResponse r = call(post("/api/admin/customers/" + uid + "/telegram"), a.cookie(), body);
    return r.getStatus() + " " + r.getContentAsString();
  }

  long session(long uid) {
    jdbc.update("INSERT INTO account_session (user_id, token_hash, ip, user_agent, created_at, last_seen_at, expires_at) VALUES (?, ?, '203.0.113.9', 't', NOW(3), NOW(3), NOW(3) + INTERVAL 1 DAY)",
        uid, String.format("%064x", ThreadLocalRandom.current().nextLong(Long.MAX_VALUE)));
    return jdbc.queryForObject("SELECT MAX(id) FROM account_session WHERE user_id = ?", Long.class, uid);
  }

  @Test
  void 更换和解除Telegram关联_只有超管_要后台密码_安全事件_冻结提现_清提现密码白名单_审计_告警() throws Exception {
    long oldTg = newTg();
    long uid = telegram.openAccount(oldTg, "old").userId();
    app.trading.support.PinTestSupport.setPin(jdbc, uid, "135790");
    jdbc.update("INSERT INTO wallet_whitelist (user_id, network, address, address_key, label, created_at, active_at) VALUES (?, 'TRC20', 'T1', 'T1', 'x', NOW(3), NOW(3))", uid);
    long other = telegram.openAccount(newTg(), "other").userId();
    long taken = telegram.byUserId(other).orElseThrow().telegramId();
    Admin notSuper = withRoles(role(Permission.CUSTOMER_VIEW, Permission.CUSTOMER_FREEZE, Permission.WITHDRAW_UNFREEZE, Permission.RECOVERY_REVIEW));
    Admin s = superAdmin();
    long newTg = newTg();
    String ok = "{\"telegramId\":\"" + newTg + "\",\"username\":\"@newme\",\"reason\":\"视频核对后换绑\",\"password\":\"" + PASSWORD + "\"}";
    assertThat(link(uid, notSuper, ok)).as("只有超管").startsWith("403");
    assertThat(link(uid, s, "{\"telegramId\":\"" + newTg + "\",\"reason\":\"x\",\"password\":\"wrong\"}")).contains("CURRENT_PASSWORD_WRONG");
    assertThat(link(uid, s, "{\"telegramId\":\"" + taken + "\",\"reason\":\"x\",\"password\":\"" + PASSWORD + "\"}")).contains("TELEGRAM_TAKEN");
    assertThat(link(uid, s, "{\"telegramId\":\"12x\",\"reason\":\"x\",\"password\":\"" + PASSWORD + "\"}")).startsWith("400");
    assertThat(telegram.byUserId(uid).orElseThrow().telegramId()).isEqualTo(oldTg);

    long sess = session(uid);
    JsonNode d = body(call(post("/api/admin/customers/" + uid + "/telegram"), s.cookie(), ok));
    assertThat(d.get("telegramId").asString()).isEqualTo(String.valueOf(newTg));
    assertThat(telegram.byUserId(uid).orElseThrow().username()).isEqualTo("newme");
    assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM account_session WHERE id = ?", Boolean.class, sess)).as("安全事件作废全部会话").isTrue();
    assertThat(jdbc.queryForList("SELECT type FROM account_security_event WHERE user_id = ?", String.class, uid)).contains("REBIND");
    assertThat(jdbc.queryForList("SELECT source FROM account_withdraw_freeze WHERE user_id = ?", String.class, uid)).containsExactly("ADMIN");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_withdraw_pin WHERE user_id = ?", Integer.class, uid)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_whitelist WHERE user_id = ?", Integer.class, uid)).isZero();
    assertThat(audits("customer.telegram_relink")).anyMatch(l -> l.contains("视频核对后换绑"));
    verify(mail).send(eq("ops@example.com"), eq("OPS_TELEGRAM_LINK"),
        argThat(p -> p.get("oldTg").equals(String.valueOf(oldTg)) && p.get("newTg").equals(String.valueOf(newTg)) && p.get("actionZh").equals("更换")));

    JsonNode u = body(call(post("/api/admin/customers/" + uid + "/telegram"), s.cookie(), "{\"reason\":\"客户要求解除\",\"password\":\"" + PASSWORD + "\"}"));
    assertThat(u.get("telegramId").isNull()).isTrue();
    assertThat(telegram.byUserId(uid)).isEmpty();
    assertThat(audits("customer.telegram_unlink")).anyMatch(l -> l.contains("客户要求解除"));
    assertThat(link(uid, s, "{\"reason\":\"再来\",\"password\":\"" + PASSWORD + "\"}")).contains("NOT_TG_ACCOUNT");
  }

  @Test
  void 审核页的充值带交易哈希和区块浏览器链接() throws Exception {
    long uid = telegram.openAccount(newTg(), "dep").userId();
    String tx = "a".repeat(60) + Long.toHexString(System.nanoTime()).substring(0, 4);
    jdbc.update("INSERT INTO wallet_deposit (public_no, chain, tx_hash, log_index, block_number, block_hash, from_address, to_address, user_id, amount, status, "
        + "confirmations, credited_at) VALUES (?, 'TRC20', ?, 0, 1, '0x1', 'Ta', 'Tb', ?, 50, 'CREDITED', 20, '2026-10-01 08:00:00')", Snowflake.shared(1).next(), tx, uid);
    long no = Snowflake.shared(1).next();
    jdbc.update("INSERT INTO wallet_withdrawal (public_no, user_id, network, address, amount, fee, status, cooling_ends_at, created_at) "
        + "VALUES (?, ?, 'TRC20', 'TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t', 10, 1, 'REVIEWING', '2026-10-14 08:00:00', '2026-10-14 06:00:00')", no, uid);
    Admin s = superAdmin();
    JsonNode dep = body(call(get("/api/admin/withdrawals/" + no), s.cookie(), null)).get("recentDeposits").get(0);
    assertThat(dep.get("txHash").asString()).isEqualTo(tx);
    assertThat(dep.get("explorerUrl").asString()).isEqualTo("https://tronscan.org/#/transaction/" + tx);
    jdbc.update("UPDATE wallet_withdrawal SET status = 'CANCELLED' WHERE public_no = ?", no);
    jdbc.update("INSERT INTO account_recovery (kind, user_id, applicant_tg_id, status, objection_ends_at, created_at) VALUES ('REBIND', ?, 1, 'PROOF', NOW(3), NOW(3))", uid);
    long rid = jdbc.queryForObject("SELECT MAX(id) FROM account_recovery WHERE user_id = ?", Long.class, uid);
    JsonNode rd = body(call(get("/api/admin/recoveries/" + rid), s.cookie(), null));
    assertThat(rd.get("deposits").get(0).get("explorerUrl").asString()).isEqualTo("https://tronscan.org/#/transaction/" + tx);
  }
}
