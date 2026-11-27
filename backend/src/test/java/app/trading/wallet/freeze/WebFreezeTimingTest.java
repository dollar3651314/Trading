package app.trading.wallet.freeze;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import app.trading.account.api.TelegramAccountApi;
import app.trading.account.api.WithdrawPinApi;
import app.trading.common.captcha.Captchas;
import app.trading.notification.api.MailApi;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 网页"申请冻结提现"的最短响应时间和并发上限（4c 审查 🟡、4c 复审 🔴D1），用生产的 800 毫秒（其他测试调成 20 毫秒）：
 * 信息对不对、验证码对不对，响应都一样、都不少于 800 毫秒；补时间不占请求线程（异步）；等待中的提交超过上限马上 429；
 * 真实 Tomcat 只给 4 个请求线程，同时挂着 5 个提交时，别的接口照样马上回应。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"trading.wallet.freeze.min-response-ms=800", "trading.wallet.freeze.max-pending=6", "server.tomcat.threads.max=4",
    "server.tomcat.threads.min-spare=1"})
class WebFreezeTimingTest {

  @Autowired MockMvc mvc;
  @Autowired WebFreezeController controller;
  @Autowired TelegramAccountApi telegram;
  @Autowired WithdrawPinApi pins;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @MockitoBean MailApi mail;
  @LocalServerPort int port;

  String captcha() {
    String id = UUID.randomUUID().toString().replace("-", "");
    redis.opsForValue().set(Captchas.PREFIX + id, Captchas.hash("AC34"), Duration.ofMinutes(5));
    return id;
  }

  long customer() {
    long uid = telegram.openAccount(6_600_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L), "fzt").userId();
    jdbc.update("INSERT INTO account_profile (user_id, first_name, middle_name, last_name, birth_date, nationality) VALUES (?, '雷', '', '李', '1990-01-01', 'CN')", uid);
    jdbc.update("INSERT INTO account_kyc_application (user_id, doc_type, doc_number, front_key, back_key, selfie_key, status) VALUES (?, 'PASSPORT', 'G00004321', 'f', 'b', 's', 'APPROVED')",
        uid);
    return uid;
  }

  MvcResult start(String body) throws Exception {
    return mvc.perform(post("/api/public/withdraw-freeze").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
  }

  String wrongCaptcha() {
    return "{\"customerNo\":\"1\",\"captchaId\":\"" + captcha() + "\",\"captcha\":\"XXXX\"}";
  }

  @Test
  void 信息对和不对_响应状态内容一样_都不少于最短响应时间_补时间走异步() throws Exception {
    long uid = customer();
    String[] bodies = {
      "{\"customerNo\":\"" + uid + "\",\"name\":\"韩梅梅\",\"docLast4\":\"0000\",\"captchaId\":\"" + captcha() + "\",\"captcha\":\"AC34\"}",
      "{\"customerNo\":\"" + uid + "\",\"name\":\"李雷\",\"docLast4\":\"4321\",\"captchaId\":\"" + captcha() + "\",\"captcha\":\"AC34\"}",
      "{\"customerNo\":\"999999999999\",\"name\":\"李雷\",\"docLast4\":\"4321\",\"captchaId\":\"" + captcha() + "\",\"captcha\":\"AC34\"}" };
    List<String> got = new ArrayList<>();
    for (String b : bodies) {
      long t0 = System.nanoTime();
      MvcResult s = start(b);
      assertThat(s.getRequest().isAsyncStarted()).as("请求线程马上返回，补时间交给定时器").isTrue();
      assertThat((System.nanoTime() - t0) / 1_000_000).as("处理完就返回，不在请求线程上等").isLessThan(700);
      MockHttpServletResponse r = mvc.perform(asyncDispatch(s)).andReturn().getResponse();
      assertThat((System.nanoTime() - t0) / 1_000_000).as("最短响应时间").isGreaterThanOrEqualTo(800);
      got.add(r.getStatus() + " " + r.getContentAsString());
    }
    assertThat(got).containsOnly("200 {\"message\":\"" + WebFreezeService.ACCEPTED + "\"}");
    assertThat(pins.status(uid).freezes()).extracting(WithdrawPinApi.Freeze::source).containsExactly("WEB_REQUEST");
    long t0 = System.nanoTime();
    MockHttpServletResponse bad = mvc.perform(asyncDispatch(start(wrongCaptcha()))).andReturn().getResponse();
    assertThat((System.nanoTime() - t0) / 1_000_000).as("验证码不对也一样等").isGreaterThanOrEqualTo(800);
    assertThat(bad.getContentAsString()).contains("CAPTCHA_INVALID");
  }

  @Test
  void 等待中的提交超过上限_马上429_和填的信息无关_回完以后又能提交() throws Exception {
    List<MvcResult> held = new ArrayList<>();
    for (int i = 0; i < 6; i++) held.add(start(wrongCaptcha()));
    assertThat(controller.availablePermits()).isZero();
    long t0 = System.nanoTime();
    MockHttpServletResponse over = start("{\"customerNo\":\"123\",\"name\":\"李雷\",\"docLast4\":\"4321\"}").getResponse();
    assertThat((System.nanoTime() - t0) / 1_000_000).as("超过上限马上回").isLessThan(300);
    assertThat(over.getStatus()).isEqualTo(429);
    assertThat(over.getContentAsString()).contains("RATE_LIMITED");
    for (MvcResult h : held) assertThat(mvc.perform(asyncDispatch(h)).andReturn().getResponse().getContentAsString()).contains("CAPTCHA_INVALID");
    assertThat(controller.availablePermits()).as("回完以后名额都还回来").isEqualTo(6);
  }

  @Test
  void 真实Tomcat只有4个请求线程_同时挂着5个提交_健康检查照样马上回应() throws Exception {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    String base = "http://127.0.0.1:" + port;
    List<CompletableFuture<HttpResponse<String>>> subs = new ArrayList<>();
    long t0 = System.nanoTime();
    for (int i = 0; i < 5; i++) {
      HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/public/withdraw-freeze")).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(wrongCaptcha())).build();
      subs.add(http.sendAsync(req, HttpResponse.BodyHandlers.ofString()));
    }
    Thread.sleep(200); // 等 5 个提交都进到应用里挂着（每个都要等到 800 毫秒）
    long h0 = System.nanoTime();
    HttpResponse<String> health = http.send(HttpRequest.newBuilder(URI.create(base + "/api/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
    long healthMs = (System.nanoTime() - h0) / 1_000_000;
    long sinceStart = (System.nanoTime() - t0) / 1_000_000;
    assertThat(health.statusCode()).as("回应了（测试环境有的检查项可能 DOWN）").isIn(200, 503);
    assertThat(sinceStart).as("健康检查在提交补完时间之前就回来了").isLessThan(800);
    assertThat(healthMs).isLessThan(500);
    for (CompletableFuture<HttpResponse<String>> f : subs) {
      HttpResponse<String> r = f.get();
      assertThat(r.body()).contains("CAPTCHA_INVALID");
    }
    assertThat((System.nanoTime() - t0) / 1_000_000).as("提交照样补够最短时间").isGreaterThanOrEqualTo(800);
  }
}
