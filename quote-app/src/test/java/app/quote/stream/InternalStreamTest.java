package app.quote.stream;

import static app.quote.stream.StreamTestClient.await;
import static org.assertj.core.api.Assertions.assertThat;

import app.quote.pipeline.QuoteProcessor;
import app.quote.pipeline.QuoteStatus;
import app.quote.pipeline.RawQuote;
import app.quote.protocol.Message;
import app.quote.protocol.QuoteCodec;
import app.quote.protocol.QuoteFrame;
import app.quote.support.FixedClockConfig;
import app.quote.support.QuoteAppTest;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

/**
 * 内部 WebSocket（技术方案 02 页第 2 节、TQ-2 A）：真实的报价服务 + 正式的客户端 {@code QuoteStreamClient}。
 * 检查：内部 Key、连上先 hello 再快照、每个品种序号连续、只出干净价（不加点，TQ-3 B）、断线补齐、缓存不够时发快照、连接中跳号要求补齐。
 * 报价由测试直接喂给报价主链路（报价模式 none，不连任何报价源）。时钟固定在 2026-10-14（周三）10:00 UTC，外汇开市。
 */
@QuoteAppTest
@Import(FixedClockConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InternalStreamTest {

  static final String KEY = "ci_only_fake_internal_key";
  static final Instant NOW = FixedClockConfig.NOW;

  @LocalServerPort int port;
  @Autowired QuoteProcessor processor;
  @Autowired QuoteStream stream;

  static RawQuote q(String symbol, String bid, String ask) {
    return new RawQuote(symbol, new BigDecimal(bid), new BigDecimal(ask), NOW);
  }

  /** 喂一串连续变化的报价（每次 +1 个最小单位，不触发跳变守卫） */
  void feed(String symbol, int n, int fromPips) {
    for (int i = 0; i < n; i++) {
      BigDecimal bid = new BigDecimal("1.08000").add(BigDecimal.valueOf(fromPips + i, 5));
      assertThat(processor.process(new RawQuote(symbol, bid, bid.add(new BigDecimal("0.00010")), NOW))).isEqualTo(QuoteStatus.FRESH);
    }
  }

  @Test
  void 没有内部Key或Key不对_握手和内部接口都拒绝() throws Exception {
    HttpClient http = HttpClient.newHttpClient();
    for (String key : new String[] {null, "wrong_key"}) {
      WebSocket.Builder b = http.newWebSocketBuilder();
      if (key != null) b.header("X-Quote-Key", key);
      CompletableFuture<WebSocket> ws = b.buildAsync(URI.create("ws://127.0.0.1:" + port + "/internal/ws/quotes"), new WebSocket.Listener() {});
      try {
        ws.join();
        throw new AssertionError("没有正确的 Key 也连上了");
      } catch (CompletionException e) {
        assertThat(e.getCause()).isInstanceOf(java.net.http.WebSocketHandshakeException.class);
        assertThat(((java.net.http.WebSocketHandshakeException) e.getCause()).getResponse().statusCode()).isEqualTo(401);
      }
      HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/licenses"));
      if (key != null) r.header("X-Quote-Key", key);
      assertThat(http.send(r.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }
  }

  @Test
  void 连上先收hello_再收已有品种的快照_之后每条序号加1_价格不加点() throws Exception {
    feed("GBPUSD", 1, 0);
    try (StreamTestClient c = new StreamTestClient(port, KEY)) {
      await("hello 和 GBPUSD 的快照", () -> !c.hellos.isEmpty() && !c.of("GBPUSD").isEmpty());
      Message.Hello h = c.hellos.getFirst();
      assertThat(h.epoch()).isEqualTo(stream.epoch());
      assertThat(h.mode()).isEqualTo("none");
      QuoteFrame snap = c.of("GBPUSD").getFirst();
      assertThat(snap.snapshot()).isTrue();
      long base = snap.seq();
      feed("GBPUSD", 3, 1);
      await("三条实时报价", () -> c.of("GBPUSD").size() == 4);
      assertThat(c.seqs("GBPUSD")).containsExactly(base, base + 1, base + 2, base + 3);
      QuoteFrame last = c.of("GBPUSD").getLast();
      assertThat(last.snapshot()).isFalse();
      assertThat(last.bid()).as("报价服务只出干净价，不加点（TQ-3 B）").isEqualByComparingTo("1.08003");
      assertThat(last.ask()).isEqualByComparingTo("1.08013");
      assertThat(last.time()).isEqualTo(NOW.toEpochMilli());
      assertThat(last.status()).isEqualTo("FRESH");
      assertThat(last.tradable()).as("测试环境没启用授权检查").isTrue();
    }
  }

  @Test
  void 不正常的报价只在状态变化时推一条_价格保留上一条() throws Exception {
    feed("AUDUSD", 1, 0);
    try (StreamTestClient c = new StreamTestClient(port, KEY)) {
      await("快照", () -> !c.of("AUDUSD").isEmpty());
      assertThat(processor.process(q("AUDUSD", "1.08100", "1.08000"))).as("买卖倒挂").isEqualTo(QuoteStatus.ABNORMAL);
      assertThat(processor.process(q("AUDUSD", "1.08100", "1.08000"))).isEqualTo(QuoteStatus.ABNORMAL);
      await("一条 ABNORMAL", () -> c.of("AUDUSD").size() == 2);
      Thread.sleep(200);
      assertThat(c.of("AUDUSD")).as("同样的状态不重复推").hasSize(2);
      QuoteFrame bad = c.of("AUDUSD").getLast();
      assertThat(bad.status()).isEqualTo("ABNORMAL");
      assertThat(bad.bid()).as("价格是上一条正常报价").isEqualByComparingTo("1.08000");
      feed("AUDUSD", 1, 1);
      await("恢复正常", () -> c.of("AUDUSD").getLast().status().equals("FRESH"));
    }
  }

  @Test
  void 断线期间的报价重连后按序号补齐_不重复不跳号() throws Exception {
    feed("EURUSD", 1, 0);
    try (StreamTestClient c = new StreamTestClient(port, KEY)) {
      await("EURUSD 快照", () -> !c.of("EURUSD").isEmpty());
      long base = c.of("EURUSD").getFirst().seq();
      feed("EURUSD", 2, 1);
      await("两条实时报价", () -> c.of("EURUSD").size() == 3);
      stream.dropAll(); // 服务器这边断开（模拟网络中断）
      await("断开了", () -> !c.client.connected() || stream.connections() == 0);
      feed("EURUSD", 5, 3); // 断线期间的 5 条
      await("重连并补齐", () -> c.of("EURUSD").size() == 8);
      assertThat(c.seqs("EURUSD")).containsExactlyElementsOf(LongStream.rangeClosed(base, base + 7).boxed().toList());
      assertThat(c.of("EURUSD").subList(3, 8)).as("补齐的是原来的报价，不是快照").noneMatch(QuoteFrame::snapshot);
      assertThat(c.hellos).as("重连了一次").hasSize(2);
    }
  }

  @Test
  void 连接中跳号时客户端要求补齐_服务器按序号补发() throws Exception {
    feed("USDCHF", 3, 0);
    long cur = stream.latest("USDCHF").orElseThrow().seq();
    HttpClient http = HttpClient.newHttpClient();
    List<String> got = new CopyOnWriteArrayList<>();
    WebSocket ws = http.newWebSocketBuilder().header("X-Quote-Key", KEY).buildAsync(URI.create("ws://127.0.0.1:" + port + "/internal/ws/quotes"),
        new WebSocket.Listener() {
          @Override
          public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            got.add(data.toString());
            w.request(1);
            return null;
          }
        }).join();
    await("hello", () -> got.stream().anyMatch(m -> m.contains("\"type\":\"hello\"")));
    String epoch = ((Message.Hello) QuoteCodec.decode(got.getFirst())).epoch();
    // 说自己已经有到 cur-2：服务器只补发 cur-1、cur，不发快照
    ws.sendText(QuoteCodec.encode(new Message.Subscribe(epoch, Map.of("USDCHF", cur - 2))), true).join();
    await("补发两条", () -> frames(got, "USDCHF").size() >= 2);
    assertThat(frames(got, "USDCHF")).extracting(QuoteFrame::seq).containsExactly(cur - 1, cur);
    assertThat(frames(got, "USDCHF")).noneMatch(QuoteFrame::snapshot);
    // 连接中要求补齐：从 cur-1 之后
    ws.sendText(QuoteCodec.encode(new Message.Resync(Map.of("USDCHF", cur - 1))), true).join();
    await("resync 补发", () -> frames(got, "USDCHF").size() >= 3);
    assertThat(frames(got, "USDCHF").get(2).seq()).isEqualTo(cur);
    // -1 = 直接要快照
    ws.sendText(QuoteCodec.encode(new Message.Resync(Map.of("USDCHF", -1L))), true).join();
    await("快照", () -> frames(got, "USDCHF").size() >= 4);
    assertThat(frames(got, "USDCHF").get(3).snapshot()).isTrue();
    assertThat(frames(got, "USDCHF").get(3).seq()).isEqualTo(cur);
    // 格式不对的消息回 error，不断开
    ws.sendText("{\"op\":\"nope\"}", true).join();
    await("error", () -> got.stream().anyMatch(m -> m.contains("\"type\":\"error\"")));
    ws.abort();
  }

  @Test
  void epoch不同时不补齐_全部发快照() throws Exception {
    feed("NZDUSD", 2, 0);
    HttpClient http = HttpClient.newHttpClient();
    List<String> got = new CopyOnWriteArrayList<>();
    WebSocket ws = http.newWebSocketBuilder().header("X-Quote-Key", KEY).buildAsync(URI.create("ws://127.0.0.1:" + port + "/internal/ws/quotes"),
        new WebSocket.Listener() {
          @Override
          public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            got.add(data.toString());
            w.request(1);
            return null;
          }
        }).join();
    await("hello", () -> !got.isEmpty());
    ws.sendText(QuoteCodec.encode(new Message.Subscribe("上一次启动的-epoch", Map.of("NZDUSD", 1L))), true).join();
    await("NZDUSD", () -> !frames(got, "NZDUSD").isEmpty());
    assertThat(frames(got, "NZDUSD")).hasSize(1);
    assertThat(frames(got, "NZDUSD").getFirst().snapshot()).isTrue();
    ws.abort();
  }

  @Test
  void 每秒心跳() throws Exception {
    try (StreamTestClient c = new StreamTestClient(port, KEY)) {
      await("心跳", () -> !c.heartbeats.isEmpty());
      assertThat(c.heartbeats.getFirst().alerts()).as("没有配置报价源时没有告警项").isEmpty();
    }
  }

  static List<QuoteFrame> frames(List<String> got, String symbol) {
    return got.stream().map(QuoteCodec::decode).filter(m -> m instanceof QuoteFrame f && f.symbol().equals(symbol)).map(m -> (QuoteFrame) m).toList();
  }
}
