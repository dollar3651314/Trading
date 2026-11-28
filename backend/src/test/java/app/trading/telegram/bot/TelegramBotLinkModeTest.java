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
 * 一次性链接模式（FX_TG_LOGIN_MODE=link，默认；11/28 Amos 定的做法，验收修复 5 补全）的机器人一侧：
 * <ul>
 *   <li>客户自己要菜单时（/start、/start open、/start open_<页面>、/start reauth、回调按钮）机器人发<b>一整套新菜单</b>：
 *       打开交易/行情/持仓/充值/提现<b>每个按钮都带自己的 60 秒一次性令牌</b>（{@code /tg-link#t=}，一次做一组，用掉一个同组的一起作废），
 *       点哪个都能登录并直达那一页；{@code open_<页面>} 指的那一页放第一个「进入持仓 ›」；</li>
 *   <li>机器人主动发的提醒照旧是<b>普通网址按钮、不带令牌</b>（{@code ?from=tg}），7 天内用过网页就直接进入；</li>
 *   <li>验收发现的死循环：退出后点普通按钮 → 「回到机器人继续」→ /start open_positions → 新菜单的「持仓」也要带令牌，登录后落在 /positions；</li>
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
    return token(layout, UpdateHandler.TEXT_ENTER);
  }

  /** 文字为 text 的按钮里的一次性令牌（按钮必须是 /tg-link#t= 链接） */
  static String token(List<String> layout, String text) {
    for (String b : layout) {
      if (b.startsWith(text + "|url=")) {
        Matcher m = LINK.matcher(b.substring((text + "|url=").length()));
        assertThat(m.matches()).as(b).isTrue();
        return m.group(1);
      }
    }
    throw new AssertionError("没有「" + text + "」：" + layout);
  }

  /** 新菜单里进网页的五个按钮都是带令牌的链接，第一个是 first */
  static void assertFreshMenu(List<String> menu, String first) {
    assertThat(menu.getFirst()).startsWith(first + "|url=");
    for (String text : List.of("打开交易", "行情", "持仓", "充值", "提现")) {
      String label = first.equals("进入" + (text.equals("打开交易") ? "交易" : text) + " ›") ? first : text;
      assertThat(LINK.matcher(menu.stream().filter(b -> b.startsWith(label + "|url=")).findFirst().orElseThrow(() -> new AssertionError(label + "：" + menu))
          .substring((label + "|url=").length())).matches()).as(label).isTrue();
    }
    assertThat(menu).noneMatch(b -> b.contains("?from=tg") || b.contains("login_url") || b.contains("open:"));
    assertThat(menu).contains("冻结提现|wdfreeze", "当前网页会话|sessions", "退出所有网页会话|logoutall");
  }

  MockHttpServletResponse redeem(String token) throws Exception {
    return mvc.perform(post("/api/tg/link").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}")).andReturn().getResponse();
  }

  @Test
  void 平时的_start也发带令牌的新菜单_五个按钮各一个令牌同一组_主动提醒仍是普通网址() throws Exception {
    long id = newId();
    TgAccount a = accounts.openAccount(id, null);
    tg.push(tg.text(id, null, "/start"));
    FakeTelegram.Call m = sent(id, 1).getFirst();
    List<String> menu = layout(m.body());
    assertFreshMenu(menu, UpdateHandler.TEXT_ENTER);
    assertThat(m.body().path("text").asString("")).contains("60 秒").contains("7 天");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_tg_link_token WHERE user_id = ?", Integer.class, a.userId())).as("一页一个令牌").isEqualTo(5);
    assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT group_id) FROM account_tg_link_token WHERE user_id = ? AND group_id IS NOT NULL", Integer.class, a.userId()))
        .as("同一组").isEqualTo(1);

    sender.send(a.userId(), "成交提醒：EURUSD 买入 0.1 手", app.trading.notification.api.TelegramSender.Priority.NORMAL, "/positions?pid=9", null);
    assertThat(layout(sent(id, 2).get(1).body())).containsExactly("查看|url=https://trade.example.test/positions?pid=9&from=tg");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_tg_link_token WHERE user_id = ?", Integer.class, a.userId())).as("提醒不做令牌").isEqualTo(5);
  }

  @Test
  void start_open_每个按钮的令牌直达自己那页_换会话7天_用掉一个同组其余一起作废() throws Exception {
    long id = newId();
    TgAccount a = accounts.openAccount(id, null);
    tg.push(tg.text(id, null, "/start open"));
    FakeTelegram.Call m = sent(id, 1).getFirst();
    List<String> menu = layout(m.body());
    assertThat(m.body().path("text").asString("")).startsWith("客户号 #" + a.userId()).contains("60 秒").contains("7 天");
    assertFreshMenu(menu, UpdateHandler.TEXT_ENTER);
    String trade = token(menu);
    String markets = token(menu, "行情");
    MockHttpServletResponse r = redeem(markets);
    assertThat(r.getContentAsString()).as("点「行情」落在行情页").isEqualTo("{\"next\":\"/markets\"}");
    assertThat(r.getHeader("Set-Cookie")).contains("Max-Age=604800");
    assertThat(mvc.perform(get("/api/me/session").cookie(r.getCookie("fx_session"))).andReturn().getResponse().getContentAsString())
        .contains("\"userId\":\"" + a.userId() + "\"").contains("\"fromTelegram\":true");
    assertThat(redeem(markets).getContentAsString()).as("只能用一次").contains("TG_LOGIN_EXPIRED");
    assertThat(redeem(trade).getContentAsString()).as("同一套菜单的其余令牌一起作废").contains("TG_LOGIN_EXPIRED");
    assertThat(redeem(token(menu, "提现")).getContentAsString()).contains("TG_LOGIN_EXPIRED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_tg_link_token WHERE token_hash = ?", Integer.class, markets)).as("库里不存原值").isZero();

    // 每一页的令牌各自指向自己那页（新发一套，逐个试一个就作废其余，所以每次重发）
    int k = 1;
    for (String[] p : new String[][] {{"打开交易", "/trade"}, {"持仓", "/positions"}, {"充值", "/assets"}, {"提现", "/assets/withdraw"}}) {
      tg.push(tg.text(id, null, "/start open"));
      k++;
      List<String> again = layout(sent(id, k).get(k - 1).body());
      String text = p[0].equals("打开交易") ? UpdateHandler.TEXT_ENTER : p[0];
      assertThat(redeem(token(again, text)).getContentAsString()).as(p[0]).isEqualTo("{\"next\":\"" + p[1] + "\"}");
    }

    // 安全事件后的深链接 /start reauth：不用重新授权时一样发带令牌的新菜单
    tg.push(tg.text(id, null, "/start reauth"));
    assertFreshMenu(layout(sent(id, k + 1).get(k).body()), UpdateHandler.TEXT_ENTER);
  }

  @Test
  void 死循环场景_退出后_start_open_positions_新菜单的持仓令牌登录并落在持仓页() throws Exception {
    long id = newId();
    TgAccount a = accounts.openAccount(id, null);
    // 先登录过，再「退出所有网页会话」——之后普通按钮（?from=tg）进网页没有会话，网页显示「回到机器人继续」，深链接 /start open_positions
    tg.push(tg.text(id, null, "/start"));
    MockHttpServletResponse first = redeem(token(layout(sent(id, 1).getFirst().body())));
    tg.push(tg.callback(id, null, id, "private", "logoutall"));
    assertThat(sent(id, 2).get(1).body().path("text").asString("")).startsWith("已退出所有网页会话");
    assertThat(mvc.perform(get("/api/me/session").cookie(first.getCookie("fx_session"))).andReturn().getResponse().getStatus()).isEqualTo(401);

    tg.push(tg.text(id, null, "/start open_positions"));
    List<String> menu = layout(sent(id, 3).get(2).body());
    assertFreshMenu(menu, "进入持仓 ›");
    assertThat(menu).as("持仓只出现一次（放在第一个）").noneMatch(b -> b.startsWith("持仓|"));
    MockHttpServletResponse r = redeem(token(menu, "进入持仓 ›"));
    assertThat(r.getContentAsString()).isEqualTo("{\"next\":\"/positions\"}");
    assertThat(mvc.perform(get("/api/me/session").cookie(r.getCookie("fx_session"))).andReturn().getResponse().getContentAsString())
        .contains("\"userId\":\"" + a.userId() + "\"");

    // 就算客户点的不是第一个，菜单里别的按钮（例如「行情」）也一样能登录——不会再回到「回到机器人继续」
    tg.push(tg.text(id, null, "/start open_positions"));
    assertThat(redeem(token(layout(sent(id, 4).get(3).body()), "行情")).getContentAsString()).isEqualTo("{\"next\":\"/markets\"}");
  }

  @Test
  void start参数里的页面只认白名单_不认识的退回默认菜单_reauth也能带页面() throws Exception {
    long id = newId();
    accounts.openAccount(id, null);
    tg.push(tg.text(id, null, "/start open_admin"));
    assertFreshMenu(layout(sent(id, 1).getFirst().body()), UpdateHandler.TEXT_ENTER);
    tg.push(tg.text(id, null, "/start open_withdraw"));
    List<String> w = layout(sent(id, 2).get(1).body());
    assertFreshMenu(w, "进入提现 ›");
    assertThat(redeem(token(w, "进入提现 ›")).getContentAsString()).isEqualTo("{\"next\":\"/assets/withdraw\"}");
    tg.push(tg.text(id, null, "/start reauth_markets"));
    assertFreshMenu(layout(sent(id, 3).get(2).body()), "进入行情 ›");
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
    List<String> menu = layout(reply.body());
    assertFreshMenu(menu, "进入持仓 ›");
    String t = token(menu, "进入持仓 ›");
    assertThat(mvc.perform(get("/api/me/session").cookie(redeem(t).getCookie("fx_session"))).andReturn().getResponse().getContentAsString())
        .as("令牌是点的人自己的").contains("\"userId\":\"" + c.userId() + "\"");
    Thread.sleep(300);
    assertThat(tg.calls().stream().filter(x -> x.body().path("chat_id").asString("").equals(String.valueOf(owner)))).as("不给原主发、不改原主的消息").isEmpty();

    tg.push(tg.callback(stranger, null, owner, "private", "open:/positions"));
    assertThat(sent(stranger, 1).getFirst().body().path("text").asString("")).as("没开户：只提示开户").containsAnyOf("暂未开放开户", "请先发 /start 授权开户");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_tg_link_token", Integer.class)).as("只做了 clicker 那一组（五页各一个）令牌").isEqualTo(tokensBefore + 5);
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
