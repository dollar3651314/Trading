package app.trading.market.quote;

import static app.quote.feed.FakeWsServerAccess.FAKE_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import app.trading.market.api.MarketQuote;
import app.quote.feed.FakeWsServerAccess;
import app.quote.feed.QuoteFeed;
import app.quote.feed.QuoteFeeds;
import app.quote.pipeline.SimulatedQuoteSource;
import app.trading.notification.api.MailApi;
import app.trading.support.FixedClockConfig;
import app.trading.support.MutableClock;
import app.trading.support.QuoteAppHarness;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 官网公开行情的开关 {@code FX_PUBLIC_QUOTES_SOURCE=live}（内部文档 第 6 节，拿到展示授权以后才打开）：
 * 官网取交易报价（真实源的价格），{@code sample} 为 false。默认（simulated）的情况见 {@link QuoteFeedsWiringTest}。只配 AllTick 的假 token。
 * 第 2 批拆进程后报价源在真实的报价服务里（{@link QuoteAppHarness}），交易这边通过内部 WebSocket 收报价。
 */
@SpringBootTest(properties = {"trading.market.source=remote", "trading.market.public-quotes.symbols=XAUUSD", "trading.market.public-quotes.source=live"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PublicQuotesLiveTest {

  static final FakeWsServerAccess server = FakeWsServerAccess.start();
  static final QuoteAppHarness quoteApp = QuoteAppHarness.start(new MutableClock(), Map.of("quote.market.source", "simulated",
      "quote.market.simulated-interval", "1h", "quote.feeds.alltick.token", FAKE_TOKEN, "quote.feeds.alltick.fx-url", server.url("/quote-b-ws-api"),
      "quote.feeds.alltick.stock-url", server.url("/quote-stock-b-ws-api"), "quote.feeds.alltick.channel-gap", "100ms"));

  @DynamicPropertySource
  static void quoteApp(DynamicPropertyRegistry r) {
    r.add("trading.market.quote-app.url", quoteApp::url);
    r.add("trading.market.quote-app.key", () -> QuoteAppHarness.KEY);
  }

  @AfterAll
  static void stop() {
    quoteApp.close();
    server.close();
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired QuoteProcessor processor;
  final QuoteFeeds feeds = quoteApp.bean(QuoteFeeds.class);
  final SimulatedQuoteSource simulated = quoteApp.bean(SimulatedQuoteSource.class);
  @MockitoBean MailApi mail;

  @Test
  void 打开live后官网显示真实源的价格_不再标示例行情() throws Exception {
    QuoteFeedsWiringTest.await("AllTick 两条连接都订阅了",
        () -> feeds.feeds().size() == 1 && feeds.feeds().getFirst().status().stream().allMatch(s -> s.state() == QuoteFeed.State.CONNECTED)
            && server.subscribed() >= 2);
    server.allTickDepth("GOLD", "4126.50", "4126.90");
    QuoteFeedsWiringTest.await("XAUUSD 有交易报价", () -> processor.latest("XAUUSD").filter(MarketQuote::executable).isPresent());
    simulated.moveTo("XAUUSD", new BigDecimal("4197.70"));
    QuoteFeedsWiringTest.await("示例行情也到了交易这边", () -> processor.latestSample("XAUUSD").isPresent());
    JsonNode body = json.readTree(mvc.perform(get("/api/public/quotes")).andReturn().getResponse().getContentAsString());
    assertThat(body.get("sample").asBoolean()).isFalse();
    // AllTick 的 4126.50 − 加点 0.10（V24）
    assertThat(body.get("items").get(0).get("bid").asString()).isEqualTo("4126.40");
  }
}
