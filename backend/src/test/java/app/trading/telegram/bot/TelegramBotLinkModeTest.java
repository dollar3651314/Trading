package app.trading.telegram.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import app.trading.account.api.TelegramAccountApi;
import app.trading.account.api.TelegramAccountApi.TgAccount;
import app.trading.support.FixedClockConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import app.trading.common.config.ConfigKey;
import app.trading.common.config.SystemConfig;

/**
 * 一次性链接模式（FX_TG_LOGIN_MODE=link，默认；11/28 Amos 定的最终做法）的机器人一侧：
 * <ul>
 *   <li>平时的菜单、提醒里进网页的按钮都是<b>普通网址按钮、不带令牌</b>（{@code ?from=tg}），7 天内用过网页就直接进入；</li>
 *   <li>网页没有会话时让客户回机器人：{@code /start open}（安全事件后 {@code /start reauth}）→ 机器人发<b>一整套新菜单</b>，「打开交易」换成
 *       60 秒一次性链接「进入交易 ›」（令牌在 # 后面），别的按钮照常——客户只要看最新这一条；</li>
 *   <li>旧消息上以前的回调按钮 {@code open:…}：给<b>点的人</b>（回调里的 from）发他自己的一整套新菜单，转发来的消息也拿不到别人的令牌；</li>
 *   <li>「退出所有网页会话」作废全部网页会话。</li>
 * </ul>
 * 本机假 Telegram，不连真实 Telegram。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
@DirtiesContext
class TelegramBotLinkModeTest {

  static final String TOKEN = "556000:LINKMODE-test-token-not-real";
  static final FakeTelegram tg;

  static {
    try {
      tg = new FakeTelegram(TOKEN, "TradingAssistTestBot");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void telegram(DynamicPropertyRegistry r) {
    r.add("trading.telegram.token", () -> TOKEN);
    r.add("trading.telegram.username", () -> "TradingAssistTestBot");
    r.add("trading.telegram.api-url", tg::url);
    r.add("trading.telegram.poll-timeout", () -> "1s");
    r.add("trading.telegram.per-chat-interval", () -> "0s");
    r.add("trading.telegram.web-url", () -> "https://trade.example.test/");
    // 不写 login-mode：默认就是 link
  }

  @AfterAll
  static void close() {
    tg.close();
  }

  @Autowired TelegramAccountApi accounts;
  @Autowired SystemConfig config;
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired app.trading.notification.api.TelegramSender sender;

  @BeforeEach
  void open() {
    config.setBySystem(ConfigKey.TELEGRAM_ENABLED, "true", LocalDateTime.now());
  }

  static long newId() {
    return 7_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
  }

  List<FakeTelegram.Call> sent(long chat, int n) throws InterruptedException {
    return tg.await("sendMessage", c -> c.body().path("chat_id").asString("").equals(String.valueOf(chat)), n, Duration.ofSeconds(15));
  }

  static final Pattern LINK = Pattern.compile("^https://trade\\.example\\.test/tg-link#t=([A-Za-z0-9_-]{43})$");

  /** 键盘里每颗按钮：文字|回调数据，网址按钮写 url=网址 */
  static List<String> layout(tools.jackson.databind.JsonNode msgBody) {
    List<String> out = new ArrayList<>();
    for (tools.jackson.databind.JsonNode r : msgBody.path("reply_markup").path("inline_keyboard")) {
      for (tools.jackson.databind.JsonNode b : r) {
        out.add(b.path("text").asString("") + "|" + (b.has("url") ? "url=" + b.path("url").asString("") : b.path("callback_data").asString("")));
      }
    }
    return out;
  }

  static String token(List<String> layout) {
    for (String b : layout) {
      if (b.startsWith(UpdateHandler.TEXT_ENTER + "|url=")) {
        Matcher m = LINK.matcher(b.substring((UpdateHandler.TEXT_ENTER + "|url=").length()));
        assertThat(m.matches()).as(b).isTrue();
        return m.group(1);
      }
    }
    throw new AssertionError("没有「进入交易 ›」：" + layout);
  }

  MockHttpServletResponse redeem(String token) throws Exception {
    return mvc.perform(post("/api/tg/link").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}")).andReturn().getResponse();
  }

  @Test
  void 平时的菜单和提醒_进网页的按钮是普通网址_不带令牌() throws Exception {
    long id = newId();
    TgAccount a = accounts.openAccount(id, null);
    tg.push(tg.text(id, null, "/start"));
    List<String> menu = layout(sent(id, 1).getFirst().body());
    assertThat(menu).contains("打开交易|url=https://trade.example.test/trade?from=tg", "行情|url=https://trade.example.test/markets?from=tg",
        "持仓|url=https://trade.example.test/positions?from=tg", "充值|url=https://trade.example.test/assets?from=tg",
        "提现|url=https://trade.example.test/assets/withdraw?from=tg");
    assertThat(menu).noneMatch(b -> b.contains("#t=") || b.contains("login_url") || b.contains("open:"));
    int before = jdbc.queryForObject("SELECT COUNT(*) FROM account_tg_link_token WHERE user_id = ?", Integer.class, a.userId());
    assertThat(before).as("平时的菜单不做令牌").isZero();

    sender.send(a.userId(), "成交提醒：EURUSD 买入 0.1 手", app.trading.notification.api.TelegramSender.Priority.NORMAL, "/positions?pid=9", null);
    assertThat(layout(sent(id, 2).get(1).body())).containsExactly("查看|url=https://trade.example.test/positions?pid=9&from=tg");
  }

  @Test
  void start_open_发一整套新菜单_打开交易换成一次性链接_别的照常_令牌换会话7天_只能用一次() throws Exception {
    long id = newId();
    TgAccount a = accounts.openAccount(id, null);
    tg.push(tg.text(id, null, "/start open"));
    FakeTelegram.Call m = sent(id, 1).getFirst();
    List<String> menu = layout(m.body());
    assertThat(m.body().path("text").asString("")).startsWith("客户号 #" + a.userId()).contains("60 秒").contains("7 天");
    assertThat(menu.getFirst()).startsWith(UpdateHandler.TEXT_ENTER + "|url=");
    assertThat(menu).contains("行情|url=https://trade.example.test/markets?from=tg", "提现|url=https://trade.example.test/assets/withdraw?from=tg")
        .contains("冻结提现|wdfreeze", "当前网页会话|sessions", "退出所有网页会话|logoutall").doesNotContain("打开交易|url=https://trade.example.test/trade?from=tg");
    String t = token(menu);
    MockHttpServletResponse r = redeem(t);
    assertThat(r.getContentAsString()).isEqualTo("{\"next\":\"/trade\"}");
    assertThat(r.getHeader("Set-Cookie")).contains("Max-Age=604800");
    assertThat(mvc.perform(get("/api/me/session").cookie(r.getCookie("fx_session"))).andReturn().getResponse().getContentAsString())
        .contains("\"userId\":\"" + a.userId() + "\"").contains("\"fromTelegram\":true");
    assertThat(redeem(t).getContentAsString()).as("只能用一次").contains("TG_LOGIN_EXPIRED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_tg_link_token WHERE token_hash = ?", Integer.class, t)).as("库里不存原值").isZero();

    // 安全事件后的深链接 /start reauth：不用重新授权时一样发带一次性链接的新菜单
    tg.push(tg.text(id, null, "/start reauth"));
    assertThat(layout(sent(id, 2).get(1).body()).getFirst()).startsWith(UpdateHandler.TEXT_ENTER);
  }

  @Test
  void 旧消息上的回调按钮_转发来的也只给点的人发他自己的新菜单_没开户的拿不到令牌() throws Exception {
    long owner = newId();
    accounts.openAccount(owner, null);
    long clicker = newId();
    TgAccount c = accounts.openAccount(clicker, null);
    long stranger = newId();
    int tokensBefore = jdbc.queryForObject("SELECT COUNT(*) FROM account_tg_link_token", Integer.class);

    // owner 的旧消息被转发到 clicker 那边，clicker 点了以前的回调按钮「持仓」（回调里 from 是 clicker、聊天是 owner）
    tg.push(tg.callback(clicker, null, owner, "private", "open:/positions"));
    FakeTelegram.Call reply = sent(clicker, 1).getFirst();
    assertThat(reply.body().path("text").asString("")).startsWith("客户号 #" + c.userId());
    String t = token(layout(reply.body()));
    assertThat(mvc.perform(get("/api/me/session").cookie(redeem(t).getCookie("fx_session"))).andReturn().getResponse().getContentAsString())
        .as("令牌是点的人自己的").contains("\"userId\":\"" + c.userId() + "\"");
    Thread.sleep(300);
    assertThat(tg.calls().stream().filter(x -> x.body().path("chat_id").asString("").equals(String.valueOf(owner)))).as("不给原主发、不改原主的消息").isEmpty();

    tg.push(tg.callback(stranger, null, owner, "private", "open:/positions"));
    assertThat(sent(stranger, 1).getFirst().body().path("text").asString("")).as("没开户：只提示开户").containsAnyOf("暂未开放开户", "请先发 /start 授权开户");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_tg_link_token", Integer.class)).as("只做了 clicker 那一个令牌").isEqualTo(tokensBefore + 1);
  }

  @Test
  void 退出所有网页会话_作废全部网页会话() throws Exception {
    long id = newId();
    TgAccount a = accounts.openAccount(id, null);
    tg.push(tg.text(id, null, "/start open"));
    MockHttpServletResponse r = redeem(token(layout(sent(id, 1).getFirst().body())));
    assertThat(mvc.perform(get("/api/me/session").cookie(r.getCookie("fx_session"))).andReturn().getResponse().getStatus()).isEqualTo(200);
    tg.push(tg.callback(id, null, id, "private", "logoutall"));
    assertThat(sent(id, 2).get(1).body().path("text").asString("")).startsWith("已退出所有网页会话");
    assertThat(mvc.perform(get("/api/me/session").cookie(r.getCookie("fx_session"))).andReturn().getResponse().getStatus()).isEqualTo(401);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_session WHERE user_id = ? AND revoked_at IS NULL", Integer.class, a.userId())).isZero();
  }
}
