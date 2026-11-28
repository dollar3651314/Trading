package app.trading.account.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import app.trading.account.api.TelegramAccountApi;
import app.trading.account.api.TelegramEmergencyApi;
import app.trading.common.health.AlertCheck;
import app.trading.support.FixedClockConfig;
import app.trading.support.MutableClock;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * 应急开关下"用 Telegram 登录"（TG 机器人第 5 批，技术方案 11 页第 3 节，TB-34）：开关关着时不显示、接口拒绝；打开后登录方式里有登录机器人；
 * 用<b>登录机器人</b>的令牌验签（交易机器人令牌签的不认）、auth_date 5 分钟、hash 只能用一次、只认 id、没开户 / 冻结拒绝；
 * 通过后记安全事件 TG_WEB_LOGIN（作废其他全部会话、24 小时不能提现）、建 EMERGENCY 会话、发安全提醒；关上开关（后台或服务器终端直接写库）这类会话马上失效；
 * 告警项 telegram.emergency 开着时 DOWN；第一阶段只走 Telegram 时照样可用。日志里不出现 id 和 hash。
 */
@SpringBootTest(properties = {"trading.telegram.token=" + EmergencyLoginTest.TRADE_TOKEN, "trading.telegram.username=TradingAssistTestBot",
    "trading.telegram.api-url=http://127.0.0.1:1", "trading.auth.phase1-telegram-only=true", "trading.telegram.login-bot.token=" + EmergencyLoginTest.LOGIN_TOKEN,
    "trading.telegram.login-bot.username=@TradingLoginTestBot"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class EmergencyLoginTest {

  static final String TRADE_TOKEN = "777001:TRADE-test-token-not-real";
  static final String LOGIN_TOKEN = "777002:LOGIN-test-token-not-real";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired TelegramAccountApi telegram;
  @Autowired TelegramEmergencyApi emergency;
  @Autowired EmergencySwitch sw;
  @Autowired AuthService auth;
  @Autowired Clock clock;
  @Autowired ObjectMapper json;
  @Autowired ApplicationContext ctx;

  @AfterEach
  void off() {
    if (emergency.state().on()) emergency.set(false, "admin:1", "test", "测试结束");
    ((MutableClock) clock).reset();
  }

  static long newTgId() {
    return 6_100_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
  }

  /** 登录小部件给的字段（已签名） */
  Map<String, String> widget(long tgId, long authDateOffset, String token) throws Exception {
    Map<String, String> f = new LinkedHashMap<>();
    f.put("id", String.valueOf(tgId));
    f.put("first_name", "Li" + ThreadLocalRandom.current().nextInt(1_000_000));
    f.put("username", "li_" + tgId);
    f.put("auth_date", String.valueOf(clock.instant().getEpochSecond() + authDateOffset));
    f.put("hash", TelegramWebLoginTest.sign(f, token));
    return f;
  }

  MockHttpServletResponse login(Map<String, ?> body) throws Exception {
    return mvc.perform(post("/api/tg/emergency-login").with(r -> {
      r.setRemoteAddr("203.0.113.77");
      return r;
    }).header("User-Agent", "Mozilla/5.0 (Windows NT 10.0) Chrome/130.0").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
        .andReturn().getResponse();
  }

  String code(MockHttpServletResponse r) throws Exception {
    return r.getStatus() + " " + json.readTree(r.getContentAsString()).path("code").asString();
  }

  int session(Cookie c) throws Exception {
    return mvc.perform(get("/api/me/session").cookie(c)).andReturn().getResponse().getStatus();
  }

  long oldCustomer(long tgId) {
    long uid = telegram.openAccount(tgId, "li").userId();
    jdbc.update("UPDATE account_user SET created_at = ? WHERE id = ?", LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(30), uid);
    return uid;
  }

  Map<String, Object> options() throws Exception {
    return json.readValue(mvc.perform(get("/api/auth/options")).andReturn().getResponse().getContentAsString(), Map.class);
  }

  @Test
  void 开关关着_不显示用Telegram登录_接口拒绝() throws Exception {
    long tgId = newTgId();
    oldCustomer(tgId);
    assertThat(options()).containsEntry("telegramOnly", true).containsEntry("emergencyLogin", false).containsEntry("loginBotUsername", null);
    assertThat(code(login(widget(tgId, -5, LOGIN_TOKEN)))).isEqualTo("400 TG_EMERGENCY_DISABLED");
    assertThat(sw.evaluate()).singleElement().satisfies(i -> {
      assertThat(i.name()).isEqualTo("telegram.emergency");
      assertThat(i.ok()).isTrue();
    });
  }

  @Test
  void 打开后_登录机器人验签_建应急会话_安全事件作废其他会话_24小时不能提现_发提醒(CapturedOutput out) throws Exception {
    long tgId = newTgId();
    long uid = oldCustomer(tgId);
    jdbc.update("INSERT INTO account_session (user_id, token_hash, ip, user_agent, created_at, last_seen_at, expires_at) VALUES (?, ?, '203.0.113.9', 't', NOW(3), NOW(3), NOW(3) + INTERVAL 1 DAY)",
        uid, String.format("%064x", ThreadLocalRandom.current().nextLong(Long.MAX_VALUE)));
    long other = jdbc.queryForObject("SELECT MAX(id) FROM account_session WHERE user_id = ?", Long.class, uid);
    emergency.set(true, "admin:1", "amos", "机器人被封");
    sw.refresh();
    assertThat(options()).containsEntry("telegramOnly", true).containsEntry("emergencyLogin", true).containsEntry("loginBotUsername", "TradingLoginTestBot");
    assertThat(sw.evaluate().getFirst().ok()).as("开着时告警").isFalse();

    Map<String, String> w = widget(tgId, -30, LOGIN_TOKEN);
    MockHttpServletResponse r = login(w);
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    assertThat(json.readTree(r.getContentAsString()).get("withdrawAt").asString()).isEqualTo(now.plusHours(24).toInstant(ZoneOffset.UTC).toString());
    assertThat(r.getHeader("Set-Cookie")).contains("HttpOnly").contains("Max-Age=259200");
    Cookie c = r.getCookie("fx_session");
    assertThat(session(c)).isEqualTo(200);
    assertThat(jdbc.queryForObject("SELECT kind FROM account_session WHERE user_id = ? AND revoked_at IS NULL", String.class, uid)).isEqualTo("EMERGENCY");
    assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM account_session WHERE id = ?", Boolean.class, other)).as("安全事件作废其他会话").isTrue();
    assertThat(jdbc.queryForList("SELECT type FROM account_security_event WHERE user_id = ?", String.class, uid)).containsExactly("TG_WEB_LOGIN");
    Object gate = ctx.getBean(Class.forName("app.trading.wallet.withdraw.TelegramWithdrawGate"));
    assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(gate, "holdUntil", uid, now)).as("24 小时不能提现").isEqualTo(now.plusHours(24));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE user_id = ? AND template_code = 'TG_EMERGENCY_LOGIN'", Integer.class, uid)).isPositive();
    assertThat(code(login(w))).as("同一个 hash 只能用一次").isEqualTo("400 TG_EMERGENCY_USED");
    assertThat(out.getAll()).doesNotContain(String.valueOf(tgId)).doesNotContain(w.get("hash"));
  }

  @Test
  void 交易机器人令牌签的_篡改的_过期的_没开户的_冻结的_都拒绝() throws Exception {
    long tgId = newTgId();
    long uid = oldCustomer(tgId);
    emergency.set(true, "admin:1", "amos", "机器人被封");
    assertThat(code(login(widget(tgId, -5, TRADE_TOKEN)))).as("交易机器人的令牌签的不认").isEqualTo("400 TG_EMERGENCY_INVALID");
    Map<String, String> bad = widget(tgId, -5, LOGIN_TOKEN);
    bad.put("id", String.valueOf(newTgId()));
    assertThat(code(login(bad))).as("改了 id").isEqualTo("400 TG_EMERGENCY_INVALID");
    Map<String, Object> nested = new LinkedHashMap<>(widget(tgId, -5, LOGIN_TOKEN));
    nested.put("x", Map.of("a", 1));
    assertThat(code(login(nested))).isEqualTo("400 TG_EMERGENCY_INVALID");
    assertThat(code(login(widget(tgId, -301, LOGIN_TOKEN)))).as("5 分钟以前的").isEqualTo("400 TG_EMERGENCY_EXPIRED");
    assertThat(code(login(widget(tgId, 120, LOGIN_TOKEN)))).as("将来的").isEqualTo("400 TG_EMERGENCY_EXPIRED");
    assertThat(code(login(widget(newTgId(), -5, LOGIN_TOKEN)))).isEqualTo("400 TG_EMERGENCY_NO_ACCOUNT");
    auth.freeze(uid);
    try {
      assertThat(code(login(widget(tgId, -5, LOGIN_TOKEN)))).isEqualTo("400 TG_EMERGENCY_FROZEN");
    } finally {
      auth.unfreeze(uid);
    }
    // 数字的 id、auth_date（登录小部件给的是数字）照样能验
    Map<String, String> w = widget(tgId, -5, LOGIN_TOKEN);
    Map<String, Object> numeric = new LinkedHashMap<>(w);
    numeric.put("id", tgId);
    numeric.put("auth_date", Long.parseLong(w.get("auth_date")));
    assertThat(login(numeric).getStatus()).isEqualTo(200);
  }

  @Test
  void 关上开关_应急会话马上失效_服务器终端直接写库关的也一样() throws Exception {
    long tgId = newTgId();
    long uid = oldCustomer(tgId);
    emergency.set(true, "admin:1", "amos", "机器人被封");
    Cookie c = login(widget(tgId, -5, LOGIN_TOKEN)).getCookie("fx_session");
    assertThat(session(c)).isEqualTo(200);
    emergency.set(false, "admin:1", "amos", "机器人恢复了");
    assertThat(session(c)).isEqualTo(401);
    assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM account_session WHERE user_id = ? AND kind = 'EMERGENCY'", Boolean.class, uid)).isTrue();
    assertThat(code(login(widget(tgId, -4, LOGIN_TOKEN)))).isEqualTo("400 TG_EMERGENCY_DISABLED");

    // 服务器备用办法（sudo trading set TR_TG_EMERGENCY_LOGIN off）只写记录表，不经过应用：会话照样马上失效
    emergency.set(true, "admin:1", "amos", "又被封了");
    Cookie c2 = login(widget(tgId, -3, LOGIN_TOKEN)).getCookie("fx_session");
    assertThat(session(c2)).isEqualTo(200);
    jdbc.update("INSERT INTO account_tg_emergency_log (turned_on, actor, actor_name, reason, created_at) VALUES (0, 'server', 'server:root', '终端关', UTC_TIMESTAMP(3))");
    assertThat(session(c2)).isEqualTo(401);
    sw.refresh();
    assertThat(options()).containsEntry("emergencyLogin", false);
    assertThat(emergency.history(2)).extracting(TelegramEmergencyApi.Change::actor).containsExactly("server", "admin:1");
    assertThat(sw.evaluate().getFirst().ok()).isTrue();
  }

  /** #48 审查跟进：应急登录的会话照旧按网页会话 72 小时续期，不是机器人会话的 7 天 */
  @Test
  void 应急登录的会话续期是72小时_不是7天() throws Exception {
    long tgId = newTgId();
    long uid = oldCustomer(tgId);
    emergency.set(true, "admin:1", "amos", "机器人被封");
    MockHttpServletResponse r = login(widget(tgId, -5, LOGIN_TOKEN));
    assertThat(r.getHeader("Set-Cookie")).contains("Max-Age=259200");
    Cookie c = r.getCookie("fx_session");
    jdbc.update("UPDATE account_session SET last_seen_at = DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 1 HOUR), expires_at = DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 1 HOUR) "
        + "WHERE user_id = ? AND kind = 'EMERGENCY' AND revoked_at IS NULL", uid);
    MockHttpServletResponse used = mvc.perform(get("/api/me/session").cookie(c)).andReturn().getResponse();
    assertThat(used.getStatus()).isEqualTo(200);
    assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(HOUR, UTC_TIMESTAMP(3), expires_at) FROM account_session WHERE user_id = ? AND kind = 'EMERGENCY' AND revoked_at IS NULL",
        Integer.class, uid)).as("续到现在 + 72 小时").isBetween(71, 72);
    assertThat(used.getHeader("Set-Cookie")).as("应急会话不改 Cookie（只有机器人会话跟着续）").isNull();
  }

  @Test
  void 和现在一样时报EMERGENCY_UNCHANGED() {
    assertThat(emergency.state().on()).isFalse();
    try {
      emergency.set(false, "admin:1", "amos", "x");
      throw new AssertionError("应该报错");
    } catch (app.trading.common.error.BizException e) {
      assertThat(e.code()).isEqualTo("EMERGENCY_UNCHANGED");
    }
    ((MutableClock) clock).advance(Duration.ofMinutes(1));
  }

  @Test
  void 告警项只有一项且名字固定() {
    AlertCheck a = sw;
    assertThat(a.evaluate()).extracting(AlertCheck.Item::name).containsExactly("telegram.emergency");
  }
}
