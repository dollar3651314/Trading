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
 * 退路模式（FX_TG_LOGIN_MODE=link，TG 机器人第 2 批）的机器人一侧：主菜单是回调按钮；点「持仓」机器人现发一条一次性链接（令牌在 # 后面），
 * 网页用它换会话直达持仓；这条消息到时删掉；「不是我，退出所有网页会话」作废全部网页会话。本机假 Telegram，不连真实 Telegram。
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
    r.add("trading.telegram.login-mode", () -> "link");
  }

  @AfterAll
  static void close() {
    UpdateHandler.linkDeleteMillis = 60_000;
    tg.close();
  }

  @Autowired TelegramAccountApi accounts;
  @Autowired SystemConfig config;
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void open() {
    config.setBySystem(ConfigKey.TELEGRAM_ENABLED, "true", LocalDateTime.now());
    UpdateHandler.linkDeleteMillis = 300;
  }

  static long newId() {
    return 7_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
  }

  List<FakeTelegram.Call> sent(long chat, int n) throws InterruptedException {
    return tg.await("sendMessage", c -> c.body().path("chat_id").asString("").equals(String.valueOf(chat)), n, Duration.ofSeconds(15));
  }

  @Test
  void 主菜单是回调按钮_点了现发一次性链接_网页换会话直达_消息到时删掉() throws Exception {
    long id = newId();
    TgAccount a = accounts.openAccount(id, null);
    tg.push(tg.text(id, null, "/start"));
    String menu = sent(id, 1).getFirst().body().path("reply_markup").toString();
    assertThat(menu).contains("\"callback_data\":\"open:/trade\"").contains("\"callback_data\":\"open:/assets/withdraw\"").doesNotContain("login_url");

    tg.push(tg.callback(id, null, id, "private", "open:/positions"));
    FakeTelegram.Call link = sent(id, 2).get(1);
    assertThat(link.body().path("text").asString("")).contains("60 秒");
    String url = link.body().path("reply_markup").path("inline_keyboard").get(0).get(0).path("url").asString("");
    Matcher m = Pattern.compile("^https://trade\\.example\\.test/tg-link#t=([A-Za-z0-9_-]{43})$").matcher(url);
    assertThat(m.matches()).as(url).isTrue();

    MockHttpServletResponse r = mvc.perform(post("/api/tg/link").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + m.group(1) + "\"}"))
        .andReturn().getResponse();
    assertThat(r.getContentAsString()).isEqualTo("{\"next\":\"/positions\"}");
    assertThat(mvc.perform(get("/api/me/session").cookie(r.getCookie("fx_session"))).andReturn().getResponse().getStatus()).isEqualTo(200);
    assertThat(tg.await("deleteMessage", c -> c.body().path("chat_id").asString("").equals(String.valueOf(id)), 1, Duration.ofSeconds(5)))
        .as("链接消息到时删掉").hasSize(1);

    // 不是我：退出所有网页会话
    tg.push(tg.callback(id, null, id, "private", "logoutall"));
    assertThat(sent(id, 3).get(2).body().path("text").asString("")).startsWith("已退出所有网页会话");
    assertThat(mvc.perform(get("/api/me/session").cookie(r.getCookie("fx_session"))).andReturn().getResponse().getStatus()).isEqualTo(401);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_session WHERE user_id = ? AND revoked_at IS NULL", Integer.class, a.userId())).isZero();
  }
}
