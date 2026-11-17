package app.trading.market.quote;

import app.quote.protocol.QuoteStreamClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 调报价服务（quote-app）的内部接口（请求头带内部 Key）：同步品种和时段、端到端测试推动模拟报价。
 * 只在 {@code trading.market.source=remote} 时创建（{@link RemoteQuotes}）。Key 不写日志。
 */
public class QuoteAppClient {

  private final String base;
  private final String key;
  private final ObjectMapper json;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  QuoteAppClient(QuoteAppProperties props, ObjectMapper json) {
    this.base = props.url();
    this.key = props.key();
    this.json = json;
  }

  /** 内部 WebSocket 的地址 */
  URI streamUri() {
    return URI.create(base.replaceFirst("^http", "ws") + "/internal/ws/quotes");
  }

  /** PUT /internal/catalog：整份同步品种、时段、休市日、模拟起始价；失败抛异常 */
  void syncCatalog(Object body) {
    HttpResponse<String> r = send("PUT", "/internal/catalog", json.writeValueAsString(body));
    if (r.statusCode() != 200) throw new IllegalStateException("报价服务同步品种失败：HTTP " + r.statusCode());
  }

  /** 端到端测试：把模拟源某个品种的原始卖价移到 bid；没有这个品种或不是模拟源时为空 */
  public Optional<JsonNode> e2eMove(String symbol, BigDecimal bid) {
    HttpResponse<String> r = send("POST", "/internal/e2e/move", json.writeValueAsString(Map.of("symbol", symbol, "bid", bid.toPlainString())));
    return r.statusCode() == 200 ? Optional.of(json.readTree(r.body())) : Optional.empty();
  }

  private HttpResponse<String> send(String method, String path, String body) {
    HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)).header(QuoteStreamClient.KEY_HEADER, key)
        .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body)).build();
    try {
      return http.send(req, HttpResponse.BodyHandlers.ofString());
    } catch (java.io.IOException e) {
      throw new IllegalStateException("连不上报价服务：" + e.getClass().getSimpleName());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("调用报价服务被中断");
    }
  }
}
