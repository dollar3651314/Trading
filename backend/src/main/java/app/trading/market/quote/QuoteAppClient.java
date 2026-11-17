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
 * 调报价服务（quote-app）的内部接口（请求头带内部 Key）：同步品种和时段、K 线历史、端到端测试推动模拟报价。
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

  /** GET /internal/klines：最近 limit 根，从旧到新 */
  java.util.List<app.quote.protocol.Message.Bar> klines(String symbol, app.quote.protocol.Timeframe tf, int limit) {
    HttpResponse<String> r = send("GET", "/internal/klines?symbol=" + enc(symbol) + "&tf=" + tf.code + "&limit=" + limit, null);
    if (r.statusCode() != 200) throw new IllegalStateException("报价服务取 K 线失败：HTTP " + r.statusCode());
    java.util.List<app.quote.protocol.Message.Bar> out = new java.util.ArrayList<>();
    for (JsonNode n : json.readTree(r.body())) {
      out.add(new app.quote.protocol.Message.Bar(symbol, tf.code, n.path("time").asLong(), new BigDecimal(n.path("open").asString()),
          new BigDecimal(n.path("high").asString()), new BigDecimal(n.path("low").asString()), new BigDecimal(n.path("close").asString()), n.path("ticks").asInt()));
    }
    return out;
  }

  /** GET /internal/klines/closes：每个品种最近 limit 根的收盘价 */
  Map<String, java.util.List<BigDecimal>> closes(java.util.List<String> symbols, app.quote.protocol.Timeframe tf, int limit) {
    HttpResponse<String> r = send("GET", "/internal/klines/closes?symbols=" + enc(String.join(",", symbols)) + "&tf=" + tf.code + "&limit=" + limit, null);
    if (r.statusCode() != 200) throw new IllegalStateException("报价服务取收盘价失败：HTTP " + r.statusCode());
    Map<String, java.util.List<BigDecimal>> out = new java.util.LinkedHashMap<>();
    JsonNode body = json.readTree(r.body());
    for (String s : symbols) {
      java.util.List<BigDecimal> closes = new java.util.ArrayList<>();
      for (JsonNode c : body.path(s)) closes.add(new BigDecimal(c.asString()));
      out.put(s, closes);
    }
    return out;
  }

  /** 第 3 批：后台"报价服务"菜单转发内部接口，原样返回状态码、类型和内容 */
  public app.trading.market.api.QuoteServiceApi.Response raw(String method, String pathWithQuery, String body) {
    HttpResponse<String> r = send(method, pathWithQuery, body);
    return new app.trading.market.api.QuoteServiceApi.Response(r.statusCode(), r.headers().firstValue("Content-Type").orElse(""), r.body());
  }

  private static String enc(String s) {
    return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
  }

  private HttpResponse<String> send(String method, String path, String body) {
    HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)).header(QuoteStreamClient.KEY_HEADER, key)
        .header("Content-Type", "application/json").method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
        .build();
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
