package app.trading.account.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import app.trading.account.api.AccountApi;
import app.trading.account.api.TelegramAccountApi;
import app.trading.account.api.TelegramAccountApi.TgAccount;
import app.trading.support.FixedClockConfig;
import app.trading.support.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 退路：一次性登录链接（FX_TG_LOGIN_MODE=link，TG 机器人第 2 批）：只能用一次（并发也只有一次成功）、60 秒过期、
 * 冻结和安全事件照样拦；这个模式下 login_url 落地页关闭。
 */
@SpringBootTest(properties = {"trading.telegram.login-mode=link"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class TelegramWebLinkTest {

  @Autowired MockMvc mvc;
  @Autowired TelegramAccountApi telegram;
  @Autowired AccountApi account;
  @Autowired TelegramWebLogin login;
  @Autowired Clock clock;

  @AfterEach
  void resetClock() {
    ((MutableClock) clock).reset();
  }

  MockHttpServletResponse use(String token) throws Exception {
    return mvc.perform(post("/api/tg/link").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}")).andReturn().getResponse();
  }

  @Test
  void 链接换会话_只能用一次_直达页面() throws Exception {
    TgAccount a = telegram.openAccount(TelegramWebLoginTest.newTgId(), null);
    String token = telegram.createLoginLink(a.userId(), "/positions");
    MockHttpServletResponse r = use(token);
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat(r.getContentAsString()).isEqualTo("{\"next\":\"/positions\"}");
    assertThat(mvc.perform(get("/api/me/session").cookie(r.getCookie("fx_session"))).andReturn().getResponse().getStatus()).isEqualTo(200);
    MockHttpServletResponse again = use(token);
    assertThat(again.getStatus()).isEqualTo(400);
    assertThat(again.getContentAsString()).contains("TG_LOGIN_EXPIRED");
    assertThat(again.getCookie("fx_session")).isNull();
  }

  @Test
  void 并发两次只有一次成功() throws Exception {
    TgAccount a = telegram.openAccount(TelegramWebLoginTest.newTgId(), null);
    String token = telegram.createLoginLink(a.userId(), "/");
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Callable<TelegramWebLogin.Outcome>> calls = new ArrayList<>();
      for (int i = 0; i < 4; i++) calls.add(() -> login.link(token, "203.0.113.9", "ua").outcome());
      List<TelegramWebLogin.Outcome> outcomes = new ArrayList<>();
      for (Future<TelegramWebLogin.Outcome> f : pool.invokeAll(calls)) outcomes.add(f.get());
      assertThat(outcomes).filteredOn(o -> o == TelegramWebLogin.Outcome.OK).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void 超过60秒失效_乱填的失效_冻结拦() throws Exception {
    TgAccount a = telegram.openAccount(TelegramWebLoginTest.newTgId(), null);
    String token = telegram.createLoginLink(a.userId(), "/");
    ((MutableClock) clock).advance(Duration.ofSeconds(61));
    assertThat(use(token).getContentAsString()).contains("TG_LOGIN_EXPIRED");
    assertThat(use("not-a-real-token").getContentAsString()).contains("TG_LOGIN_EXPIRED");

    String t2 = telegram.createLoginLink(a.userId(), "/");
    ((MutableClock) clock).advance(Duration.ofSeconds(1));
    account.freeze(a.userId());
    assertThat(use(t2).getContentAsString()).contains("TG_LOGIN_FROZEN");
    account.unfreeze(a.userId());
  }

  @Test
  void 退路模式下一键登录落地页关闭() throws Exception {
    assertThat(mvc.perform(get("/api/tg/login").param("id", "1").param("hash", "x")).andReturn().getResponse().getHeader("Location"))
        .isEqualTo("/tg?r=disabled");
    assertThat(mvc.perform(get("/api/auth/options")).andReturn().getResponse().getContentAsString()).contains("\"loginMode\":\"link\"");
  }
}
