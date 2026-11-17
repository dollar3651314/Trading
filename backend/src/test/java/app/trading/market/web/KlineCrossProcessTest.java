package app.trading.market.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import app.quote.kline.KlineService;
import app.quote.pipeline.QuoteProcessor;
import app.quote.pipeline.RawQuote;
import app.trading.market.quote.RemoteQuotes;
import app.trading.notification.api.MailApi;
import app.trading.support.FixedClockConfig;
import app.trading.support.MutableClock;
import app.trading.support.QuoteAppHarness;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * K 线只在报价服务里算（第 2 批第二步，技术方案 02 页第 2 节第 4 条），客户端的 K 线接口和推送经 trading-app 转出来。跨进程：真实的报价服务
 * （{@link QuoteAppHarness}）+ trading-app（trading.market.source=remote）。拆分前 {@code MarketFlowTest} 的"K 线用不含加点的中间价 4126.55"
 * 和 {@code MarketPushTest} 的"订阅的 K 线推送"这两条搬到这里，断言值不变。时钟固定在 2026-10-14（周三）10:00 UTC。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "trading.market.source=remote")
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KlineCrossProcessTest {

  static final Instant NOW = FixedClockConfig.NOW;
  static final QuoteAppHarness quoteApp = QuoteAppHarness.start(new MutableClock(), Map.of());

  @DynamicPropertySource
  static void quoteApp(DynamicPropertyRegistry r) {
    r.add("trading.market.quote-app.url", quoteApp::url);
    r.add("trading.market.quote-app.key", () -> QuoteAppHarness.KEY);
  }

  @AfterAll
  static void stop() {
    quoteApp.close();
  }

  @LocalServerPort int port;
  @Autowired RemoteQuotes remote;
  @MockitoBean MailApi mail;
  final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void connected() throws InterruptedException {
    await("连上报价服务", remote::connected);
  }

  static void await(String what, BooleanSupplier ok) throws InterruptedException {
    long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < end) {
      if (ok.getAsBoolean()) return;
      Thread.sleep(20);
    }
    throw new AssertionError("等了 10 秒还没有：" + what);
  }

  @SuppressWarnings("unchecked")
  String loginCookie() throws Exception {
    String email = "kl" + System.nanoTime() + "@example.com";
    send("POST", "/api/auth/email-code", "{\"email\":\"" + email + "\",\"purpose\":\"REGISTER\"}", null);
    ArgumentCaptor<Map<String, String>> p = ArgumentCaptor.forClass(Map.class);
    verify(mail, atLeastOnce()).send(eq(email), eq("EMAIL_CODE"), p.capture());
    HttpResponse<String> r = send("POST", "/api/auth/register", "{\"email\":\"" + email + "\",\"code\":\"" + p.getValue().get("code")
        + "\",\"password\":\"password123\",\"firstName\":\"A\",\"lastName\":\"B\",\"birthDate\":\"1990-05-01\",\"nationality\":\"CN\"}", null);
    String setCookie = r.headers().firstValue("set-cookie").orElseThrow();
    return setCookie.substring(0, setCookie.indexOf(';'));
  }

  HttpResponse<String> send(String method, String path, String body, String cookie) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
    if (cookie != null) b.header("Cookie", cookie);
    b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void K线接口和今日走势来自报价服务_用不含加点的中间价() throws Exception {
    String cookie = loginCookie();
    quoteApp.bean(QuoteProcessor.class).process(new RawQuote("XAUUSD", new BigDecimal("4126.35"), new BigDecimal("4126.75"), NOW));
    String k = send("GET", "/api/market/klines?symbol=XAUUSD&tf=15m", null, cookie).body();
    // K 线用不含加点的中间价 4126.55（对客报价是 4126.25 / 4126.85）
    assertThat(k).contains("\"open\":\"4126.55\"");
    assertThat(send("GET", "/api/market/klines?symbol=XAUUSD&tf=2m", null, cookie).body()).contains("TIMEFRAME_INVALID");
    assertThat(send("GET", "/api/market/sparklines", null, cookie).body()).contains("\"XAUUSD\":[\"4126.55\"]");
  }

  @Test
  void 订阅的K线有变化时推给客户端() throws Exception {
    String cookie = loginCookie();
    MarketPushTest.Collector c = new MarketPushTest.Collector();
    WebSocket ws = http.newWebSocketBuilder().header("Cookie", cookie).connectTimeout(Duration.ofSeconds(5))
        .buildAsync(URI.create("ws://localhost:" + port + "/api/ws/market"), c).get(5, TimeUnit.SECONDS);
    assertThat(c.next("\"type\":\"quotes\"")).isNotNull();
    ws.sendText("{\"op\":\"subKline\",\"symbol\":\"NZDUSD\",\"tf\":\"1m\"}", true).get(5, TimeUnit.SECONDS);
    Thread.sleep(300);
    quoteApp.bean(KlineService.class).accept("NZDUSD", new BigDecimal("0.57040"), NOW.plusSeconds(1));
    assertThat(c.next("\"type\":\"kline\"")).contains("\"tf\":\"1m\"").contains("\"close\":\"0.57040\"");
    ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS);
  }
}
