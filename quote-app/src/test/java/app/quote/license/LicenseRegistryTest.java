package app.quote.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import app.quote.feed.QuoteRouter;
import app.quote.feed.QuoteSource;
import app.quote.license.QuoteLicense.Grant;
import app.quote.license.QuoteLicense.Use;
import app.quote.pipeline.QuoteProcessor;
import app.quote.pipeline.RawQuote;
import app.quote.protocol.QuoteFrame;
import app.quote.stream.QuoteStream;
import app.quote.support.FixedClockConfig;
import app.quote.support.MutableClock;
import app.quote.support.QuoteAppTest;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
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

/**
 * 报价源授权登记（技术方案 08 页，TQ-11 A）：报价商 × 品种三项（交易用途、登录后展示、转售），各自有起止日期；具体品种优先于 {@code *}；
 * 启用检查时（这里明确设 enforce=true；所有环境默认关，Amos 2026-11-17）报价的 tradable 按"交易用途"标，没有授权的品种只显示不开仓。
 * 报价源用 mock 的路由给出（EURUSD 在用 alltick），日期用可调时钟（2026-10-14）。
 */
@QuoteAppTest
@Import(FixedClockConfig.class)
@TestPropertySource(properties = "quote.license.enforce=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LicenseRegistryTest {

  @LocalServerPort int port;
  @Autowired LicenseRegistry registry;
  @Autowired QuoteProcessor processor;
  @Autowired QuoteStream stream;
  @Autowired JdbcTemplate jdbc;
  @Autowired Clock clock;
  @MockitoBean QuoteRouter router;

  static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

  @BeforeEach
  void route() {
    when(router.current(anyString())).thenReturn(Optional.empty());
    when(router.current("EURUSD")).thenReturn(Optional.of(new QuoteSource("alltick", "primary")));
    when(router.current("GBPUSD")).thenReturn(Optional.of(new QuoteSource("itick", "backup")));
  }

  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM quote_source_license");
    registry.reload();
    ((MutableClock) clock).reset();
  }

  static QuoteLicense trading(String feed, String symbol, LocalDate from, LocalDate until) {
    return new QuoteLicense(feed, symbol, new Grant(true, from, until), Grant.NONE, Grant.NONE, "测试");
  }

  QuoteFrame push(String symbol, String bid) {
    BigDecimal b = new BigDecimal(bid);
    processor.process(new RawQuote(symbol, b, b.add(new BigDecimal("0.00010")), clock.instant()));
    return stream.latest(symbol).orElseThrow();
  }

  @Test
  void 没有登记就是没有授权_报价照推但不能开仓() {
    assertThat(registry.enforced()).isTrue();
    assertThat(registry.tradingUse("alltick", "EURUSD")).isFalse();
    QuoteFrame f = push("EURUSD", "1.08000");
    assertThat(f.tradable()).isFalse();
    assertThat(f.feed()).isEqualTo("alltick");
    assertThat(f.bid()).as("只显示：价格照常推").isEqualByComparingTo("1.08000");
  }

  @Test
  void 交易用途已授权_报价可以开仓_撤销后立即不行() {
    registry.save(trading("alltick", "EURUSD", null, null));
    assertThat(push("EURUSD", "1.08001").tradable()).isTrue();
    assertThat(push("GBPUSD", "1.33000").tradable()).as("别的报价商没授权").isFalse();
    assertThat(registry.delete("alltick", "EURUSD")).isTrue();
    assertThat(push("EURUSD", "1.08002").tradable()).isFalse();
  }

  @Test
  void 星号是这家的全部品种_具体品种那一行优先() {
    registry.save(trading("itick", QuoteLicense.ALL, null, null));
    assertThat(registry.tradingUse("itick", "GBPUSD")).isTrue();
    assertThat(registry.tradingUse("itick", "XAUUSD")).isTrue();
    registry.save(new QuoteLicense("itick", "GBPUSD", Grant.NONE, new Grant(true, null, null), Grant.NONE, "这个品种只给展示"));
    assertThat(registry.tradingUse("itick", "GBPUSD")).as("具体品种那一行没有交易用途").isFalse();
    assertThat(registry.allowed("itick", "GBPUSD", Use.DISPLAY)).isTrue();
    assertThat(registry.tradingUse("itick", "XAUUSD")).isTrue();
    assertThat(registry.tradingUse("alltick", "XAUUSD")).isFalse();
  }

  @Test
  void 起止日期按UTC日期_含首尾_到期后自动失效() {
    registry.save(trading("alltick", "EURUSD", TODAY.plusDays(1), TODAY.plusDays(2)));
    assertThat(registry.tradingUse("alltick", "EURUSD")).as("还没开始").isFalse();
    ((MutableClock) clock).advance(Duration.ofDays(1));
    assertThat(registry.tradingUse("alltick", "EURUSD")).as("起始日当天").isTrue();
    ((MutableClock) clock).advance(Duration.ofDays(1));
    assertThat(registry.tradingUse("alltick", "EURUSD")).as("到期日当天").isTrue();
    ((MutableClock) clock).advance(Duration.ofDays(1));
    assertThat(registry.tradingUse("alltick", "EURUSD")).as("过了到期日").isFalse();
  }

  @Test
  void 三项各管各的() {
    registry.save(new QuoteLicense("binance", "BTCUSD", Grant.NONE, new Grant(true, null, null), new Grant(true, null, TODAY.minusDays(1)), null));
    assertThat(registry.allowed("binance", "BTCUSD", Use.TRADING)).isFalse();
    assertThat(registry.allowed("binance", "BTCUSD", Use.DISPLAY)).isTrue();
    assertThat(registry.allowed("binance", "BTCUSD", Use.RESALE)).as("转售已到期").isFalse();
  }

  @Test
  void 内部接口_查看_新增修改_删除_校验() throws Exception {
    HttpClient http = HttpClient.newHttpClient();
    String body = "{\"feed\":\"alltick\",\"symbol\":\"XAUUSD\",\"trading\":{\"granted\":true,\"from\":\"2026-10-01\",\"until\":\"2027-09-30\"},"
        + "\"display\":{\"granted\":true},\"resale\":{\"granted\":false},\"note\":\"测试登记\"}";
    assertThat(send(http, "PUT", "/internal/licenses", body).statusCode()).isEqualTo(200);
    assertThat(registry.tradingUse("alltick", "XAUUSD")).isTrue();
    String list = send(http, "GET", "/internal/licenses", null).body();
    assertThat(list).contains("\"enforced\":true").contains("\"symbol\":\"XAUUSD\"").contains("\"until\":\"2027-09-30\"").doesNotContain("price");
    for (String bad : new String[] {"{\"feed\":\"massive\",\"symbol\":\"XAUUSD\"}", "{\"feed\":\"alltick\",\"symbol\":\"xau usd\"}",
        "{\"feed\":\"alltick\",\"symbol\":\"XAUUSD\",\"trading\":{\"granted\":true,\"from\":\"2026-10-02\",\"until\":\"2026-10-01\"}}"}) {
      assertThat(send(http, "PUT", "/internal/licenses", bad).statusCode()).as(bad).isEqualTo(400);
    }
    assertThat(send(http, "DELETE", "/internal/licenses/alltick/XAUUSD", null).statusCode()).isEqualTo(200);
    assertThat(send(http, "DELETE", "/internal/licenses/alltick/XAUUSD", null).statusCode()).isEqualTo(404);
    assertThat(registry.tradingUse("alltick", "XAUUSD")).isFalse();
  }

  HttpResponse<String> send(HttpClient http, String method, String path, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("X-Quote-Key", "ci_only_fake_internal_key")
        .header("Content-Type", "application/json");
    b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }
}
