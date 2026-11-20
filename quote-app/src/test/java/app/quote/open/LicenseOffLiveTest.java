package app.quote.open;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import app.quote.feed.QuoteRouter;
import app.quote.license.LicenseRegistry;
import app.quote.feed.QuoteSource;
import app.quote.pipeline.QuoteProcessor;
import app.quote.pipeline.RawQuote;
import app.quote.stream.QuoteStream;
import app.quote.support.FixedClockConfig;
import app.quote.support.MutableClock;
import app.quote.support.QuoteAppTest;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

/**
 * Amos 2026-11-17"授权检查彻底关掉"：报价模式 live（在用真实数据）+ FX_QUOTE_LICENSE_ENFORCE 用默认值时，授权检查不启用——
 * 库里一条授权登记都没有，报价照样 tradable=true（能开仓），总开关打开后外部 Key 照样拿到没有转售授权的品种。
 * 不设 quote.license.enforce（用 quote.yml 的默认值）；报价源用 mock 的路由给出（不连任何真实报价源）。
 */
@QuoteAppTest
@Import(FixedClockConfig.class)
@TestPropertySource(properties = "quote.market.source=live")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LicenseOffLiveTest {

  @LocalServerPort int port;
  @Autowired LicenseRegistry registry;
  @Autowired QuoteProcessor processor;
  @Autowired QuoteStream stream;
  @Autowired OpenSettings settings;
  @Autowired JdbcTemplate jdbc;
  @Autowired Clock clock;
  @Autowired app.quote.QuoteProperties quote;
  @Autowired app.quote.feed.QuoteFeedsProperties feeds;
  @MockitoBean QuoteRouter router;

  OpenClient c;

  @BeforeEach
  void setUp() {
    c = new OpenClient(port, clock);
    when(router.current(anyString())).thenReturn(Optional.of(new QuoteSource("alltick", "primary")));
  }

  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM quote_api_key");
    jdbc.update("DELETE FROM quote_client");
    jdbc.update("DELETE FROM quote_open_setting");
    jdbc.update("DELETE FROM quote_source_license");
    settings.reload();
    registry.reload();
    ((MutableClock) clock).reset();
  }

  void push(String symbol) {
    BigDecimal b = new BigDecimal("1.08000");
    processor.process(new RawQuote(symbol, b, b.add(new BigDecimal("0.00010")), clock.instant()));
  }

  @Test
  void live加默认值_没有任何授权登记_报价能开仓_外部Key拿到全部品种() throws Exception {
    assertThat(LicenseRegistry.realData(quote, feeds)).as("报价模式 live = 在用真实数据").isTrue();
    assertThat(registry.enforced()).as("默认不检查").isFalse();
    assertThat(registry.all()).as("一条授权登记都没有").isEmpty();

    push("EURUSD");
    push("GBPUSD");
    assertThat(stream.latest("EURUSD").orElseThrow().tradable()).as("没有交易用途授权也能开仓").isTrue();
    assertThat(stream.latest("GBPUSD").orElseThrow().tradable()).isTrue();

    var gate = c.internal("PUT", "/internal/open/gate", "{\"open\":true,\"amosApproved\":true,\"by\":\"owner\",\"reason\":\"测试\"}");
    assertThat(gate.status()).isEqualTo(200);
    var cl = c.internal("POST", "/internal/open/clients", "{\"name\":\"授权关闭测试" + System.nanoTime() + "\",\"email\":\"dev@example.com\",\"note\":\"测试\"}");
    var k = c.internal("POST", "/internal/open/keys", "{\"clientId\":" + cl.body().path("id").asLong() + ",\"kind\":\"EXTERNAL\",\"symbols\":[]}");
    assertThat(k.status()).isEqualTo(200);
    String id = k.body().path("key").path("keyId").asString();
    String secret = k.body().path("secret").asString();

    Set<String> codes = new HashSet<>();
    for (JsonNode s : c.call(id, secret, "GET", "/v1/symbols").body().path("items")) codes.add(s.path("code").asString());
    assertThat(codes).as("没有转售授权的品种外部 Key 也拿得到").contains("EURUSD", "GBPUSD");
    var q = c.call(id, secret, "GET", "/v1/quotes?symbols=EURUSD,GBPUSD");
    assertThat(q.status()).isEqualTo(200);
    assertThat(q.body().path("items")).hasSize(2);
  }
}
