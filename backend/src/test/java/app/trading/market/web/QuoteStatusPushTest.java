package app.trading.market.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

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
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
 * 第 3 批客户端交易页（原型 P3）要的两样，跨进程（真实的报价服务 + trading-app remote）：
 * <ul>
 *   <li>"暂不开放交易"：报价服务明确打开授权检查（enforce=true；所有环境默认关）、品种没有"交易用途"授权时，品种列表的 {@code tradable} 是 false（下单拒 SYMBOL_NOT_TRADABLE 已有测试）；</li>
 *   <li>"暂无报价"：行情推送连上先推 {@code quoteStatus}；开市的品种 3 秒都没收到报价推 available=false，来了报价推 true（恢复不用刷新）。</li>
 * </ul>
 * 时钟：两边都停在 2026-10-14（周三）10:00 UTC，交易这边按需要往前拨。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "trading.market.source=remote")
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuoteStatusPushTest {

  static final QuoteAppHarness quoteApp = QuoteAppHarness.start(new MutableClock(), Map.of("quote.license.enforce", "true"));

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
  @Autowired Clock clock;
  @Autowired MarketPush push;
  @MockitoBean MailApi mail;
  final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void connected() throws InterruptedException {
    await("连上报价服务", remote::connected);
  }

  @AfterEach
  void reset() {
    ((MutableClock) clock).reset();
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
    String email = "qs" + System.nanoTime() + "@example.com";
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

  void upstream(String symbol, String bid, String ask) {
    quoteApp.bean(QuoteProcessor.class).process(new RawQuote(symbol, new BigDecimal(bid), new BigDecimal(ask), clock.instant()));
  }

  @Test
  void 没有交易用途授权的品种_品种列表标tradable_false() throws Exception {
    String cookie = loginCookie();
    upstream("EURUSD", "1.08000", "1.08010");
    await("交易这边收到报价", () -> remote.connected() && !send0("/api/market/symbols", cookie).isEmpty());
    String body = send("GET", "/api/market/symbols", null, cookie).body();
    assertThat(body).contains("\"code\":\"EURUSD\"");
    await("EURUSD 标成不能交易", () -> send0("/api/market/symbols", cookie).replace(" ", "").matches("(?s).*\"code\":\"EURUSD\"[^}]*\"tradable\":false.*"));
  }

  String send0(String path, String cookie) {
    try {
      return send("GET", path, null, cookie).body();
    } catch (Exception e) {
      return "";
    }
  }

  /** 一个假的推送连接：记下服务器发给它的每条消息 */
  static org.springframework.web.socket.WebSocketSession fakeSession(java.util.List<String> sent) throws Exception {
    org.springframework.web.socket.WebSocketSession s = org.mockito.Mockito.mock(org.springframework.web.socket.WebSocketSession.class);
    org.mockito.Mockito.when(s.getId()).thenReturn("qs-" + System.nanoTime());
    org.mockito.Mockito.when(s.isOpen()).thenReturn(true);
    org.mockito.Mockito.doAnswer(inv -> {
      sent.add(String.valueOf(((org.springframework.web.socket.TextMessage) inv.getArgument(0)).getPayload()));
      return null;
    }).when(s).sendMessage(org.mockito.ArgumentMatchers.any());
    return s;
  }

  /** 10 秒内等到推送 quoteStatus = available（推送是 250 毫秒一次的定时任务，这里也顺手调一次） */
  boolean status(java.util.List<String> sent, boolean available) throws InterruptedException {
    long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < end) {
      push.flush();
      if (sent.stream().anyMatch(m -> m.contains("quoteStatus") && m.contains("\"available\":" + available))) return true;
      Thread.sleep(50);
    }
    return false;
  }

  @Test
  void 报价中断推quoteStatus_false_来了报价推true_不用刷新() throws Exception {
    upstream("XAUUSD", "4126.35", "4126.75");
    await("XAUUSD 能成交", push::quoteAvailable);
    java.util.List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
    org.springframework.web.socket.WebSocketSession s = fakeSession(sent);
    push.afterConnectionEstablished(s);
    try {
      assertThat(sent).as("连上先推全部报价，再推一次当前状态").anyMatch(m -> m.contains("\"type\":\"quotes\""));
      assertThat(sent).anyMatch(m -> m.contains("\"type\":\"quoteStatus\"") && m.contains("\"available\":true"));
      // 先让定时推送也看到"可以成交"（不然它记的上一次状态可能还是连上之前的，拨表后就看不到"变化"）
      assertThat(status(sent, true)).isTrue();
      sent.clear();
      ((MutableClock) clock).advance(Duration.ofSeconds(4));
      await("4 秒没有新报价，不能成交", () -> !push.quoteAvailable());
      assertThat(status(sent, false)).as("推送 available=false，收到：%s", sent).isTrue();
      sent.clear();
      upstream("XAUUSD", "4126.45", "4126.85");
      assertThat(status(sent, true)).as("恢复后推送 available=true，收到：%s", sent).isTrue();
    } finally {
      push.afterConnectionClosed(s, org.springframework.web.socket.CloseStatus.NORMAL);
    }
  }
}
