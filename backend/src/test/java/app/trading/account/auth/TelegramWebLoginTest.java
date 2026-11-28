package app.trading.account.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import app.trading.account.api.AccountApi;
import app.trading.account.api.TelegramAccountApi;
import app.trading.account.api.TelegramAccountApi.TgAccount;
import app.trading.support.FixedClockConfig;
import app.trading.support.MutableClock;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 从机器人一键登录（TG 机器人第 2 批，技术方案 12 页、05 页第 5 节必测）：hash 被篡改拒绝、auth_date 超过 60 秒拒绝、
 * 同一个 hash 第二次用（重放、并发两次）只有一次成功、拒绝授权显示提示页、日志里不出现 id 和 hash；机器人会话（闲置 30 分钟、冻结作废）；
 * 第一阶段只走 Telegram（注册、邮箱登录、忘记密码关闭）。机器人令牌是测试假值，Telegram 指向一个连不上的地址（不连真实 Telegram）。
 */
@SpringBootTest(properties = {"trading.telegram.token=" + TelegramWebLoginTest.TOKEN, "trading.telegram.username=TradingAssistTestBot",
    "trading.telegram.api-url=http://127.0.0.1:1", "trading.auth.phase1-telegram-only=true",
    // 这个类测 login_url 一键登录；验收反馈方案 ② 以后默认是 link，这里明确选 login-url
    "trading.telegram.login-mode=login-url"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class TelegramWebLoginTest {

  static final String TOKEN = "777000:WEBLOGIN-test-token-not-real";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired TelegramAccountApi telegram;
  @Autowired AccountApi account;
  @Autowired TelegramWebLogin login;
  @Autowired Clock clock;
  @Autowired SessionService sessions;
  @Autowired app.trading.common.config.SystemConfig config;

  @AfterEach
  void resetClock() {
    ((MutableClock) clock).reset();
  }

  static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();

  static long newTgId() {
    return 6_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
  }

  /** 照 Telegram 的做法签名（data-check-string 按字母排序、换行连接；密钥 = SHA-256(令牌)） */
  static String sign(Map<String, String> fields, String token) throws Exception {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> e : new TreeMap<>(fields).entrySet()) {
      if (!sb.isEmpty()) sb.append('\n');
      sb.append(e.getKey()).append('=').append(e.getValue());
    }
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8)));
  }

  /** Telegram 加在网址后面的参数（已签名），authDateOffset 秒（负数 = 以前） */
  Map<String, String> telegramParams(long tgId, long authDateOffset) throws Exception {
    Map<String, String> f = new LinkedHashMap<>();
    f.put("id", String.valueOf(tgId));
    // 名字每次不一样：同一秒里点两次按钮，Telegram 给的 hash 也不一样（真实情况下 auth_date 不同）
    f.put("first_name", "San" + SEQ.incrementAndGet());
    f.put("username", "san_" + tgId);
    f.put("auth_date", String.valueOf(clock.instant().getEpochSecond() + authDateOffset));
    f.put("hash", sign(f, TOKEN));
    return f;
  }

  MockHttpServletResponse open(Map<String, String> params, String next) throws Exception {
    MockHttpServletRequestBuilder b = get("/api/tg/login").with(r -> {
      r.setRemoteAddr("203.0.113.9");
      return r;
    }).header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Telegram-iOS/11.0");
    if (next != null) b = b.param(TelegramWebLogin.NEXT, next);
    for (Map.Entry<String, String> e : params.entrySet()) b = b.param(e.getKey(), e.getValue());
    return mvc.perform(b).andReturn().getResponse();
  }

  static Cookie cookieOf(MockHttpServletResponse r) {
    return r.getCookie("fx_session");
  }

  @Test
  void 验过身份建机器人会话_302到干净网址_不再发刚在浏览器里登录的提醒_会话标出从机器人来(CapturedOutput out) throws Exception {
    long tgId = newTgId();
    TgAccount a = telegram.openAccount(tgId, null);
    Map<String, String> p = telegramParams(tgId, -5);
    MockHttpServletResponse r = open(p, "/positions");
    assertThat(r.getStatus()).isEqualTo(302);
    assertThat(r.getHeader("Location")).isEqualTo("/positions");
    assertThat(r.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(r.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    assertThat(r.getHeader("Set-Cookie")).contains("HttpOnly").contains("SameSite=Strict").contains("Max-Age=604800");
    Cookie c = cookieOf(r);
    assertThat(mvc.perform(get("/api/me/session").cookie(c)).andReturn().getResponse().getContentAsString())
        .contains("\"userId\":\"" + a.userId() + "\"").contains("\"email\":null").contains("\"fromTelegram\":true");
    assertThat(jdbc.queryForObject("SELECT kind FROM account_session WHERE user_id = ? ORDER BY id DESC LIMIT 1", String.class, a.userId())).isEqualTo("BOT");
    // 11/28 Amos 定：机器人会话 7 天滑动有效期，没有最长时间（以前是闲置 30 分钟、最长 12 小时）
    assertThat(jdbc.queryForObject("SELECT max_expires_at IS NULL FROM account_session WHERE user_id = ? ORDER BY id DESC LIMIT 1", Boolean.class, a.userId())).isTrue();
    assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(MINUTE, created_at, expires_at) FROM account_session WHERE user_id = ? ORDER BY id DESC LIMIT 1",
        Integer.class, a.userId())).isEqualTo(7 * 24 * 60);
    // 验收反馈 B（Amos：「每次使用后都发，太烦，不要发这个」）：从机器人进网页不再发"刚在浏览器里登录"；「退出所有网页会话」在机器人主菜单里
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE user_id = ? AND template_code = 'LOGIN_NEW_DEVICE'", Integer.class, a.userId())).isZero();
    assertThat(out.getAll()).as("日志里不出现身份参数").doesNotContain(p.get("hash")).doesNotContain(String.valueOf(tgId));
  }

  @Autowired AuthAccess authAccess;

  /** 验收反馈 1b：安全事件作废的会话回 401 SECURITY_LOGOUT（网页去"回机器人重新授权"的提示页）；别的原因作废的照旧 UNAUTHORIZED */
  @Test
  void 安全事件作废的会话回SECURITY_LOGOUT_别的作废照旧UNAUTHORIZED() throws Exception {
    long tgId = newTgId();
    TgAccount a = telegram.openAccount(tgId, null);
    Cookie c = cookieOf(open(telegramParams(tgId, -1), null));
    assertThat(mvc.perform(get("/api/me/session").cookie(c)).andReturn().getResponse().getStatus()).isEqualTo(200);
    authAccess.securityEvent(a.userId(), app.trading.account.api.SecurityEvent.PIN_SET);
    MockHttpServletResponse r = mvc.perform(get("/api/me/session").cookie(c)).andReturn().getResponse();
    assertThat(r.getStatus()).isEqualTo(401);
    assertThat(r.getContentAsString()).contains("\"code\":\"SECURITY_LOGOUT\"").contains("重新授权");
    assertThat(jdbc.queryForObject("SELECT revoke_reason FROM account_session WHERE user_id = ? ORDER BY id DESC LIMIT 1", String.class, a.userId())).isEqualTo("SECURITY");

    ((MutableClock) clock).advance(Duration.ofSeconds(2));
    telegram.reauthorize(a.userId());
    Cookie c2 = cookieOf(open(telegramParams(tgId, -1), null));
    sessions.revokeAll(a.userId(), null); // 例如「不是我，退出所有网页会话」：不是安全事件
    MockHttpServletResponse r2 = mvc.perform(get("/api/me/session").cookie(c2)).andReturn().getResponse();
    assertThat(r2.getStatus()).isEqualTo(401);
    assertThat(r2.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    assertThat(mvc.perform(get("/api/me/session").cookie(new Cookie("fx_session", "no-such-token"))).andReturn().getResponse().getContentAsString())
        .contains("\"code\":\"UNAUTHORIZED\"");
  }

  @Test
  void hash被篡改_拒绝() throws Exception {
    long tgId = newTgId();
    telegram.openAccount(tgId, null);
    Map<String, String> p = telegramParams(tgId, 0);
    p.put("first_name", "Mallory");
    MockHttpServletResponse r = open(p, "/positions");
    assertThat(r.getHeader("Location")).isEqualTo("/tg?r=invalid");
    assertThat(cookieOf(r)).isNull();
    // 换成别人的编号也不行
    Map<String, String> q = telegramParams(tgId, 0);
    q.put("id", String.valueOf(tgId + 1));
    assertThat(open(q, null).getHeader("Location")).isEqualTo("/tg?r=invalid");
    // 用别的令牌签的也不行
    Map<String, String> w = telegramParams(tgId, 0);
    w.remove("hash");
    w.put("hash", sign(w, "111:other-token"));
    assertThat(open(w, null).getHeader("Location")).isEqualTo("/tg?r=invalid");
  }

  @Test
  void auth_date超过60秒_拒绝_太超前也拒绝() throws Exception {
    long tgId = newTgId();
    telegram.openAccount(tgId, null);
    assertThat(open(telegramParams(tgId, -61), null).getHeader("Location")).isEqualTo("/tg?r=expired");
    assertThat(open(telegramParams(tgId, 31), null).getHeader("Location")).isEqualTo("/tg?r=expired");
    assertThat(open(telegramParams(tgId, -59), null).getHeader("Location")).isEqualTo("/");
  }

  @Test
  void 同一个hash只能用一次_重放拒绝() throws Exception {
    long tgId = newTgId();
    telegram.openAccount(tgId, null);
    Map<String, String> p = telegramParams(tgId, 0);
    assertThat(open(p, "/markets").getHeader("Location")).isEqualTo("/markets");
    MockHttpServletResponse again = open(p, "/markets");
    assertThat(again.getHeader("Location")).isEqualTo("/tg?r=used");
    assertThat(cookieOf(again)).isNull();
  }

  @Test
  void 同一个hash并发两次_只有一次成功() throws Exception {
    long tgId = newTgId();
    telegram.openAccount(tgId, null);
    Map<String, String> p = telegramParams(tgId, 0);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Callable<TelegramWebLogin.Outcome>> calls = new ArrayList<>();
      for (int i = 0; i < 4; i++) calls.add(() -> login.loginUrl(p, "203.0.113.9", "ua").outcome());
      List<TelegramWebLogin.Outcome> outcomes = new ArrayList<>();
      for (Future<TelegramWebLogin.Outcome> f : pool.invokeAll(calls)) outcomes.add(f.get());
      assertThat(outcomes).filteredOn(o -> o == TelegramWebLogin.Outcome.OK).hasSize(1);
      assertThat(outcomes).filteredOn(o -> o == TelegramWebLogin.Outcome.USED).hasSize(3);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void 客户拒绝授权_打开的是不带身份的网址_显示提示页() throws Exception {
    MockHttpServletResponse r = open(Map.of(), "/positions");
    assertThat(r.getStatus()).isEqualTo(302);
    assertThat(r.getHeader("Location")).isEqualTo("/tg?r=refused");
  }

  @Test
  void 没开户_冻结_授权后有安全事件_都不给会话() throws Exception {
    long none = newTgId();
    assertThat(open(telegramParams(none, 0), null).getHeader("Location")).isEqualTo("/tg?r=no_account");

    long frozenTg = newTgId();
    TgAccount f = telegram.openAccount(frozenTg, null);
    ((MutableClock) clock).advance(Duration.ofSeconds(1));
    account.freeze(f.userId());
    assertThat(open(telegramParams(frozenTg, 0), null).getHeader("Location")).isEqualTo("/tg?r=frozen");
    account.unfreeze(f.userId());
    assertThat(open(telegramParams(frozenTg, 0), null).getHeader("Location")).as("解冻也是安全事件：先回机器人重新授权").isEqualTo("/tg?r=reauth");
    telegram.reauthorize(f.userId());
    ((MutableClock) clock).advance(Duration.ofSeconds(1));
    assertThat(open(telegramParams(frozenTg, 0), null).getHeader("Location")).isEqualTo("/");
  }

  @Test
  void 登录后打开的页面只认站内路径() {
    assertThat(TelegramWebLogin.safeNext("/positions")).isEqualTo("/positions");
    assertThat(TelegramWebLogin.safeNext("/positions?pid=123")).isEqualTo("/positions?pid=123");
    for (String bad : new String[] {"//evil.example", "https://evil.example", "/\\evil", "javascript:alert(1)", null, "", "/a b"}) {
      assertThat(TelegramWebLogin.safeNext(bad)).as(String.valueOf(bad)).isEqualTo("/");
    }
  }

  @Test
  void 机器人会话_7天滑动有效期_用着就续_Cookie跟着续_过期失效_冻结立即失效() throws Exception {
    long tgId = newTgId();
    TgAccount a = telegram.openAccount(tgId, null);
    Cookie c = cookieOf(open(telegramParams(tgId, 0), null));
    assertThat(mvc.perform(get("/api/me/session").cookie(c)).andReturn().getResponse().getStatus()).isEqualTo(200);
    // 11/28：6 天没用、还剩 1 天到期 → 这次用了就续到"现在 + 7 天"，没有最长时间；Cookie 也续成 7 天
    jdbc.update("UPDATE account_session SET last_seen_at = DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 6 DAY), expires_at = DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 1 DAY) "
        + "WHERE user_id = ? AND kind = 'BOT'", a.userId());
    MockHttpServletResponse used = mvc.perform(get("/api/me/session").cookie(c)).andReturn().getResponse();
    assertThat(used.getStatus()).isEqualTo(200);
    assertThat(used.getHeader("Set-Cookie")).as("Cookie 跟着续").contains("fx_session=" + c.getValue()).contains("Max-Age=604800").contains("HttpOnly")
        .contains("SameSite=Strict").contains("Path=/api");
    assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(HOUR, UTC_TIMESTAMP(3), expires_at) FROM account_session WHERE user_id = ? AND kind = 'BOT'", Integer.class,
        a.userId())).as("续到现在 + 7 天").isBetween(7 * 24 - 1, 7 * 24);
    MockHttpServletResponse again = mvc.perform(get("/api/me/session").cookie(c)).andReturn().getResponse();
    assertThat(again.getHeader("Set-Cookie")).as("1 分钟内再用不重复续").isNull();
    jdbc.update("UPDATE account_session SET expires_at = DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE user_id = ? AND kind = 'BOT'", a.userId());
    assertThat(mvc.perform(get("/api/me/session").cookie(c)).andReturn().getResponse().getStatus()).as("7 天没用就过期").isEqualTo(401);

    Cookie c2 = cookieOf(open(telegramParams(tgId, 0), null));
    assertThat(mvc.perform(get("/api/me/session").cookie(c2)).andReturn().getResponse().getStatus()).isEqualTo(200);
    ((MutableClock) clock).advance(Duration.ofSeconds(1));
    account.freeze(a.userId());
    assertThat(mvc.perform(get("/api/me/session").cookie(c2)).andReturn().getResponse().getStatus()).isEqualTo(401);
    account.unfreeze(a.userId());
  }

  @Test
  void 不是我_退出所有网页会话() throws Exception {
    long tgId = newTgId();
    TgAccount a = telegram.openAccount(tgId, null);
    Cookie c = cookieOf(open(telegramParams(tgId, 0), null));
    telegram.logoutAllWeb(a.userId());
    assertThat(mvc.perform(get("/api/me/session").cookie(c)).andReturn().getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void 第一阶段只走Telegram_注册邮箱登录忘记密码都关闭() throws Exception {
    for (String path : new String[] {"/api/auth/register", "/api/auth/login", "/api/auth/email-code", "/api/auth/password/reset"}) {
      MockHttpServletResponse r = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse();
      assertThat(r.getStatus()).as(path).isEqualTo(403);
      assertThat(r.getContentAsString()).as(path).contains("TELEGRAM_ONLY");
    }
    assertThat(mvc.perform(get("/api/auth/options")).andReturn().getResponse().getContentAsString())
        .isEqualTo("{\"telegramOnly\":true,\"botUsername\":\"TradingAssistTestBot\",\"loginMode\":\"login-url\",\"emergencyLogin\":false,\"loginBotUsername\":null}");
    // 一次性链接只在退路模式开
    assertThat(mvc.perform(post("/api/tg/link").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"x\"}")).andReturn().getResponse()
        .getContentAsString()).contains("TG_LOGIN_DISABLED");
  }

  @Test
  void 设备大致信息和IP只露一部分() {
    assertThat(TelegramWebLogin.device("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Telegram-iOS")).isEqualTo("iPhone · Telegram 内置浏览器");
    assertThat(TelegramWebLogin.device("Mozilla/5.0 (Linux; Android 14) Chrome/130.0 Mobile Safari/537.36")).isEqualTo("安卓 · Chrome");
    assertThat(TelegramWebLogin.device(null)).isEqualTo("未知设备");
    assertThat(TelegramWebLogin.maskIp("203.0.113.9")).isEqualTo("203.0.113.*");
    assertThat(TelegramWebLogin.maskIp("2001:db8:1:2::5")).isEqualTo("2001:db8:1:…");
  }

  MockHttpServletResponse write(String path, Cookie c, String body) throws Exception {
    return mvc.perform(post(path).cookie(c).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
  }

  /** 🔴B2-1（TB-1"TG 提现默认关"）：有 TG 账户的客户，机器人会话、网页会话都不能加白名单、提交提现 */
  @Test
  void TG客户提现默认关_机器人会话和网页会话都拒绝_打开后不再拦() throws Exception {
    assertThat(config.text(app.trading.common.config.ConfigKey.TELEGRAM_WITHDRAW_ENABLED, null)).as("迁移 V37 默认关").isEqualTo("false");
    long tgId = newTgId();
    TgAccount a = telegram.openAccount(tgId, null);
    Cookie bot = cookieOf(open(telegramParams(tgId, 0), null));
    Cookie web = new Cookie("fx_session", sessions.create(a.userId(), "203.0.113.9", "test"));
    String addr = "{\"network\":\"TRC20\",\"address\":\"TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t\",\"label\":\"x\"}";
    for (Cookie c : new Cookie[] {bot, web}) {
      MockHttpServletResponse w = write("/api/wallet/whitelist", c, addr);
      assertThat(w.getStatus()).isEqualTo(403);
      assertThat(w.getContentAsString()).contains("TG_WITHDRAW_DISABLED").contains("Telegram 账户提现暂未开放");
      MockHttpServletResponse s = write("/api/wallet/withdrawals", c, "{\"whitelistId\":\"1\",\"amount\":\"10\"}");
      assertThat(s.getStatus()).isEqualTo(403);
      assertThat(s.getContentAsString()).contains("TG_WITHDRAW_DISABLED");
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_whitelist WHERE user_id = ?", Integer.class, a.userId())).isZero();
    // 打开以后（第 4 批验收后由 Amos 打开）：不再被这道闸拦（后面照常走白名单的其他规则）
    config.setBySystem(app.trading.common.config.ConfigKey.TELEGRAM_WITHDRAW_ENABLED, "true", java.time.LocalDateTime.now());
    try {
      assertThat(write("/api/wallet/whitelist", web, addr).getContentAsString()).doesNotContain("TG_WITHDRAW_DISABLED");
    } finally {
      config.setBySystem(app.trading.common.config.ConfigKey.TELEGRAM_WITHDRAW_ENABLED, "false", java.time.LocalDateTime.now());
    }
  }
}
