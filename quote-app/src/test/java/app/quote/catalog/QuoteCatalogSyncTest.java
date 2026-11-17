package app.quote.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import app.quote.pipeline.CleanQuote;
import app.quote.pipeline.QuoteProcessor;
import app.quote.pipeline.SimulatedQuoteSource;
import app.quote.support.FixedClockConfig;
import app.quote.support.QuoteAppTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 品种、时段、模拟起始价（报价服务自己的一份，第 2 批）：第一版 50 个品种和时段由迁移建好；trading-app 后台改完整份同步过来（PUT /internal/catalog）；
 * 模拟源<b>重启后从库里读回起始价</b>（拆分前 trading-app 的 AdminSimStartPriceTest 里"重启后读回"的那部分搬到这里），csv 里有的品种以 csv 为准。
 * 模拟模式，随机游走间隔 1 小时（测试期间不跳价）。
 */
@QuoteAppTest
@Import(FixedClockConfig.class)
@TestPropertySource(properties = {"quote.market.source=simulated", "quote.market.simulated-interval=1h"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuoteCatalogSyncTest {

  @LocalServerPort int port;
  @Autowired SymbolCatalog catalog;
  @Autowired SimulatedQuoteSource source;
  @Autowired QuoteProcessor processor;
  @Autowired JdbcTemplate jdbc;

  @AfterEach
  void restore() {
    jdbc.update("DELETE FROM quote_session WHERE symbol LIKE 'ZQ%'");
    jdbc.update("DELETE FROM quote_symbol WHERE code LIKE 'ZQ%'");
    jdbc.update("UPDATE quote_symbol SET sim_start_price = NULL");
    catalog.reload();
  }

  Optional<CleanQuote> awaitQuote(String code) throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      Optional<CleanQuote> q = processor.latest(code);
      if (q.isPresent()) return q;
      Thread.sleep(50);
    }
    return Optional.empty();
  }

  @Test
  void 迁移建好第一版50个品种和时段() {
    assertThat(catalog.all()).hasSizeGreaterThanOrEqualTo(50);
    assertThat(catalog.get("XAUUSD").orElseThrow().digits()).isEqualTo(2);
    assertThat(catalog.get("USDJPY").orElseThrow().digits()).isEqualTo(3);
    assertThat(catalog.isOpen("EURUSD", FixedClockConfig.NOW)).isTrue();
    assertThat(catalog.isOpen("AAPL", FixedClockConfig.NOW)).as("美股这个时间休市").isFalse();
    assertThat(catalog.isOpen("BTCUSD", java.time.Instant.parse("2026-10-17T03:00:00Z"))).as("加密货币周六也开").isTrue();
  }

  @Test
  void 重启后从库里读回起始价_csv里有的品种以csv为准() throws Exception {
    String code = "ZQRST";
    // 模拟"上次运行时同步过来的品种"：直接写库，模拟源还不知道它
    jdbc.update("INSERT INTO quote_symbol (code, category, digits, sim_start_price, sort_order) VALUES (?, 'crypto', 3, 42.125, 9999)", code);
    for (int d = 1; d <= 7; d++) jdbc.update("INSERT INTO quote_session (symbol, day_of_week, open_minute, close_minute) VALUES (?, ?, 0, 1440)", code, d);
    jdbc.update("UPDATE quote_symbol SET sim_start_price = 9.99999 WHERE code = 'EURUSD'");
    catalog.reload();
    assertThat(source.has(code)).isFalse();
    assertThat(source.seedFromDatabase()).as("EURUSD 在 csv 里，不用库里的起始价").contains(code).doesNotContain("EURUSD");
    CleanQuote q = awaitQuote(code).orElseThrow(() -> new AssertionError("重启后没有收到报价"));
    assertThat(q.bid()).as("报价服务出干净价（不加点）").isEqualByComparingTo("42.125");
    assertThat(q.ask()).isEqualByComparingTo("42.129"); // 点差 42.125 × 0.0001 ≈ 0.004（3 位小数）
    assertThat(source.seedFromDatabase()).as("已经在报价的不重复加入").doesNotContain(code);
  }

  @Test
  void 同步接口整份替换_新起始价的品种立即开始报价_格式不对返回400() throws Exception {
    HttpClient http = HttpClient.newHttpClient();
    String body = "{\"symbols\":[{\"code\":\"EURUSD\",\"category\":\"fx\",\"digits\":5},{\"code\":\"ZQNEW\",\"category\":\"crypto\",\"digits\":2,\"simStartPrice\":12.34}],"
        + "\"sessions\":[{\"symbol\":\"ZQNEW\",\"dayOfWeek\":3,\"openMinute\":0,\"closeMinute\":1440},{\"symbol\":\"EURUSD\",\"dayOfWeek\":3,\"openMinute\":0,\"closeMinute\":1440}],"
        + "\"holidays\":[{\"symbol\":\"EURUSD\",\"date\":\"2026-10-15\"}]}";
    try {
      HttpResponse<String> r = put(http, body, "ci_only_fake_internal_key");
      assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
      assertThat(r.body()).contains("\"seeded\":[\"ZQNEW\"]");
      assertThat(catalog.all()).extracting(QuoteSymbol::code).containsExactly("EURUSD", "ZQNEW");
      assertThat(catalog.isOpen("EURUSD", java.time.Instant.parse("2026-10-15T10:00:00Z"))).as("同步来的休市日").isFalse();
      assertThat(awaitQuote("ZQNEW").orElseThrow().bid()).isEqualByComparingTo("12.34");
      for (String bad : new String[] {"{\"symbols\":[]}", "{\"symbols\":[{\"code\":\"bad code\",\"category\":\"fx\",\"digits\":5}]}",
          "{\"symbols\":[{\"code\":\"EURUSD\",\"category\":\"fx\",\"digits\":5}],\"sessions\":[{\"symbol\":\"EURUSD\",\"dayOfWeek\":8,\"openMinute\":0,\"closeMinute\":10}]}"}) {
        assertThat(put(http, bad, "ci_only_fake_internal_key").statusCode()).as(bad).isEqualTo(400);
      }
      assertThat(put(http, body, "wrong").statusCode()).isEqualTo(401);
      assertThat(catalog.all()).as("校验失败不改").hasSize(2);
    } finally {
      // 恢复迁移建的 50 个品种（重新跑一遍 V1 的数据）
      jdbc.update("DELETE FROM quote_holiday");
      jdbc.update("DELETE FROM quote_session");
      jdbc.update("DELETE FROM quote_symbol");
      String v1 = new String(new org.springframework.core.io.ClassPathResource("db/quote/V1__quote_catalog.sql").getInputStream().readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8);
      for (String stmt : v1.substring(v1.indexOf("INSERT INTO quote_symbol")).split(";")) {
        if (!stmt.isBlank()) jdbc.update(stmt.lines().filter(l -> !l.startsWith("--")).reduce("", (a, b) -> a + "\n" + b));
      }
      catalog.reload();
      assertThat(catalog.all()).hasSize(50);
    }
  }

  HttpResponse<String> put(HttpClient http, String body, String key) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/catalog")).header("X-Quote-Key", key)
        .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void 健康检查不要内部Key() throws Exception {
    HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/health")).build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).contains("\"status\":\"UP\"");
  }
}
