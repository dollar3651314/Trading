package app.trading.trading.order;

import static org.assertj.core.api.Assertions.assertThat;

import app.quote.protocol.QuoteFrame;
import app.trading.market.api.MarketApi;
import app.trading.market.api.MarketQuote;
import app.trading.market.api.QuoteStatus;
import app.trading.support.MutableClock;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 第 2 批拆进程后交易这边的报价保护（技术方案 02 页第 2 节、08 页；需求 03 页第一节、第四节）：
 * <ul>
 *   <li><b>过期保护</b>：一个品种超过 3 秒（{@code trading.market.max-quote-age}）没收到新报价，就不能成交——拒新开仓（"报价暂不可用"），
 *       收到新报价立即恢复。时间用可调时钟，边界（正好 3 秒 / 3 秒零 1 毫秒）确定性断言（C106）；</li>
 *   <li><b>不按过期价强平</b>：到达时报价时间已经超过 5 秒的旧报价（例如断线很久后补齐的）只更新显示、不可成交、不交给撮合，越过强平价也不强平；</li>
 *   <li><b>报价源授权</b>（TQ-11 A）：报价带 tradable=false（报价源没有"交易用途"授权）时只显示不开仓，拒单原因"该品种暂不开放交易"，
 *       已有挂单不成交；平仓照常；</li>
 *   <li>偏差状态跟着报价走（报价服务推来的 state=deviation → 暂停开仓）。</li>
 * </ul>
 * 报价直接喂给交易这边的本地报价簿（{@link QuoteFrame}，序号 &gt; 0 表示"报价服务推来的"）。XAUUSD 2 位小数，加点每边 0.10（V24）。
 */
class QuoteProtectionOrderTest extends TradingTestSupport {

  @Autowired MatchingEngine engine;
  @Autowired PositionService positionService;
  @Autowired MarketApi market;
  @Autowired Clock clock;

  long seq = 1000;

  @AfterEach
  void reset() {
    engine.setEnabled(false);
    ((MutableClock) clock).reset();
    frame("XAUUSD", "4126.35", "4126.75", 0, "primary", true);
  }

  /** 报价服务推来一条（报价时间 = 现在 − agoMillis） */
  QuoteStatus frame(String symbol, String bid, String ask, long agoMillis, String state, boolean tradable) {
    return processor.accept(new QuoteFrame(symbol, ++seq, new BigDecimal(bid), new BigDecimal(ask), clock.millis() - agoMillis, "FRESH", state, null, tradable,
        false));
  }

  static void await(BooleanSupplier ok) throws InterruptedException {
    long t0 = System.nanoTime();
    while (!ok.getAsBoolean()) {
      if (System.nanoTime() - t0 > 3_000_000_000L) throw new AssertionError("3 秒内没有完成");
      Thread.sleep(5);
    }
  }

  String positionStatus(long orderId) {
    return jdbc.queryForObject("SELECT status FROM trading_position WHERE order_id = ?", String.class, orderId);
  }

  String orderStatus(long id) {
    return jdbc.queryForObject("SELECT status FROM trading_order WHERE id = ?", String.class, id);
  }

  @Test
  void 报价超过3秒没更新就拒新开仓_正好3秒还可以_收到新报价立即恢复() {
    long uid = newUser("5000");
    MutableClock c = (MutableClock) clock;
    assertThat(code(() -> orders.place(uid, market("XAUUSD", "buy", "0.10", 100)))).isEqualTo("OK");
    c.advance(Duration.ofSeconds(3));
    assertThat(market.quote("XAUUSD").orElseThrow().executable()).as("正好 3 秒").isTrue();
    assertThat(code(() -> orders.place(uid, market("XAUUSD", "buy", "0.10", 100)))).isEqualTo("OK");
    c.advance(Duration.ofMillis(1));
    MarketQuote q = market.quote("XAUUSD").orElseThrow();
    assertThat(q.executable()).as("3 秒零 1 毫秒没有新报价").isFalse();
    assertThat(q.status()).isEqualTo(QuoteStatus.STALE);
    assertThat(q.bid()).as("价格还在（只显示）").isEqualByComparingTo("4126.25");
    assertThat(code(() -> orders.place(uid, market("XAUUSD", "sell", "0.10", 100)))).isEqualTo("QUOTE_UNAVAILABLE");
    long pid = orders.positions(uid).getFirst().id;
    assertThat(code(() -> positionService.close(uid, pid))).as("平仓也不按过期价成交").isEqualTo("QUOTE_UNAVAILABLE");

    frame("XAUUSD", "4126.35", "4126.75", 0, "primary", true);
    assertThat(market.quote("XAUUSD").orElseThrow().executable()).isTrue();
    assertThat(code(() -> orders.place(uid, market("XAUUSD", "buy", "0.10", 100)))).as("收到新报价后恢复").isEqualTo("OK");
    assertThat(code(() -> positionService.close(uid, pid))).isEqualTo("OK");
  }

