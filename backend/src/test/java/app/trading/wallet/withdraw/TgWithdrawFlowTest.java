package app.trading.wallet.withdraw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import app.trading.account.api.WithdrawPinApi.PinInput;
import app.trading.common.config.ConfigKey;
import app.trading.common.config.SystemConfig;
import app.trading.common.error.BizException;
import app.trading.common.error.ValidationException;
import app.trading.notification.api.MailApi;
import app.trading.support.FixedClockConfig;
import app.trading.support.MutableClock;
import app.trading.support.PinTestSupport;
import app.trading.trading.api.Bucket;
import app.trading.trading.api.LedgerApi;
import app.trading.trading.api.LedgerCommand;
import app.trading.trading.api.LedgerEntryType;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * TG 客户的提现规则（TG 机器人第 4 批，05 页第 5 节必测：网页加白名单、网页提现也要提现密码；安全事件后 24 小时从网页、机器人都提不了；
 * 没邮箱每天 500、首笔标人工核实；TG 当日额度并发提交不超；白名单未生效不能提）。机器人会话和网页会话都走同一套规则。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class TgWithdrawFlowTest {

  static final String TRON = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";
  static final String PIN = "135790";

  @Autowired WithdrawService withdraws;
  @Autowired WhitelistService whitelist;
  @Autowired LedgerApi ledger;
  @Autowired SystemConfig config;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired Clock clock;
  @Autowired MockMvc mvc;
  @MockitoBean MailApi mail;

  @BeforeEach
  void open() {
    config.setBySystem(ConfigKey.TELEGRAM_WITHDRAW_ENABLED, "true", LocalDateTime.now());
  }

  @AfterEach
  void close() {
    config.setBySystem(ConfigKey.TELEGRAM_WITHDRAW_ENABLED, "false", LocalDateTime.now());
    ((MutableClock) clock).reset();
  }

  /** 这个测试类建的客户（收尾用） */
  final java.util.List<Long> users = new java.util.ArrayList<>();

  /**
   * 收尾：没有广播器时审核通过的提现留在"已审核待放款"，后面别的测试类跑提现推进会把它交给自己的假广播器（共用一个库），
   * 所以把这个类的客户还在途的提现标成已撤销（只改测试数据，不影响本类的断言）
   */
  @AfterEach
  void closeInFlight() {
    for (Long u : users) {
      jdbc.update("UPDATE wallet_withdrawal SET status = 'CANCELLED' WHERE user_id = ? AND status IN ('COOLING', 'REVIEWING', 'DELAYED', 'APPROVED')", u);
    }
    users.clear();
  }

  LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
  }

  /** TG 客户：开户 40 多天、KYC 已通过、余额 deposit、设好提现密码；email 不为空时有邮箱 */
  long tg(String deposit, String email) {
    long uid = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 + FLOOR(RAND() * 1000) FROM account_user", Long.class);
    jdbc.update("INSERT INTO account_user (id, email, status, created_at) VALUES (?, ?, 'ACTIVE', '2026-09-01 00:00:00')", uid, email);
    PinTestSupport.makeTg(jdbc, uid);
    ledger.openAccount(uid);
    ledger.apply(new LedgerCommand(uid, Bucket.BALANCE, new BigDecimal(deposit), LedgerEntryType.DEPOSIT, "TEST", "tgdep-" + uid, "测试入金"));
    jdbc.update("INSERT INTO account_kyc_application (user_id, doc_type, doc_number, front_key, back_key, selfie_key, status) VALUES (?, 'PASSPORT', ?, 'f', 'b', 's', 'APPROVED')",
        uid, "TP" + uid);
    PinTestSupport.setPin(jdbc, uid, PIN);
    users.add(uid);
    return uid;
  }

  PinInput pin(long uid) {
    return PinTestSupport.input(redis, uid, PIN);
  }

  long activeAddress(long uid) {
    WhitelistRow r = whitelist.add(uid, "TRC20", TRON, "测试", pin(uid));
    jdbc.update("UPDATE wallet_whitelist SET active_at = ? WHERE id = ?", now().minusHours(1), r.id);
    return r.id;
  }

  String code(Runnable r) {
    try {
      r.run();
      return "OK";
    } catch (BizException e) {
      return e.code();
    } catch (ValidationException e) {
      return "VALIDATION:" + e.fields().getFirst().field();
    }
  }

  /** 网页会话或机器人会话（直接写会话表，令牌的哈希和 SessionService 一样） */
  Cookie session(long uid, String kind) throws Exception {
    String token = "tok-" + uid + "-" + kind + "-" + System.nanoTime();
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    jdbc.update("INSERT INTO account_session (user_id, token_hash, kind, created_at, last_seen_at, expires_at, max_expires_at) "
        + "VALUES (?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) + INTERVAL 1 HOUR, UTC_TIMESTAMP(3) + INTERVAL 12 HOUR)", uid, hash, kind);
    return new Cookie("fx_session", token);
  }

  MockHttpServletResponse httpSubmit(Cookie c, long uid, long wid, String amount, boolean withPin) throws Exception {
    String pinJson = "";
    if (withPin) {
      PinInput p = pin(uid);
      pinJson = ",\"pin\":{\"keypadId\":\"" + p.keypadId() + "\",\"positions\":" + p.positions() + "}";
    }
    return mvc.perform(post("/api/wallet/withdrawals").cookie(c).contentType(MediaType.APPLICATION_JSON)
        .content("{\"whitelistId\":\"" + wid + "\",\"amount\":\"" + amount + "\"" + pinJson + "}")).andReturn().getResponse();
  }

  @Test
  void 加白名单要提现密码_至少24小时生效_通知() {
    long uid = tg("1000", null);
    config.setBySystem(ConfigKey.WHITELIST_EFFECTIVE_HOURS, "1", LocalDateTime.now());
    try {
      assertThat(code(() -> whitelist.add(uid, "TRC20", TRON, "x", null))).isEqualTo("VALIDATION:pin");
      assertThat(code(() -> whitelist.add(uid, "TRC20", TRON, "x", PinTestSupport.input(redis, uid, "000123")))).isEqualTo("PIN_WRONG");
      WhitelistRow r = whitelist.add(uid, "TRC20", TRON, "x", pin(uid));
      assertThat(Duration.between(r.createdAt, r.activeAt)).as("后台设 1 小时，TG 客户也至少 24 小时").isEqualTo(Duration.ofHours(24));
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE user_id = ? AND template_code = 'WHITELIST_ADDED' AND channel = 'TELEGRAM'",
          Integer.class, uid)).isEqualTo(1);
      // 生效前 1 小时、生效时各通知一次（重复跑不重复发）
      jdbc.update("UPDATE wallet_whitelist SET active_at = ? WHERE id = ?", now().plusMinutes(30), r.id);
      whitelist.notifyWhitelist();
      whitelist.notifyWhitelist();
      jdbc.update("UPDATE wallet_whitelist SET active_at = ? WHERE id = ?", now().minusMinutes(1), r.id);
      whitelist.notifyWhitelist();
      whitelist.notifyWhitelist();
      assertThat(jdbc.queryForList("SELECT template_code FROM notify_record WHERE user_id = ? AND channel = 'INBOX' AND template_code LIKE 'WHITELIST%' ORDER BY id",
          String.class, uid)).containsExactly("WHITELIST_ADDED", "WHITELIST_SOON", "WHITELIST_ACTIVE");
    } finally {
      config.setBySystem(ConfigKey.WHITELIST_EFFECTIVE_HOURS, "24", LocalDateTime.now());
    }
  }

  @Test
  void 提现要提现密码_白名单未生效不能提() {
    long uid = tg("1000", null);
    WhitelistRow r = whitelist.add(uid, "TRC20", TRON, "x", pin(uid));
    assertThat(code(() -> withdraws.submit(uid, String.valueOf(r.id), "100", null, "WEB"))).isEqualTo("VALIDATION:pin");
    assertThat(code(() -> withdraws.submit(uid, String.valueOf(r.id), "100", PinTestSupport.input(redis, uid, "000123"), "WEB"))).isEqualTo("PIN_WRONG");
    assertThat(code(() -> withdraws.submit(uid, String.valueOf(r.id), "100", pin(uid), "WEB"))).isEqualTo("WHITELIST_NOT_ACTIVE");
  }

  @Test
  void 安全事件后24小时网页会话和机器人会话都提不了_重设密码48小时_开户不满24小时也不行() throws Exception {
    long uid = tg("1000", null);
    long wid = activeAddress(uid);
    jdbc.update("INSERT INTO account_security_event (user_id, type, created_at) VALUES (?, 'PIN_CHANGE', ?)", uid, now().minusHours(23));
    for (String kind : new String[] {"WEB", "BOT"}) {
      MockHttpServletResponse r = httpSubmit(session(uid, kind), uid, wid, "100", true);
      assertThat(r.getStatus()).as(kind).isEqualTo(400);
      assertThat(r.getContentAsString()).as(kind).contains("WITHDRAW_HOLD");
    }
    ((MutableClock) clock).advance(Duration.ofHours(2));
    assertThat(code(() -> withdraws.submit(uid, String.valueOf(wid), "100", pin(uid), "WEB"))).as("满 24 小时").isEqualTo("OK");

    long u2 = tg("1000", null);
    long w2 = activeAddress(u2);
    jdbc.update("INSERT INTO account_security_event (user_id, type, created_at) VALUES (?, 'PIN_RESET', ?)", u2, now().minusHours(30));
    assertThat(code(() -> withdraws.submit(u2, String.valueOf(w2), "100", pin(u2), "WEB"))).as("重设提现密码后 48 小时").isEqualTo("WITHDRAW_HOLD");
    ((MutableClock) clock).advance(Duration.ofHours(19));
    assertThat(code(() -> withdraws.submit(u2, String.valueOf(w2), "100", pin(u2), "WEB"))).isEqualTo("OK");

    long u3 = tg("1000", null);
    long w3 = activeAddress(u3);
    jdbc.update("UPDATE account_user SET created_at = ? WHERE id = ?", now().minusHours(20), u3);
    assertThat(code(() -> withdraws.submit(u3, String.valueOf(w3), "100", pin(u3), "WEB"))).as("开户不满 24 小时").isEqualTo("WITHDRAW_HOLD");
  }

  @Test
  void 没邮箱每天500_首笔标人工核实_一律人工审核_记入口() throws Exception {
    long uid = tg("5000", null);
    long wid = activeAddress(uid);
    MockHttpServletResponse r = httpSubmit(session(uid, "BOT"), uid, wid, "300", true);
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(jdbc.queryForMap("SELECT rule_type, source, first_check FROM wallet_withdrawal WHERE user_id = ?", uid))
        .containsEntry("rule_type", "TG").containsEntry("source", "BOT").containsEntry("first_check", true);
    assertThat(code(() -> withdraws.submit(uid, String.valueOf(wid), "250", pin(uid), "WEB"))).as("300 + 250 > 500（网页和机器人合并）").isEqualTo("TG_DAILY_LIMIT");
    assertThat(code(() -> withdraws.submit(uid, String.valueOf(wid), "200", pin(uid), "WEB"))).as("正好 500 可以").isEqualTo("OK");

    // 冷静期结束：一律人工（不自动通过）
    long id = jdbc.queryForObject("SELECT MIN(id) FROM wallet_withdrawal WHERE user_id = ?", Long.class, uid);
    ((MutableClock) clock).advance(Duration.ofHours(3));
    withdraws.advance();
    assertThat(jdbc.queryForObject("SELECT status FROM wallet_withdrawal WHERE id = ?", String.class, id)).isEqualTo("REVIEWING");
    // 首笔：不确认已核实不能通过
    assertThat(code(() -> withdraws.approve(id, 1L, false))).isEqualTo("FIRST_CHECK_REQUIRED");
    withdraws.approve(id, 1L, true);
    assertThat(jdbc.queryForObject("SELECT first_checked_by FROM wallet_withdrawal WHERE id = ?", Long.class, id)).isEqualTo(1L);
  }

  @Test
  void 有邮箱每天2000_不标首笔核实() {
    long uid = tg("5000", "tgmail" + System.nanoTime() + "@example.com");
    long wid = activeAddress(uid);
    assertThat(code(() -> withdraws.submit(uid, String.valueOf(wid), "1500", pin(uid), "WEB"))).isEqualTo("OK");
    assertThat(code(() -> withdraws.submit(uid, String.valueOf(wid), "600", pin(uid), "WEB"))).isEqualTo("TG_DAILY_LIMIT");
    assertThat(jdbc.queryForObject("SELECT first_check FROM wallet_withdrawal WHERE user_id = ?", Boolean.class, uid)).isFalse();
  }

  @Test
  void 当日额度并发提交不超() throws Exception {
    long uid = tg("5000", null);
    long wid = activeAddress(uid);
    List<PinInput> pins = new ArrayList<>();
    for (int i = 0; i < 6; i++) pins.add(pin(uid));
    ExecutorService pool = Executors.newFixedThreadPool(6);
    try {
      List<Callable<String>> calls = new ArrayList<>();
      for (PinInput p : pins) calls.add(() -> code(() -> withdraws.submit(uid, String.valueOf(wid), "200", p, "WEB")));
      List<String> out = new ArrayList<>();
      for (Future<String> f : pool.invokeAll(calls)) out.add(f.get());
      assertThat(out).filteredOn("OK"::equals).hasSize(2);
      assertThat(out).filteredOn("TG_DAILY_LIMIT"::equals).hasSize(4);
    } finally {
      pool.shutdownNow();
    }
    assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM wallet_withdrawal WHERE user_id = ?", BigDecimal.class, uid)).isEqualByComparingTo("400");
  }

  @Test
  void 冻结提现时加白名单和提现都不行() {
    long uid = tg("1000", null);
    long wid = activeAddress(uid);
    jdbc.update("INSERT INTO account_withdraw_freeze (user_id, source, reason, created_at) VALUES (?, 'PIN_LOCKS', 'x', NOW(3))", uid);
    assertThat(code(() -> withdraws.submit(uid, String.valueOf(wid), "100", pin(uid), "WEB"))).isEqualTo("WITHDRAW_FROZEN");
    assertThat(code(() -> whitelist.add(uid, "ERC20", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", "y", pin(uid)))).isEqualTo("WITHDRAW_FROZEN");
  }

  @Test
  void 提现页显示TG规则() throws Exception {
    long uid = tg("1000", null);
    String body = mvc.perform(get("/api/wallet/withdraw-info").cookie(session(uid, "BOT"))).andReturn().getResponse().getContentAsString();
    assertThat(body).contains("\"tg\":{\"dailyLimit\":\"500.00\",\"hasEmail\":false,\"pinSet\":true,\"pinLockedUntil\":null,\"withdrawFrozen\":false,\"holdUntil\":null,\"enabled\":true}");
  }
}
