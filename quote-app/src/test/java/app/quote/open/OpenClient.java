package app.quote.open;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 测试用的对外客户端：按开发者文档签名调 REST、换票据连 WebSocket，记下收到的每一条（AC-Q8 核对字段用）。 */
final class OpenClient {

  static final ObjectMapper JSON = JsonMapper.builder().build();
  static final String INTERNAL_KEY = "ci_only_fake_internal_key";

  record Res(int status, JsonNode body, HttpResponse<String> raw) {}

  private final int port;
  private final Clock clock;
  final HttpClient http = HttpClient.newHttpClient();
  /** 外部 Key 收到的全部 REST 响应体 */
  final List<JsonNode> seen = new CopyOnWriteArrayList<>();

  OpenClient(int port, Clock clock) {
    this.port = port;
    this.clock = clock;
  }

  Res call(String keyId, String secret, String method, String pathWithQuery) throws Exception {
    String ts = String.valueOf(clock.instant().getEpochSecond());
    String sig = OpenAuthFilter.sign(secret.getBytes(StandardCharsets.UTF_8), ts + "\n" + method + "\n" + pathWithQuery + "\n");
    return raw(method, pathWithQuery, keyId, ts, sig);
  }

  Res raw(String method, String pathWithQuery, String keyId, String ts, String sig) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pathWithQuery)).method(method, HttpRequest.BodyPublishers.noBody());
    if (keyId != null) b.header("X-QK-Key", keyId);
    if (ts != null) b.header("X-QK-Timestamp", ts);
    if (sig != null) b.header("X-QK-Signature", sig);
    HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    JsonNode n = r.body() == null || r.body().isBlank() ? null : JSON.readTree(r.body());
    if (n != null) seen.add(n);
    return new Res(r.statusCode(), n, r);
  }

  /** 内部接口（后台用，带内部 Key） */
  Res internal(String method, String path, String body) throws Exception {
    HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("X-Quote-Key", INTERNAL_KEY)
        .header("Content-Type", "application/json").method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
        .build();
    HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
    JsonNode n = r.body() == null || r.body().isBlank() || !r.headers().firstValue("Content-Type").orElse("").contains("json") ? null : JSON.readTree(r.body());
    return new Res(r.statusCode(), n, r);
  }

  /** 一条对外 WebSocket 连接 */
  static final class Ws implements WebSocket.Listener, AutoCloseable {
    final List<JsonNode> messages = new CopyOnWriteArrayList<>();
    volatile int closeCode = -1;
    WebSocket socket;
    private final StringBuilder buf = new StringBuilder();

    @Override
    public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
      buf.append(data);
      if (last) {
        messages.add(JSON.readTree(buf.toString()));
        buf.setLength(0);
      }
      w.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket w, int status, String reason) {
      closeCode = status;
      return null;
    }

    void send(String text) {
      socket.sendText(text, true).join();
    }

    List<JsonNode> of(String type) {
      return messages.stream().filter(m -> type.equals(m.path("type").asString())).toList();
    }

    List<JsonNode> quotes(String symbol) {
      return of("quote").stream().filter(m -> symbol.equals(m.path("symbol").asString())).toList();
    }

    @Override
    public void close() {
      if (socket != null) socket.abort();
    }
  }

  /** 换票据并连上；握手被拒时抛 {@link WebSocketHandshakeException}（取 HTTP 状态码看 {@link #handshakeStatus}） */
  Ws connect(String keyId, String secret) throws Exception {
    Res t = call(keyId, secret, "POST", "/v1/ws-ticket");
    if (t.status() != 200) throw new IllegalStateException("换票据失败：" + t.status() + " " + t.body());
    return connectTicket(t.body().path("ticket").asString());
  }

  Ws connectTicket(String ticket) {
    Ws ws = new Ws();
    ws.socket = http.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + port + "/v1/ws?ticket=" + ticket), ws).join();
    return ws;
  }

  /** 握手被拒时的 HTTP 状态码；连上了返回 101 */
  int handshakeStatus(String keyId, String secret) throws Exception {
    try (Ws ws = connect(keyId, secret)) {
      return 101;
    } catch (CompletionException e) {
      if (e.getCause() instanceof WebSocketHandshakeException h) return h.getResponse().statusCode();
      throw e;
    }
  }

  static void await(String what, BooleanSupplier ok) throws InterruptedException {
    await(what, Duration.ofSeconds(10), ok);
  }

  static void await(String what, Duration max, BooleanSupplier ok) throws InterruptedException {
    long end = System.nanoTime() + max.toNanos();
    while (System.nanoTime() < end) {
      if (ok.getAsBoolean()) return;
      Thread.sleep(20);
    }
    throw new AssertionError("等了 " + max.toSeconds() + " 秒还没有：" + what);
  }
}
