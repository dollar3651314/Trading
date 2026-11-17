package app.quote.pipeline;

import app.quote.QuoteProperties;
import app.quote.catalog.QuoteSymbol;
import app.quote.catalog.SymbolCatalog;
import app.quote.kline.KlineService;
import app.quote.stream.QuoteStream;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 报价主链路（拆分前 trading-app 的 {@code market.quote.QuoteProcessor} 的前半段）：报价 → 交易时段 → 质量守卫 → 最新报价 → K 线 → 内部 WebSocket。
 * <b>不加点</b>（TQ-3 B：加点、逐笔、撮合在 trading-app 收到以后做）。K 线用干净的中间价（和拆分前一样不含加点），只在报价服务里算（技术方案 02 页第 2 节第 4 条）。
 * 按品种分到固定的处理线程（同一品种严格按顺序，序号也按这个顺序编），不同品种并行。
 * <ul>
 *   <li>正常报价每条都推；不正常（休市、异常、过期、长时间不变）只在状态变化时推一条（价格是上一条正常报价），不重复推；</li>
 *   <li>两家报价源偏差过大（{@link #setDeviationPaused}，由 {@code feed.QuoteRouter} 设置）只改报价的 {@code state}，报价照常；
 *       状态变了立即把最新报价按新状态再推一条，trading-app 马上知道要不要暂停开仓；</li>
 *   <li>官网"示例行情"（{@link #submitSample}）：真实源接管的品种，模拟价单独推 {@code sample}，不进交易链路。</li>
 * </ul>
 * 监控：每个品种最后一次收到原始报价的时间（指标 {@code fx_quote_age_seconds}），收到的报价数 {@code fx_quotes_received_total}。
 */
@Component
public class QuoteProcessor {

  private static final Logger log = LoggerFactory.getLogger(QuoteProcessor.class);
  private static final int STRIPES = 8;

  private final SymbolCatalog catalog;
  private final QuoteProperties props;
  private final QualityGuard guard;
  private final QuoteStream stream;
  private final KlineService klines;
  private final Map<String, CleanQuote> book = new ConcurrentHashMap<>();
  private final ExecutorService[] stripes = new ExecutorService[STRIPES];
  private final Clock clock;
  private final Map<String, Instant> lastReceived = new ConcurrentHashMap<>();
  private final MeterRegistry metrics;
  private final Counter received;
  private final Set<String> deviationPaused = ConcurrentHashMap.newKeySet();

  QuoteProcessor(SymbolCatalog catalog, QuoteProperties props, Clock clock, MeterRegistry metrics, QuoteStream stream, KlineService klines) {
    this.klines = klines;
    this.catalog = catalog;
    this.props = props;
    this.guard = new QualityGuard(props);
    this.clock = clock;
    this.metrics = metrics;
    this.stream = stream;
    this.received = Counter.builder("fx.quotes.received").description("收到的原始报价条数（含被质量守卫拦下和休市的）").register(metrics);
    for (int i = 0; i < STRIPES; i++) {
      int n = i;
      stripes[i] = Executors.newSingleThreadExecutor(r -> new Thread(r, "quote-" + n));
    }
  }

  /** 报价源调用：交给该品种的处理线程 */
  public void submit(RawQuote q) {
    onStripe(q.symbol(), () -> process(q));
  }

  private void onStripe(String symbol, Runnable r) {
    stripes[Math.floorMod(symbol.hashCode(), STRIPES)].execute(() -> {
      try {
        r.run();
      } catch (RuntimeException e) {
        log.error("处理报价失败 {}", symbol, e);
      }
    });
  }

  /** 处理一条报价，返回质量状态。测试里直接调用 */
  public QuoteStatus process(RawQuote q) {
    Optional<QuoteSymbol> spec = catalog.get(q.symbol());
    if (spec.isEmpty()) return QuoteStatus.ABNORMAL; // 没有配置的品种直接丢弃
    Instant now = clock.instant();
    received(q.symbol(), now);
    if (!catalog.isOpen(q.symbol(), now)) {
      mark(q.symbol(), QuoteStatus.MARKET_CLOSED);
      return QuoteStatus.MARKET_CLOSED;
    }
    QuoteStatus st = guard.evaluate(q, now);
    if (st != QuoteStatus.FRESH) {
      mark(q.symbol(), st);
      return st;
    }
    CleanQuote c = new CleanQuote(q.symbol(), q.bid(), q.ask(), q.time(), QuoteStatus.FRESH, true);
    book.put(q.symbol(), c);
    klines.accept(q.symbol(), QualityGuard.mid(q.bid(), q.ask()), q.time());
    stream.publish(c);
    return st;
  }

  /** 不正常的报价：保留上一条价格，状态变了才推 */
  private void mark(String symbol, QuoteStatus st) {
    CleanQuote prev = book.get(symbol);
    if (prev == null || prev.status() == st) return;
    CleanQuote c = prev.withStatus(st);
    book.put(symbol, c);
    stream.publish(c);
  }

  private void received(String symbol, Instant now) {
    received.increment();
    if (lastReceived.put(symbol, now) == null) {
      Gauge.builder("fx.quote.age", () -> {
        Instant t = lastReceived.get(symbol);
        return t == null ? Double.NaN : (clock.millis() - t.toEpochMilli()) / 1000.0;
      }).description("这个品种最后一次收到报价距今的秒数").baseUnit("seconds").tag("symbol", symbol).register(metrics);
    }
  }

  /**
   * 报价源偏差（两家价格对不上）：trading-app 暂停这个品种<b>开仓</b>；偏差回落后恢复。变了就在处理线程上把最新报价按新状态再推一条。
   */
  public void setDeviationPaused(String symbol, boolean paused) {
    boolean changed = paused ? deviationPaused.add(symbol) : deviationPaused.remove(symbol);
    if (changed) onStripe(symbol, () -> {
      CleanQuote c = book.get(symbol);
      if (c != null) stream.publish(c);
    });
  }

  public boolean isDeviationPaused(String symbol) {
    return deviationPaused.contains(symbol);
  }

  /** 官网"示例行情"：模拟价直接推 sample（不经过质量守卫，不进交易链路） */
  public void submitSample(RawQuote q) {
    if (catalog.get(q.symbol()).isPresent()) stream.sample(q.symbol(), q.bid(), q.ask(), q.time());
  }

  /** 这个品种最后一次收到原始报价的时间；从没收到过时为空 */
  public Optional<Instant> lastReceived(String symbol) {
    return Optional.ofNullable(lastReceived.get(symbol));
  }

  /** 最新报价；超过 staleAfter 没更新视为不可成交（STALE）；从没收到过时为空 */
  public Optional<CleanQuote> latest(String symbol) {
    CleanQuote q = book.get(symbol);
    if (q == null) return Optional.empty();
    boolean fresh = q.executable() && clock.millis() - q.time().toEpochMilli() <= props.staleAfter().toMillis();
    return Optional.of(fresh ? q : new CleanQuote(q.symbol(), q.bid(), q.ask(), q.time(), q.executable() ? QuoteStatus.STALE : q.status(), false));
  }

  @PreDestroy
  void stop() {
    for (ExecutorService e : stripes) e.shutdownNow();
  }
}
