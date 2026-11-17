package app.trading.market.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import app.quote.pipeline.RawQuote;
import app.trading.notification.api.MailApi;
import app.trading.support.FixedClockConfig;
import app.trading.support.MutableClock;
import app.trading.support.QuoteAppHarness;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
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
 * 第 3 批（TD-9 A、TY-4、AC-Q10）：生产（报价服务 live 模式）官网公开行情<b>不返回价格</b>，只有品种代码、中英文名、状态和 pricesHidden=true，
 * 官网每行显示"登录后查看实时行情"。FX_PUBLIC_QUOTES_SOURCE 用默认值（没有另谈公开展示授权）。报价服务用 live 模式、不配报价源，报价由测试直接喂。
 */
@SpringBootTest(properties = {"trading.market.source=remote", "trading.market.public-quotes.symbols=XAUUSD,EURUSD"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PublicQuotesHiddenTest {

  static final QuoteAppHarness quoteApp = QuoteAppHarness.start(new MutableClock(), Map.of("quote.market.source", "live"));

  @DynamicPropertySource
  static void quoteApp(DynamicPropertyRegistry r) {
    r.add("trading.market.quote-app.url", quoteApp::url);
    r.add("trading.market.quote-app.key", () -> QuoteAppHarness.KEY);
  }

  @AfterAll
  static void stop() {
    quoteApp.close();
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired QuoteProcessor processor;
  @Autowired RemoteQuotes remote;
  @MockitoBean MailApi mail;

  static void await(String what, BooleanSupplier ok) throws InterruptedException {
    long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < end) {
      if (ok.getAsBoolean()) return;
      Thread.sleep(20);
    }
    throw new AssertionError("等了 10 秒还没有：" + what);
  }

  @Test
  void 生产配置下公开行情接口不返回价格_只有品种名和状态() throws Exception {
    await("连上报价服务、知道是 live 模式", () -> remote.connected() && processor.quoteMode().map("live"::equals).orElse(false));
    quoteApp.bean(app.quote.pipeline.QuoteProcessor.class).process(new RawQuote("XAUUSD", new BigDecimal("4126.50"), new BigDecimal("4126.90"), FixedClockConfig.NOW));
    await("交易这边有 XAUUSD 报价", () -> processor.latest("XAUUSD").isPresent());
    String text = mvc.perform(get("/api/public/quotes")).andReturn().getResponse().getContentAsString();
    JsonNode body = json.readTree(text);
    assertThat(body.get("pricesHidden").asBoolean()).isTrue();
    assertThat(body.get("sample").asBoolean()).isFalse();
    assertThat(body.get("items")).hasSize(2);
    for (JsonNode n : body.get("items")) {
      assertThat(n.get("bid").isNull()).as(n.toString()).isTrue();
      assertThat(n.get("changePct").isNull()).isTrue();
      assertThat(n.get("time").isNull()).isTrue();
      assertThat(n.get("nameZh").asString()).isNotBlank();
      Set<String> f = new TreeSet<>(n.propertyNames());
      assertThat(f).containsExactlyInAnyOrder("symbol", "nameZh", "nameEn", "bid", "changePct", "time", "status");
    }
    assertThat(text).doesNotContain("4126");
  }
}
