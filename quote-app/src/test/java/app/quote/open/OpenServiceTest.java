package app.quote.open;

import static app.quote.open.OpenClient.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import app.quote.feed.QuoteRouter;
import app.quote.feed.QuoteSource;
import app.quote.health.AlertCheck;
import app.quote.license.LicenseRegistry;
import app.quote.license.QuoteLicense;
import app.quote.license.QuoteLicense.Grant;
import app.quote.pipeline.QuoteProcessor;
import app.quote.pipeline.QuoteStatus;
import app.quote.pipeline.RawQuote;
import app.quote.support.FixedClockConfig;
import app.quote.support.MutableClock;
import app.quote.support.QuoteAppTest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * 报价对外（第 3 批，验收标准 AC-Q1～Q5、Q8、Q11）：真实的报价服务（随机端口、真实 MySQL）+ 按开发者文档签名的客户端。
 * 授权检查强制打开（quote.license.enforce=true，等同生产）；报价源用 mock 的路由给出（默认都在用 alltick 主源）；时钟固定在 2026-10-14（周三）10:00 UTC。
 * 每个测试自己建客户和 Key，测完清掉 Key、客户、设置、用量、授权。
 */
@QuoteAppTest
@Import(FixedClockConfig.class)
@TestPropertySource(properties = "quote.license.enforce=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OpenServiceTest {

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired Clock clock;
  @Autowired ApiKeys keys;
  @Autowired OpenSettings settings;
  @Autowired LicenseRegistry licenses;
  @Autowired QuoteProcessor processor;
  @Autowired Usage usage;
  @Autowired List<AlertCheck> alerts;
  @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
  @MockitoBean QuoteRouter router;

  OpenClient c;
  int pips;

  record K(String id, String secret) {}

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
    jdbc.update("DELETE FROM quote_usage_daily");
    jdbc.update("DELETE FROM quote_source_license");
    keys.reload();
    settings.reload();
    licenses.reload();
    ((MutableClock) clock).reset();
  }

  // ---- 准备 ----

  K key(String kind, String... symbols) throws Exception {
    String name = "测试客户" + System.nanoTime();
    var cl = c.internal("POST", "/internal/open/clients", "{\"name\":\"" + name + "\",\"email\":\"dev@example.com\",\"note\":\"测试\"}");
    assertThat(cl.status()).isEqualTo(200);
    String syms = symbols.length == 0 ? "[]" : "[\"" + String.join("\",\"", symbols) + "\"]";
    var k = c.internal("POST", "/internal/open/keys", "{\"clientId\":" + cl.body().path("id").asLong() + ",\"kind\":\"" + kind + "\",\"symbols\":" + syms + "}");
    assertThat(k.status()).isEqualTo(200);
    return new K(k.body().path("key").path("keyId").asString(), k.body().path("secret").asString());
  }

  void gate(boolean open) throws Exception {
    var r = c.internal("PUT", "/internal/open/gate", "{\"open\":" + open + ",\"amosApproved\":true,\"by\":\"owner\",\"reason\":\"测试\"}");
    assertThat(r.status()).isEqualTo(200);
  }

  void resale(String feed, String symbol) {
    licenses.save(new QuoteLicense(feed, symbol, Grant.NONE, Grant.NONE, new Grant(true, null, null), "测试"));
  }

  void push(String symbol) {
    BigDecimal bid = new BigDecimal("1.08000").add(BigDecimal.valueOf(pips++, 5));
    assertThat(processor.process(new RawQuote(symbol, bid, bid.add(new BigDecimal("0.00010")), clock.instant()))).isEqualTo(QuoteStatus.FRESH);
  }

  void limits(String keyId, int rps, int daily, int ws, int syms) throws Exception {
    var r = c.internal("PUT", "/internal/open/keys/" + keyId + "/limits",
        "{\"restPerSecond\":" + rps + ",\"restPerDay\":" + daily + ",\"wsConnections\":" + ws + ",\"wsSymbols\":" + syms + ",\"by\":\"owner\",\"reason\":\"测试\"}");
    assertThat(r.status()).isEqualTo(200);
  }

  // ---- AC-Q1 两道闸 ----

  @Test
  void AC_Q1_总开关默认关_外部Key的REST返回403_WS握手被拒_内部测试Key正常() throws Exception {
    assertThat(settings.gateOpen()).as("默认关").isFalse();
    K ext = key("EXTERNAL");
    K internal = key("INTERNAL_TEST");
    var r = c.call(ext.id(), ext.secret(), "GET", "/v1/symbols");
    assertThat(r.status()).isEqualTo(403);
    assertThat(r.body().path("code").asString()).isEqualTo("OPEN_SERVICE_CLOSED");
    assertThat(r.body().path("traceId").asString()).isNotBlank();
    assertThat(c.call(ext.id(), ext.secret(), "POST", "/v1/ws-ticket").status()).as("关着时外部 Key 换票据也 403").isEqualTo(403);
    assertThat(c.call(internal.id(), internal.secret(), "GET", "/v1/symbols").status()).isEqualTo(200);
    assertThat(c.handshakeStatus(internal.id(), internal.secret())).isEqualTo(101);

    // 开着时外部 Key 拿到票据，之后总开关关掉：握手被拒（403），已连的断开（4403）
    gate(true);
    resale("alltick", "EURUSD");
    var t = c.call(ext.id(), ext.secret(), "POST", "/v1/ws-ticket");
    assertThat(t.status()).isEqualTo(200);
    try (OpenClient.Ws live = c.connect(ext.id(), ext.secret())) {
      await("hello", () -> !live.of("hello").isEmpty());
      gate(false);
      await("总开关关掉后外部连接断开", Duration.ofSeconds(5), () -> live.closeCode == 4403);
    }
    try {
      c.connectTicket(t.body().path("ticket").asString()).close();
      throw new AssertionError("总开关关着，外部 Key 也连上了");
    } catch (java.util.concurrent.CompletionException e) {
      assertThat(((java.net.http.WebSocketHandshakeException) e.getCause()).getResponse().statusCode()).isEqualTo(403);
    }
    assertThat(c.call(ext.id(), ext.secret(), "GET", "/v1/symbols").status()).isEqualTo(403);
  }

  @Test
  void AC_Q1_打开总开关要勾Amos已同意和原因_配置里没有能打开它的开关() throws Exception {
    assertThat(c.internal("PUT", "/internal/open/gate", "{\"open\":true,\"by\":\"owner\",\"reason\":\"测试\"}").status()).isEqualTo(400);
    assertThat(c.internal("PUT", "/internal/open/gate", "{\"open\":true,\"amosApproved\":true,\"by\":\"owner\"}").status()).isEqualTo(400);
    assertThat(settings.gateOpen()).isFalse();
    String yml = new String(getClass().getClassLoader().getResourceAsStream("quote.yml").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(yml).doesNotContain("gate-open").doesNotContain("FX_QUOTE_OPEN_GATE");
    jdbc.update("INSERT INTO quote_open_setting (name, value) VALUES ('gate_open', 'yes')");
    settings.reload();
    assertThat(settings.gateOpen()).as("值不是 true 都按关").isFalse();
  }

  @Test
  void AC_Q1_开关开但没登记转售授权_外部Key拿不到这个品种_列表报价订阅K线都没有() throws Exception {
    gate(true);
    K ext = key("EXTERNAL");
    K internal = key("INTERNAL_TEST");
    resale("alltick", "EURUSD");
    push("EURUSD");
    push("GBPUSD");
    Set<String> codes = new HashSet<>();
    for (JsonNode s : c.call(ext.id(), ext.secret(), "GET", "/v1/symbols").body().path("items")) codes.add(s.path("code").asString());
    assertThat(codes).containsExactly("EURUSD");
    var q = c.call(ext.id(), ext.secret(), "GET", "/v1/quotes?symbols=EURUSD,GBPUSD");
    assertThat(q.body().path("items")).hasSize(1);
    assertThat(q.body().path("unavailable").get(0).asString()).isEqualTo("GBPUSD");
    assertThat(c.call(ext.id(), ext.secret(), "GET", "/v1/klines?symbol=GBPUSD&tf=1m").status()).isEqualTo(400);
    // 内部测试 Key 不看转售授权
    assertThat(c.call(internal.id(), internal.secret(), "GET", "/v1/symbols").body().path("items").size()).isGreaterThan(1);
    try (OpenClient.Ws ws = c.connect(ext.id(), ext.secret())) {
      await("hello", () -> !ws.of("hello").isEmpty());
      ws.send("{\"op\":\"subscribe\",\"symbols\":[\"EURUSD\",\"GBPUSD\"]}");
      await("订阅回执", () -> !ws.of("subscribed").isEmpty());
      assertThat(ws.of("subscribed").getFirst().path("symbols").toString()).isEqualTo("[\"EURUSD\"]");
      JsonNode err = ws.of("error").getFirst();
      assertThat(err.path("code").asString()).isEqualTo("SYMBOL_NOT_AVAILABLE");
      assertThat(err.path("symbols").toString()).contains("GBPUSD");
      push("GBPUSD");
      push("EURUSD");
      await("EURUSD 推过来", () -> ws.quotes("EURUSD").size() >= 2);
      assertThat(ws.quotes("GBPUSD")).isEmpty();
    }
  }

  @Test
  void AC_Q1_切到没有转售授权的备源_对外这个品种暂无报价_不输出价格() throws Exception {
    gate(true);
    K ext = key("EXTERNAL");
    resale("alltick", "EURUSD");
    push("EURUSD");
    try (OpenClient.Ws ws = c.connect(ext.id(), ext.secret())) {
      await("hello", () -> !ws.of("hello").isEmpty());
      ws.send("{\"op\":\"subscribe\",\"symbols\":[\"EURUSD\"]}");
      await("快照", () -> !ws.quotes("EURUSD").isEmpty());
      assertThat(ws.quotes("EURUSD").getFirst().path("status").asString()).isEqualTo("OPEN");
      assertThat(ws.quotes("EURUSD").getFirst().has("bid")).isTrue();
      when(router.current("EURUSD")).thenReturn(Optional.of(new QuoteSource("itick", "backup")));
      push("EURUSD");
      await("切到备源后的一条", () -> ws.quotes("EURUSD").size() >= 2);
      JsonNode last = ws.quotes("EURUSD").getLast();
      assertThat(last.path("status").asString()).isEqualTo("NO_QUOTE");
      assertThat(last.has("bid")).isFalse();
      assertThat(last.has("ask")).isFalse();
    }
    var q = c.call(ext.id(), ext.secret(), "GET", "/v1/quotes?symbols=EURUSD");
    assertThat(q.body().path("unavailable").get(0).asString()).as("现在在用的源没授权，列表和报价都拿不到").isEqualTo("EURUSD");
  }

  // ---- 审查 🔴：畸形路径不能绕过内部 Key 和对外签名 ----

  @Test
  void 畸形路径_分号_编码字母_编码斜杠_点点_都被拒_无Key不能开总开关也不能建外部Key() throws Exception {
    long clientId = c.internal("POST", "/internal/open/clients", "{\"name\":\"路径测试\"}").body().path("id").asLong();
    String openGate = "{\"open\":true,\"amosApproved\":true,\"by\":\"x\",\"reason\":\"x\"}";
    String extKey = "{\"clientId\":" + clientId + ",\"kind\":\"EXTERNAL\",\"symbols\":[]}";
    List<String> internalPaths = List.of("/internal;x/open/overview", "/%69nternal/open/overview", "/internal/open;x/overview", "/internal%2Fopen/overview",
        "/v1/../internal/open/overview", "/v1/%2e%2e/internal/open/overview", "//internal/open/overview", "/internal//open/overview", "/internal/./open/overview");
    for (String p : internalPaths) {
      int st = c.bare("GET", p, null).status();
      assertThat(st).as(p).isIn(400, 401, 404);
    }
    for (String p : List.of("/internal;x/open/gate", "/%69nternal/open/gate", "/internal/open/gate;x", "/internal/open/%67ate")) {
      assertThat(c.bare("PUT", p, openGate).status()).as(p).isIn(400, 401, 404);
    }
    for (String p : List.of("/internal;x/open/keys", "/%69nternal/open/keys", "/internal/open/keys;x")) {
      assertThat(c.bare("POST", p, extKey).status()).as(p).isIn(400, 401, 404);
    }
    assertThat(c.bare("PUT", "/internal/open/gate", openGate).status()).as("正常路径没有内部 Key").isEqualTo(401);
    settings.reload();
    assertThat(settings.gateOpen()).as("总开关还是关").isFalse();
    keys.reload();
    assertThat(keys.keys()).as("没有建出任何 Key").isEmpty();
    // 对外 /v1/**：畸形路径 400，不会跳过验签
    for (String p : List.of("/v1;x/symbols", "/v1/symbols;x", "/%76" + "1/symbols", "/v1/%73ymbols", "/v1//symbols", "/v1/./symbols")) {
      assertThat(c.bare("GET", p, null).status()).as(p).isIn(400, 401);
    }
    assertThat(PathGuard.bad("/internal;x/open/gate")).isTrue();
  }

  /** 路径守卫的判断（单独测，不经过 HTTP 客户端的规范化） */
  static final class PathGuard {
    static boolean bad(String p) {
      return app.quote.web.PathGuardFilter.malformed(p);
    }
  }

  @Test
  void 路径守卫判断() {
    for (String p : List.of("/internal;x/a", "/%69nternal/a", "/a%2Fb", "/a/../b", "/a/..", "/a/./b", "//a", "/a\\b")) assertThat(PathGuard.bad(p)).as(p).isTrue();
    for (String p : List.of("/internal/open/overview", "/v1/symbols", "/docs/", "/v1/ws", "/internal/ws/quotes", "/api/health")) assertThat(PathGuard.bad(p)).as(p).isFalse();
  }

  // ---- 审查 🟡：防重放、限额计数在 Redis ----

  @Test
  void 防重放_同一个nonce只能用一次_签名不对的不占nonce() throws Exception {
    K k = key("INTERNAL_TEST");
    assertThat(c.callNonce(k.id(), "qs_wrong", "GET", "/v1/symbols", "nonce-abcdef-1").status()).isEqualTo(401);
    assertThat(c.callNonce(k.id(), k.secret(), "GET", "/v1/symbols", "nonce-abcdef-1").status()).as("乱签名没有占掉 nonce").isEqualTo(200);
    var again = c.callNonce(k.id(), k.secret(), "GET", "/v1/symbols", "nonce-abcdef-1");
    assertThat(again.status()).as("同一个请求原样重放").isEqualTo(401);
    assertThat(again.body().path("message").asString()).contains("已经用过");
    assertThat(c.callNonce(k.id(), k.secret(), "GET", "/v1/symbols", "short").status()).as("nonce 太短").isEqualTo(401);
    assertThat(c.raw("GET", "/v1/symbols", k.id(), String.valueOf(clock.instant().getEpochSecond()), "x", null).status()).as("没带 nonce").isEqualTo(401);
    assertThat(redis.hasKey("fxq:nonce:" + k.id() + ":nonce-abcdef-1")).isTrue();
    assertThat(redis.getExpire("fxq:nonce:" + k.id() + ":nonce-abcdef-1")).isBetween(1L, 600L);
  }

  @Test
  void 限额计数在Redis_另一个实例用掉的也算_不超发() throws Exception {
    K k = key("INTERNAL_TEST");
    limits(k.id(), 100, 5, 2, 50);
    assertThat(c.call(k.id(), k.secret(), "GET", "/v1/symbols").status()).isEqualTo(200);
    String dk = "fxq:rl:d:" + k.id() + ":2026-10-14";
    assertThat(redis.opsForValue().get(dk)).isEqualTo("1");
    // 模拟另一个实例已经放行了 3 次
    redis.opsForValue().increment(dk, 3);
    assertThat(c.call(k.id(), k.secret(), "GET", "/v1/symbols").status()).as("第 5 次").isEqualTo(200);
    assertThat(c.call(k.id(), k.secret(), "GET", "/v1/symbols").status()).as("合计超过 5 次").isEqualTo(429);
    assertThat(redis.opsForValue().get(dk)).as("被拒的不计").isEqualTo("5");
    // 每秒计数也在 Redis：另一个实例这一秒已经用满
    K k2 = key("INTERNAL_TEST");
    redis.opsForValue().set("fxq:rl:s:" + k2.id() + ":" + clock.instant().getEpochSecond(), "10");
    assertThat(c.call(k2.id(), k2.secret(), "GET", "/v1/symbols").status()).isEqualTo(429);
    // WebSocket 连接数也按所有实例合计
    ((MutableClock) clock).advance(Duration.ofSeconds(1));
    // （第 3 批复审后连接数改成租约：有序集合，成员 = 租约号，分数 = 到期毫秒）
    long until = clock.millis() + 60_000;
    redis.opsForZSet().add("fxq:ws:" + k2.id(), "other-1", until);
    redis.opsForZSet().add("fxq:ws:" + k2.id(), "other-2", until);
    assertThat(c.handshakeStatus(k2.id(), k2.secret())).as("别的实例已经占了 2 条").isEqualTo(429);
    redis.opsForZSet().remove("fxq:ws:" + k2.id(), "other-2");
    assertThat(c.handshakeStatus(k2.id(), k2.secret())).isEqualTo(101);
  }

  // ---- 复审：WebSocket 连接名额按租约，崩溃没释放的最多占一个租约期（60 秒） ----

  @Test
  void 连接名额是租约_崩溃没释放的60秒后不算_连着的定时续租_断开立即释放() throws Exception {
    K k = key("INTERNAL_TEST");
    limits(k.id(), 100, 1000, 1, 50);
    String zk = "fxq:ws:" + k.id();
    // 模拟另一个实例连上后崩溃：留下一个租约，没人续
    redis.opsForZSet().add(zk, "crashed", clock.millis() + 60_000);
    assertThat(c.handshakeStatus(k.id(), k.secret())).isEqualTo(429);
    ((MutableClock) clock).advance(Duration.ofSeconds(61));
    assertThat(usage.connections(k.id())).as("租约过期就不算").isZero();
    try (OpenClient.Ws ws = c.connect(k.id(), k.secret())) {
      await("hello", () -> !ws.of("hello").isEmpty());
      assertThat(usage.connections(k.id())).isEqualTo(1);
      assertThat(redis.getExpire(zk)).as("整个键也有过期，不会永久留着").isBetween(1L, 120L);
      // 连着的：过 50 秒续一次，再过 30 秒（离领租约 80 秒）还算
      ((MutableClock) clock).advance(Duration.ofSeconds(50));
      usage.renewLeases();
      ((MutableClock) clock).advance(Duration.ofSeconds(30));
      assertThat(usage.connections(k.id())).as("续过租约").isEqualTo(1);
      assertThat(c.handshakeStatus(k.id(), k.secret())).as("名额还占着").isEqualTo(429);
    }
    await("断开立即释放", () -> usage.connections(k.id()) == 0);
    assertThat(c.handshakeStatus(k.id(), k.secret())).isEqualTo(101);
  }

  // ---- AC-Q2 Key 管理 ----

  @Test
  void AC_Q2_Secret只在创建时出现一次_之后接口拿不到_库里不是明文() throws Exception {
    K k = key("INTERNAL_TEST");
    assertThat(k.secret()).startsWith("qs_").hasSizeGreaterThan(30);
    assertThat(k.id()).startsWith("qk_");
    String overview = c.internal("GET", "/internal/open/overview", null).raw().body();
    assertThat(overview).contains(k.id()).doesNotContain(k.secret()).doesNotContain("secret");
    byte[] stored = jdbc.queryForObject("SELECT secret_enc FROM quote_api_key WHERE key_id = ?", byte[].class, k.id());
    assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(k.secret());
    assertThat(c.call(k.id(), k.secret(), "GET", "/v1/usage").raw().body()).doesNotContain(k.secret());
  }

  @Test
  void AC_Q2_签名不对_时间戳过期_没带请求头_都是401() throws Exception {
    K k = key("INTERNAL_TEST");
    assertThat(c.raw("GET", "/v1/symbols", null, null, null).status()).isEqualTo(401);
    assertThat(c.call(k.id(), "qs_wrong", "GET", "/v1/symbols").status()).isEqualTo(401);
    String old = String.valueOf(clock.instant().getEpochSecond() - 301);
    String sig = OpenAuthFilter.sign(k.secret().getBytes(java.nio.charset.StandardCharsets.UTF_8), old + "\nnonce-expired-1\nGET\n/v1/symbols\n");
    assertThat(c.raw("GET", "/v1/symbols", k.id(), old, sig, "nonce-expired-1").status()).isEqualTo(401);
    assertThat(c.call(k.id(), k.secret(), "GET", "/v1/symbols").status()).isEqualTo(200);
    assertThat(c.call(k.id(), k.secret(), "GET", "/v1/quotes?symbols=EURUSD").status()).as("查询串参与签名").isEqualTo(200);
  }

  @Test
  void AC_Q2_停用后5秒内REST返回401_已连WebSocket断开() throws Exception {
    K k = key("INTERNAL_TEST");
    try (OpenClient.Ws ws = c.connect(k.id(), k.secret())) {
      await("hello", () -> !ws.of("hello").isEmpty());
      long t0 = System.nanoTime();
      assertThat(c.internal("POST", "/internal/open/keys/" + k.id() + "/disable", null).status()).isEqualTo(200);
      assertThat(c.call(k.id(), k.secret(), "GET", "/v1/symbols").status()).isEqualTo(401);
      await("WebSocket 断开", Duration.ofSeconds(5), () -> ws.closeCode == 4401);
      assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
    }
  }

  @Test
  void AC_Q2_别的途径停用_每秒检查一次_也在5秒内断开() throws Exception {
    K k = key("INTERNAL_TEST");
    try (OpenClient.Ws ws = c.connect(k.id(), k.secret())) {
      await("hello", () -> !ws.of("hello").isEmpty());
      jdbc.update("UPDATE quote_api_key SET status = 'DISABLED' WHERE key_id = ?", k.id());
      keys.reload();
      await("WebSocket 断开", Duration.ofSeconds(5), () -> ws.closeCode == 4401);
    }
  }

  @Test
  void AC_Q2_轮换_新Secret只显示一次_旧Key立即401_新Key继承限额() throws Exception {
    K old = key("INTERNAL_TEST");
    limits(old.id(), 7, 777, 1, 5);
    var r = c.internal("POST", "/internal/open/keys/" + old.id() + "/rotate", null);
    assertThat(r.status()).isEqualTo(200);
    K neu = new K(r.body().path("key").path("keyId").asString(), r.body().path("secret").asString());
    assertThat(neu.id()).isNotEqualTo(old.id());
    assertThat(r.body().path("key").path("rotatedFrom").asString()).isEqualTo(old.id());
    assertThat(r.body().path("key").path("limits").path("restPerDay").asInt()).isEqualTo(777);
    assertThat(c.call(old.id(), old.secret(), "GET", "/v1/symbols").status()).isEqualTo(401);
    assertThat(c.call(neu.id(), neu.secret(), "GET", "/v1/symbols").status()).isEqualTo(200);
    assertThat(c.internal("POST", "/internal/open/keys/" + old.id() + "/rotate", null).status()).as("停用的不能再轮换").isEqualTo(400);
  }

  // ---- AC-Q3 限额 ----

  @Test
  void AC_Q3_新建Key用默认限额_改默认值不影响已建Key() throws Exception {
    K a = key("INTERNAL_TEST");
    JsonNode la = c.call(a.id(), a.secret(), "GET", "/v1/usage").body().path("limits");
    assertThat(la.path("restPerSecond").asInt()).isEqualTo(10);
    assertThat(la.path("restPerDay").asInt()).isEqualTo(100_000);
    assertThat(la.path("wsConnections").asInt()).isEqualTo(2);
    assertThat(la.path("wsSymbolsPerConnection").asInt()).isEqualTo(50);
    var d = c.internal("PUT", "/internal/open/defaults", "{\"restPerSecond\":20,\"restPerDay\":5000,\"wsConnections\":3,\"wsSymbols\":10,\"by\":\"owner\",\"reason\":\"测试\"}");
    assertThat(d.status()).isEqualTo(200);
    assertThat(c.call(a.id(), a.secret(), "GET", "/v1/usage").body().path("limits").path("restPerDay").asInt()).as("已建的 Key 不变").isEqualTo(100_000);
    K b = key("INTERNAL_TEST");
    assertThat(c.call(b.id(), b.secret(), "GET", "/v1/usage").body().path("limits").path("restPerDay").asInt()).isEqualTo(5000);
    assertThat(c.internal("PUT", "/internal/open/defaults", "{\"restPerSecond\":0,\"restPerDay\":1,\"wsConnections\":1,\"wsSymbols\":1}").status())
        .as("要是正整数").isEqualTo(400);
  }

  @Test
  void AC_Q3_超每秒请求数返回429带RetryAfter_只拒超出的_下一秒恢复_被拒的不算请求数() throws Exception {
    K k = key("INTERNAL_TEST");
    List<Integer> codes = new ArrayList<>();
    for (int i = 0; i < 12; i++) codes.add(c.call(k.id(), k.secret(), "GET", "/v1/symbols").status());
    assertThat(codes.subList(0, 10)).containsOnly(200);
    assertThat(codes.subList(10, 12)).containsOnly(429);
    var r = c.call(k.id(), k.secret(), "GET", "/v1/symbols");
    assertThat(r.status()).isEqualTo(429);
    assertThat(r.raw().headers().firstValue("Retry-After")).contains("1");
    assertThat(r.body().path("code").asString()).isEqualTo("RATE_LIMITED");
    assertThat(r.body().path("remaining").asInt()).isZero();
    assertThat(r.body().path("resetAt").asLong()).isPositive();
    ((MutableClock) clock).advance(Duration.ofSeconds(1));
    JsonNode u = c.call(k.id(), k.secret(), "GET", "/v1/usage").body();
    assertThat(u.path("today").path("restRequests").asLong()).as("10 次 + 这次查用量").isEqualTo(11);
    assertThat(u.path("today").path("restRejected").asLong()).isEqualTo(3);
  }

  @Test
  void AC_Q3_超每天请求数返回429_RetryAfter到UTC零点_改限额立即生效() throws Exception {
    K k = key("INTERNAL_TEST");
    limits(k.id(), 100, 3, 2, 50);
    for (int i = 0; i < 3; i++) assertThat(c.call(k.id(), k.secret(), "GET", "/v1/symbols").status()).isEqualTo(200);
    var r = c.call(k.id(), k.secret(), "GET", "/v1/symbols");
    assertThat(r.status()).isEqualTo(429);
    assertThat(r.body().path("scope").asString()).isEqualTo("day");
    assertThat(r.raw().headers().firstValue("Retry-After")).contains(String.valueOf(14 * 3600)); // 10:00 → 次日 00:00
    limits(k.id(), 100, 10, 2, 50);
    assertThat(c.call(k.id(), k.secret(), "GET", "/v1/symbols").status()).as("改限额后立即生效（要求 1 分钟内）").isEqualTo(200);
  }

  @Test
  void AC_Q3_第N加1条WebSocket连接被拒_超订阅品种数的订阅返回错误_已订阅的照常推送() throws Exception {
    K k = key("INTERNAL_TEST");
    limits(k.id(), 100, 1000, 2, 2);
    push("EURUSD");
    try (OpenClient.Ws a = c.connect(k.id(), k.secret()); OpenClient.Ws b = c.connect(k.id(), k.secret())) {
      await("两条都连上", () -> !a.of("hello").isEmpty() && !b.of("hello").isEmpty());
      assertThat(c.handshakeStatus(k.id(), k.secret())).isEqualTo(429);
      a.send("{\"op\":\"subscribe\",\"symbols\":[\"EURUSD\"]}");
      await("订阅生效", () -> !a.of("subscribed").isEmpty());
      a.send("{\"op\":\"subscribe\",\"symbols\":[\"EURUSD\",\"GBPUSD\",\"USDJPY\"]}");
      await("超了的错误", () -> !a.of("error").isEmpty());
      assertThat(a.of("error").getFirst().path("code").asString()).isEqualTo("SUBSCRIBE_LIMIT");
      int before = a.quotes("EURUSD").size();
      push("EURUSD");
      await("已订阅的照常推", () -> a.quotes("EURUSD").size() > before);
    }
    await("断开后连接数还回来", () -> usage.connections(k.id()) == 0);
    assertThat(c.handshakeStatus(k.id(), k.secret())).isEqualTo(101);
  }

  // ---- AC-Q4 计量准确、AC-Q11 只计量不收费 ----

  @Test
  void AC_Q4_发1000次REST_订阅10个品种_用量和脚本计数完全一致() throws Exception {
    K k = key("INTERNAL_TEST");
    limits(k.id(), 10_000, 100_000, 2, 50);
    for (int i = 0; i < 1000; i++) assertThat(c.call(k.id(), k.secret(), "GET", "/v1/quotes?symbols=EURUSD").status()).isEqualTo(200);
    List<String> ten = List.of("EURUSD", "GBPUSD", "USDJPY", "USDCHF", "USDCAD", "AUDUSD", "NZDUSD", "EURGBP", "EURJPY", "GBPJPY");
    int received;
    try (OpenClient.Ws ws = c.connect(k.id(), k.secret())) {
      await("hello", () -> !ws.of("hello").isEmpty());
      ws.send("{\"op\":\"subscribe\",\"symbols\":[\"" + String.join("\",\"", ten) + "\"]}");
      await("订阅生效", () -> !ws.of("subscribed").isEmpty());
      for (int round = 0; round < 20; round++) for (String s : ten) push(s);
      await("200 条都收到", () -> ws.of("quote").size() >= 200);
      Thread.sleep(200);
      received = ws.of("quote").size();
      ((MutableClock) clock).advance(Duration.ofSeconds(600)); // 连了 10 分钟
      Thread.sleep(1500); // 每秒检查一次时记连接时长
    }
    await("断开", () -> usage.connections(k.id()) == 0);
    JsonNode today = c.call(k.id(), k.secret(), "GET", "/v1/usage").body().path("today");
    assertThat(today.path("restRequests").asLong()).as("1000 次 + ws-ticket 1 次 + 这次查用量 1 次").isEqualTo(1002);
    assertThat(today.path("wsPushes").asLong()).as("推送条数和客户端收到的一致").isEqualTo(received);
    assertThat(today.path("wsConnectionSeconds").asLong()).isGreaterThanOrEqualTo(600);
    assertThat(today.path("wsPeakConnections").asInt()).isEqualTo(1);
    // 后台用量（按品种的明细）和对外用量接口一致
    JsonNode admin = c.internal("GET", "/internal/open/usage?keyId=" + k.id(), null).body();
    long rest = 0;
    long pushes = 0;
    for (JsonNode r : admin.path("rows")) {
      rest += r.path("restCalls").asLong();
      pushes += r.path("wsPushes").asLong();
    }
    assertThat(rest).isEqualTo(1002);
    assertThat(pushes).isEqualTo(received);
    long quotesRow = 0;
    for (JsonNode r : admin.path("rows")) if ("quotes".equals(r.path("endpoint").asString())) quotesRow = r.path("restCalls").asLong();
    assertThat(quotesRow).as("按接口记").isEqualTo(1000);
    assertThat(admin.path("rows").toString()).as("按品种记推送").contains("\"symbol\":\"GBPJPY\"");
  }

  @Test
  void AC_Q11_对外接口和后台接口里没有账单_价格_支付字段() throws Exception {
    K k = key("INTERNAL_TEST");
    push("EURUSD");
    List<JsonNode> bodies = List.of(c.call(k.id(), k.secret(), "GET", "/v1/usage").body(), c.internal("GET", "/internal/open/overview", null).body(),
        c.internal("GET", "/internal/open/usage", null).body());
    Pattern billing = Pattern.compile("(?i)(bill|invoice|price|payment|charge|fee|amount|plan|subscription_fee)");
    for (JsonNode b : bodies) for (String f : fieldNames(b)) assertThat(billing.matcher(f).find()).as("字段 " + f).isFalse();
  }

  @Test
  void 用量保留13个月_导出CSV() throws Exception {
    K k = key("INTERNAL_TEST");
    c.call(k.id(), k.secret(), "GET", "/v1/symbols");
    LocalDate today = LocalDate.of(2026, 10, 14);
    jdbc.update("INSERT INTO quote_usage_daily (day, key_id, endpoint, symbol, rest_calls) VALUES (?, ?, 'symbols', '', 5)", today.minusMonths(13).minusDays(1), k.id());
    jdbc.update("INSERT INTO quote_usage_daily (day, key_id, endpoint, symbol, rest_calls) VALUES (?, ?, 'symbols', '', 6)", today.minusMonths(12), k.id());
    assertThat(usage.purge()).isEqualTo(1);
    var csv = c.internal("GET", "/internal/open/usage/export?from=" + today.minusMonths(13) + "&to=" + today, null);
    assertThat(csv.status()).isEqualTo(200);
    assertThat(csv.raw().headers().firstValue("Content-Disposition").orElse("")).contains("attachment");
    String body = csv.raw().body();
    assertThat(body).startsWith("﻿日期,Key,接口,品种,REST 请求数");
    assertThat(body).contains(today + "," + k.id() + ",REST /v1/symbols,,1");
    assertThat(body).contains(today.minusMonths(12) + "," + k.id() + ",REST /v1/symbols,,6");
  }

  @Test
  void 用量到80提醒_授权30天内到期提醒() throws Exception {
    K k = key("INTERNAL_TEST");
    limits(k.id(), 100, 10, 2, 50);
    for (int i = 0; i < 7; i++) c.call(k.id(), k.secret(), "GET", "/v1/symbols");
    assertThat(item("quote.usage80").ok()).isTrue();
    c.call(k.id(), k.secret(), "GET", "/v1/symbols");
    AlertCheck.Item u = item("quote.usage80");
    assertThat(u.ok()).isFalse();
    assertThat(u.detail()).contains(k.id());
    licenses.save(new QuoteLicense("alltick", "XAUUSD", new Grant(true, null, LocalDate.of(2026, 11, 13)), Grant.NONE, Grant.NONE, null));
    assertThat(item("quote.license_expiry").ok()).as("还剩 30 天").isFalse();
    assertThat(item("quote.license_expiry").detail()).contains("alltick × XAUUSD 交易用途 2026-11-13 到期（还剩 30 天）");
    licenses.save(new QuoteLicense("alltick", "XAUUSD", new Grant(true, null, LocalDate.of(2026, 11, 14)), Grant.NONE, Grant.NONE, null));
    assertThat(item("quote.license_expiry").ok()).as("还剩 31 天不提醒").isTrue();
    var lic = c.internal("GET", "/internal/open/licenses", null).body();
    assertThat(lic.path("items").get(0).path("trading").path("state").asString()).isEqualTo("ok");
    assertThat(lic.path("notTradable").toString()).as("XAUUSD 有交易用途，EURUSD 没有").contains("EURUSD").doesNotContain("XAUUSD");
  }

  @Test
  void P11_报价源状态_在用哪家_最后报价时间_24小时切换和偏差次数() throws Exception {
    push("USDCHF");
    when(router.current("USDCHF")).thenReturn(Optional.of(new QuoteSource("itick", "backup")));
    push("USDCHF");
    when(router.current("USDCHF")).thenReturn(Optional.of(new QuoteSource("itick", "deviation")));
    push("USDCHF");
    JsonNode body = c.internal("GET", "/internal/open/sources", null).body();
    JsonNode row = null;
    for (JsonNode r : body.path("rows")) if ("USDCHF".equals(r.path("symbol").asString())) row = r;
    assertThat(row).isNotNull();
    assertThat(row.path("current").asString()).isEqualTo("itick");
    assertThat(row.path("switches24h").asInt()).isEqualTo(1);
    assertThat(row.path("deviations24h").asInt()).isEqualTo(1);
    assertThat(row.path("state").asString()).isEqualTo("deviation");
    assertThat(row.path("lastQuoteAt").asString()).isEqualTo("2026-10-14T10:00:00Z");
    assertThat(body.path("feeds").size()).isEqualTo(3);
    assertThat(body.path("mode").asString()).isEqualTo("none");
    ((MutableClock) clock).advance(Duration.ofHours(25));
    for (JsonNode r : c.internal("GET", "/internal/open/sources", null).body().path("rows")) {
      if ("USDCHF".equals(r.path("symbol").asString())) assertThat(r.path("switches24h").asInt()).as("超过 24 小时不算").isZero();
    }
  }

  AlertCheck.Item item(String name) {
    for (AlertCheck a : alerts) for (AlertCheck.Item i : a.evaluate()) if (i.name().equals(name)) return i;
    throw new AssertionError("没有告警项 " + name);
  }

  // ---- AC-Q5 字段和文档一致、AC-Q8 不泄露 ----

  @Test
  void AC_Q5_AC_Q8_每个接口的字段和开发者文档逐字段一致_外部Key收到的只有白名单字段() throws Exception {
    gate(true);
    K k = key("EXTERNAL");
    for (String s : List.of("EURUSD", "GBPUSD")) resale("alltick", s);
    jdbc.update("UPDATE quote_symbol SET name_zh = '欧元/美元', name_en = 'EUR/USD' WHERE code = 'EURUSD'");
    for (int i = 0; i < 3; i++) push("EURUSD");
    Map<String, Set<String>> doc = docFields();
    assertThat(doc.keySet()).contains("symbols.item", "quotes.item", "klines.item", "usage.limits", "usage.day", "ticket", "ws.quote", "ws.hello", "ws.bar");

    JsonNode sym = c.call(k.id(), k.secret(), "GET", "/v1/symbols").body().path("items").get(0);
    assertThat(names(sym)).isEqualTo(doc.get("symbols.item"));
    JsonNode q = c.call(k.id(), k.secret(), "GET", "/v1/quotes?symbols=EURUSD").body().path("items").get(0);
    assertThat(names(q)).isEqualTo(doc.get("quotes.item"));
    JsonNode kl = c.call(k.id(), k.secret(), "GET", "/v1/klines?symbol=EURUSD&tf=1m").body();
    assertThat(names(kl.path("items").get(0))).isEqualTo(doc.get("klines.item"));
    JsonNode u = c.call(k.id(), k.secret(), "GET", "/v1/usage").body();
    assertThat(names(u.path("limits"))).isEqualTo(doc.get("usage.limits"));
    Set<String> day = names(u.path("days").get(0));
    assertThat(day).isEqualTo(doc.get("usage.day"));
    assertThat(names(c.call(k.id(), k.secret(), "POST", "/v1/ws-ticket").body())).isEqualTo(doc.get("ticket"));

    // K 线和交易应用图表（trading-app 转发的内部接口，按品种小数位格式化）同一根数值相同
    JsonNode internalBar = c.internal("GET", "/internal/klines?symbol=EURUSD&tf=1m&limit=1", null).body().get(0);
    JsonNode bar = kl.path("items").get(kl.path("items").size() - 1);
    assertThat(bar.path("time").asLong()).isEqualTo(internalBar.path("time").asLong());
    for (String f : List.of("open", "high", "low", "close")) {
      assertThat(new BigDecimal(bar.path(f).asString())).isEqualByComparingTo(new BigDecimal(internalBar.path(f).asString()).setScale(5, java.math.RoundingMode.HALF_UP));
    }

    try (OpenClient.Ws ws = c.connect(k.id(), k.secret())) {
      await("hello", () -> !ws.of("hello").isEmpty());
      ws.send("{\"op\":\"subscribe\",\"symbols\":[\"EURUSD\"]}");
      await("订阅生效", () -> !ws.of("subscribed").isEmpty());
      ws.send("{\"op\":\"watchBars\",\"keys\":[\"EURUSD|1m\"]}");
      // watchBars 没有回执：隔一会儿推一条，直到收到 K 线变化
      await("报价、K 线、心跳", () -> {
        if (ws.of("bar").isEmpty()) push("EURUSD");
        return ws.quotes("EURUSD").size() >= 2 && !ws.of("bar").isEmpty() && !ws.of("heartbeat").isEmpty();
      });
      assertThat(names(ws.of("hello").getFirst())).isEqualTo(doc.get("ws.hello"));
      assertThat(names(ws.of("subscribed").getFirst())).isEqualTo(doc.get("ws.subscribed"));
      assertThat(names(ws.quotes("EURUSD").getFirst())).as("第一条是快照").isEqualTo(doc.get("ws.quote"));
      Set<String> live = new LinkedHashSet<>(doc.get("ws.quote"));
      live.remove("snapshot");
      assertThat(names(ws.quotes("EURUSD").getLast())).isEqualTo(live);
      assertThat(names(ws.of("bar").getFirst())).isEqualTo(doc.get("ws.bar"));
      assertThat(names(ws.of("heartbeat").getFirst())).isEqualTo(doc.get("ws.heartbeat"));
      // AC-Q8：外部 Key 收到的全部 REST 响应和推送，字段都在文档白名单里；没有报价商名、能否开仓、加点、客户、持仓、敞口、阈值
      Set<String> whitelist = new HashSet<>();
      doc.values().forEach(whitelist::addAll);
      whitelist.addAll(List.of("items", "unavailable", "limits", "today", "days", "wsConnectionsNow", "day", "open", "close", "message", "traceId", "code"));
      List<JsonNode> all = new ArrayList<>(c.seen);
      all.addAll(ws.messages);
      for (JsonNode n : all) {
        for (String f : fieldNames(n)) assertThat(whitelist).as("字段 " + f + " 不在白名单").contains(f);
        String text = n.toString().toLowerCase(java.util.Locale.ROOT);
        for (String bad : List.of("alltick", "itick", "binance", "simulated", "tradable", "feed", "markup", "spread", "position", "exposure", "threshold")) {
          assertThat(text).as("不能出现 " + bad).doesNotContain(bad);
        }
      }
    }
  }

  @Test
  void 开发者文档在测试环境能看_中文() throws Exception {
    var r = c.http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/docs/")).build(),
        java.net.http.HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).contains("报价 API 开发者文档").contains("X-QK-Signature").contains("只计量、不收费");
  }

  /** 开发者文档里每张 data-fields 表的字段名 */
  static Map<String, Set<String>> docFields() throws Exception {
    String html = new String(OpenServiceTest.class.getClassLoader().getResourceAsStream(OpenDocsController.DOC).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    Map<String, Set<String>> out = new java.util.LinkedHashMap<>();
    Matcher t = Pattern.compile("<table data-fields=\"([^\"]+)\">(.*?)</table>", Pattern.DOTALL).matcher(html);
    while (t.find()) {
      Set<String> f = new LinkedHashSet<>();
      Matcher row = Pattern.compile("<tr><td><code>([^<]+)</code></td>").matcher(t.group(2));
      while (row.find()) f.add(row.group(1));
      out.put(t.group(1), f);
    }
    return out;
  }

  static Set<String> names(JsonNode n) {
    return new LinkedHashSet<>(n.propertyNames());
  }

  /** 所有层级的字段名 */
  static Set<String> fieldNames(JsonNode n) {
    Set<String> out = new LinkedHashSet<>();
    collect(n, out);
    return out;
  }

  private static void collect(JsonNode n, Set<String> out) {
    if (n == null) return;
    if (n.isObject()) {
      for (String f : n.propertyNames()) {
        out.add(f);
        collect(n.get(f), out);
      }
    } else if (n.isArray()) {
      for (JsonNode x : n) collect(x, out);
    }
  }
}
