package app.trading.trading.order;

import static org.assertj.core.api.Assertions.assertThat;

import app.quote.pipeline.RawQuote;
import app.trading.common.health.AlertCheck;
import app.trading.market.api.MarketApi;
import app.trading.market.api.MarketQuote;
import app.trading.market.quote.RemoteQuotes;
import app.trading.support.MutableClock;
import app.trading.support.QuoteAppHarness;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * 两个进程（第 2 批）：真实的报价服务（{@link QuoteAppHarness}，固定端口，可以停掉再启动）+ 交易这边连它（trading.market.source=remote）。
 * 对照需求 03 页第一节"单独运行"和 AC-Q9：
 * <ul>
 *   <li>报价服务出的是干净价，交易这边加点一次（TQ-3 B）；</li>
 *   <li>断线期间的报价重连后按序号补齐（逐笔一条不少、按顺序）；</li>
 *   <li>停报价服务：交易所有品种 3 秒后按"报价暂不可用"拒新开仓，已有持仓不被强平；告警项 quote.stream 变 DOWN；
 *       报价服务重新启动（新的 epoch）后交易自动连回去、从快照恢复，新报价到了就能开仓。</li>
 * </ul>
 * 报价由测试直接喂给报价服务的主链路（报价模式 none，不连报价源）。时钟：两边都停在 2026-10-14 10:00 UTC，交易这边按需要往前拨。
 */
@TestPropertySource(properties = {"trading.market.source=remote", "trading.market.quote-app.reconnect-initial=100ms",
    "trading.market.quote-app.reconnect-max=300ms", "trading.market.quote-app.idle-timeout=2s"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuoteServiceOutageTest extends TradingTestSupport {

  static final int PORT = freePort();
  static QuoteAppHarness quoteApp = start();

  static int freePort() {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static QuoteAppHarness start() {
    return QuoteAppHarness.start(new MutableClock(), Map.of("server.port", String.valueOf(PORT)));
  }

  @DynamicPropertySource
  static void quoteApp(DynamicPropertyRegistry r) {
    r.add("trading.market.quote-app.url", () -> "http://127.0.0.1:" + PORT);
    r.add("trading.market.quote-app.key", () -> QuoteAppHarness.KEY);
  }

  @AfterAll
  static void stop() {
    quoteApp.close();
  }

  @Autowired RemoteQuotes remote;
  @Autowired MarketApi market;
  @Autowired PositionService positionService;
  @Autowired MatchingEngine engine;
  @Autowired Clock clock;
  @Autowired List<AlertCheck> alerts;

  @AfterEach
  void reset() {
    engine.setEnabled(false);
    ((MutableClock) clock).reset();
  }

  static void await(String what, BooleanSupplier ok) throws InterruptedException {
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < end) {
      if (ok.getAsBoolean()) return;
      Thread.sleep(20);
    }
    throw new AssertionError("等了 15 秒还没有：" + what);
  }

  /** 报价服务收到一条原始报价（报价时间 = 交易这边测试时钟的现在，两边时钟都在同一时刻） */
  void upstream(String symbol, String bid, String ask) {
    quoteApp.bean(app.quote.pipeline.QuoteProcessor.class).process(new RawQuote(symbol, new BigDecimal(bid), new BigDecimal(ask), clock.instant()));
  }

  boolean bidIs(String symbol, String bid) {
    return market.quote(symbol).map(q -> q.bid().compareTo(new BigDecimal(bid)) == 0).orElse(false);
  }

  Boolean alert(String name) {
    for (AlertCheck a : alerts) for (AlertCheck.Item i : a.evaluate()) if (i.name().equals(name)) return i.ok();
    return null;
  }

  @Test
  void 报价服务出干净价_交易这边加点一次() throws Exception {
    await("连上报价服务", remote::connected);
    upstream("XAUUSD", "4126.45", "4126.85");
    await("报价到了交易这边", () -> bidIs("XAUUSD", "4126.35"));
    MarketQuote q = market.quote("XAUUSD").orElseThrow();
    assertThat(q.ask()).as("原始 4126.85 + 加点 0.10").isEqualByComparingTo("4126.95");
    assertThat(q.mid()).as("中间价不含加点").isEqualByComparingTo("4126.65");
    assertThat(q.executable()).isTrue();
    assertThat(quoteApp.bean(app.quote.stream.QuoteStream.class).latest("XAUUSD").orElseThrow().bid()).as("报价服务那边是原始价").isEqualByComparingTo("4126.45");
  }

  @Test
  void 断线期间的报价重连后补齐_逐笔一条不少按顺序() throws Exception {
    await("连上报价服务", remote::connected);
    upstream("XAGUSD", "48.120", "48.150");
    await("第一条", () -> bidIs("XAGUSD", "48.110"));
    remote.dropConnection();
    upstream("XAGUSD", "48.121", "48.151");
    upstream("XAGUSD", "48.122", "48.152");
    upstream("XAGUSD", "48.123", "48.153");
    await("重连并补齐", () -> bidIs("XAGUSD", "48.113"));
    List<String> bids = processor.recentTicks("XAGUSD", 4).stream().map(t -> t.bid().setScale(3, java.math.RoundingMode.UNNECESSARY).toPlainString()).toList();
    assertThat(bids).as("断线期间的三条都补上了、按顺序（加点后）").containsExactly("48.110", "48.111", "48.112", "48.113");
  }

  @Test
  void 停报价服务_3秒后拒新开仓_已有持仓不被强平_重启后自动恢复() throws Exception {
    await("连上报价服务", remote::connected);
    upstream("XAUUSD", "4126.35", "4126.75");
    await("报价到了", () -> bidIs("XAUUSD", "4126.25") && market.quote("XAUUSD").get().executable());
    long poor = newUser("300");
    long liq = orderId(orders.place(poor, market("XAUUSD", "buy", "0.10", 200)).id()); // 强平价约 4116.53
    engine.setEnabled(true);

    quoteApp.close();
    await("交易发现断开", () -> !remote.connected());
    MutableClock c = (MutableClock) clock;
    c.advance(Duration.ofMillis(3001));
    assertThat(market.quote("XAUUSD").orElseThrow().executable()).isFalse();
    assertThat(code(() -> orders.place(poor, market("XAUUSD", "buy", "0.10", 100)))).as("暂无报价，拒新开仓").isEqualTo("QUOTE_UNAVAILABLE");
    assertThat(code(() -> orders.place(poor, market("EURJPY", "buy", "0.01", 100)))).as("所有品种都拒").isEqualTo("QUOTE_UNAVAILABLE");
    c.advance(Duration.ofSeconds(3));
    await("告警 quote.stream 变 DOWN", () -> Boolean.FALSE.equals(alert("quote.stream")));
    Thread.sleep(300);
    assertThat(jdbc.queryForObject("SELECT status FROM trading_position WHERE order_id = ?", String.class, liq)).as("报价恢复前不强平").isEqualTo("OPEN");

    c.reset();
    quoteApp = start(); // 新的 epoch，序号从 1 开始
    await("自动连回去", remote::connected);
    upstream("XAUUSD", "4127.35", "4127.75");
    await("新报价到了", () -> bidIs("XAUUSD", "4127.25"));
    assertThat(market.quote("XAUUSD").orElseThrow().executable()).isTrue();
    assertThat(code(() -> orders.place(poor, market("XAUUSD", "buy", "0.01", 100)))).as("恢复后能开仓").isEqualTo("OK");
    await("告警恢复", () -> Boolean.TRUE.equals(alert("quote.stream")));
  }
}