  @Test
  void 到达时已经过期的旧报价只更新显示_不交给撮合_越过强平价也不强平() throws Exception {
    long poor = newUser("300");
    // 200 倍多单，开仓价 ask 4126.85，强平价约 4116.53
    long liq = orderId(orders.place(poor, market("XAUUSD", "buy", "0.10", 200)).id());
    List<MarketQuote> heard = new CopyOnWriteArrayList<>();
    market.subscribe(q -> {
      if (q.symbol().equals("XAUUSD")) heard.add(q);
    });
    engine.setEnabled(true);

    // 断线很久后补齐的一条：报价时间是 6 秒前（超过 5 秒），价格越过强平价
    assertThat(frame("XAUUSD", "4100.10", "4100.50", 6000, "primary", true)).isEqualTo(QuoteStatus.STALE);
    MarketQuote shown = market.quote("XAUUSD").orElseThrow();
    assertThat(shown.bid()).as("显示更新成这条").isEqualByComparingTo("4100.00");
    assertThat(shown.executable()).isFalse();
    Thread.sleep(300);
    assertThat(heard).as("没有交给撮合等订阅者").isEmpty();
    assertThat(positionStatus(liq)).as("不按过期价强平").isEqualTo("OPEN");

    // 同样的价格来了一条新的：照常强平（说明撮合是开着的）
    assertThat(frame("XAUUSD", "4100.10", "4100.50", 0, "primary", true)).isEqualTo(QuoteStatus.FRESH);
    await(() -> "CLOSED".equals(positionStatus(liq)));
    assertThat(jdbc.queryForObject("SELECT close_reason FROM trading_position WHERE order_id = ?", String.class, liq)).isEqualTo("LIQUIDATED");
  }

  @Test
  void 报价源没有交易用途授权_只显示不开仓_拒单原因该品种暂不开放交易_平仓照常_挂单不成交() throws Exception {
    long uid = newUser("5000");
    long open = orderId(orders.place(uid, market("XAUUSD", "buy", "0.10", 100)).id());
    long pending = orderId(orders.place(uid, new PlaceOrder("XAUUSD", "limit", "buy", "0.10", 100, "4120.00", null, null, UUID.randomUUID().toString())).id());
    long pid = orders.positions(uid).stream().filter(p -> p.orderId == open).findFirst().orElseThrow().id;

    frame("XAUUSD", "4126.35", "4126.75", 0, "primary", false);
    assertThat(market.quoteTradingAllowed("XAUUSD")).isFalse();
    assertThat(market.quote("XAUUSD").orElseThrow().executable()).as("报价照常（只显示）").isTrue();
    BizExceptionView rejected = reject(() -> orders.place(uid, market("XAUUSD", "buy", "0.10", 100)));
    assertThat(rejected.code()).isEqualTo("SYMBOL_NOT_TRADABLE");
    assertThat(rejected.message()).isEqualTo("该品种暂不开放交易");
    assertThat(code(() -> orders.place(uid, new PlaceOrder("XAUUSD", "limit", "buy", "0.10", 100, "4000.00", null, null, UUID.randomUUID().toString()))))
        .as("限价挂单也是开仓").isEqualTo("SYMBOL_NOT_TRADABLE");
    assertThat(code(() -> orders.place(uid, market("USDJPY", "buy", "0.10", 100)))).as("别的品种不受影响").isEqualTo("OK");

    // 已有挂单触及限价也不成交（挂单成交算开仓）
    engine.setEnabled(true);
    frame("XAUUSD", "4118.00", "4118.40", 0, "primary", false);
    Thread.sleep(300);
    assertThat(orderStatus(pending)).isEqualTo("PENDING");
    assertThat(code(() -> positionService.close(uid, pid))).as("客户平仓照常").isEqualTo("OK");

    // 授权恢复（报价换到有授权的源）：挂单按当时的价格成交，市价开仓恢复
    frame("XAUUSD", "4118.00", "4118.40", 0, "backup", true);
    await(() -> "FILLED".equals(orderStatus(pending)));
    assertThat(market.quoteTradingAllowed("XAUUSD")).isTrue();
    assertThat(code(() -> orders.place(uid, market("XAUUSD", "buy", "0.10", 100)))).isEqualTo("OK");
  }

  @Test
  void 偏差状态跟着报价服务推来的报价走() {
    long uid = newUser("5000");
    frame("XAUUSD", "4126.35", "4126.75", 0, "deviation", true);
    assertThat(market.quoteDeviationPaused("XAUUSD")).isTrue();
    assertThat(code(() -> orders.place(uid, market("XAUUSD", "buy", "0.10", 100)))).isEqualTo("SYMBOL_PAUSED");
    frame("XAUUSD", "4126.35", "4126.75", 0, "primary", true);
    assertThat(market.quoteDeviationPaused("XAUUSD")).isFalse();
    assertThat(code(() -> orders.place(uid, market("XAUUSD", "buy", "0.10", 100)))).isEqualTo("OK");
  }

  record BizExceptionView(String code, String message) {}

  static BizExceptionView reject(Runnable r) {
    try {
      r.run();
    } catch (app.trading.common.error.BizException e) {
      return new BizExceptionView(e.code(), e.getMessage());
    }
    throw new AssertionError("没有拒绝");
  }
}
