package app.trading.market.quote;

import app.quote.protocol.Message;
import app.quote.protocol.QuoteFrame;
import app.trading.market.MarketProperties;
import app.trading.market.api.MarketAdminApi.QuoteSource;
import app.trading.market.api.MarketQuote;
import app.trading.market.api.QuoteListener;
import app.trading.market.api.QuoteStatus;
import app.trading.market.api.SymbolSpec;
import app.trading.market.config.SymbolCatalog;
import app.trading.market.kline.KlineService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 交易侧的本地报价簿（第 2 批拆进程后）：收报价服务推来的干净报价（{@link QuoteFrame}，已按序号排好） → 交易时段 → 最后一道检查 → <b>加点</b> →
 * 最新报价 → 逐笔 → K 线 → 订阅者（撮合）。按品种分到固定的处理线程（同一品种严格按顺序），不同品种并行。
 * <ul>
 *   <li><b>过期保护</b>（技术方案 02 页第 2 节）：一个品种超过 {@code max-quote-age}（默认 3 秒）没收到新报价，或报价时间超过 {@code stale-after}，
 *       {@link #latest} 就返回不可成交——拒新开仓、平仓也拒（"报价暂不可用"）。时间都用注入的时钟（测试里拨时钟，确定性断言）；</li>
 *   <li><b>不按过期价强平</b>：到达时报价时间已经超过 {@code stale-after} 的（例如断线很久后补齐的旧报价）只更新显示，标成不可成交，
 *       <b>不交给订阅者</b>，所以撮合不会拿它触发止盈止损、强平；报价服务断了就没有新报价，撮合自然不动；</li>
 *   <li>最后一道检查：价格非正、买卖倒挂 → ABNORMAL（完整的质量守卫在报价服务，这里只防明显错误的价格进撮合）；</li>
 *   <li>报价源状态：报价带当前源和状态（主源 / 备源 / 偏差 / 模拟）。偏差 → 这个品种<b>只暂停开仓</b>（{@link #isDeviationPaused}，负责人 2026-10-17 选 B）；</li>
 *   <li>授权：报价带 {@code tradable}（报价源"交易用途"授权，TQ-11 A），为 false 时只显示、不开仓（{@link #tradingAllowed}）；</li>
 *   <li>官网"示例行情"单独一份（{@link #acceptSample}）：真实源接管的品种，模拟价只写这里，不进交易链路。</li>
 * </ul>
 * 监控（E8、E10）：每个品种最后一次收到报价的时间（给 {@link MarketAlert} 和指标 {@code fx_quote_age_seconds}），收到的报价数 {@code fx_quotes_received_total}。
 */
@Component
public class QuoteProcessor {

  private static final Logger log = LoggerFactory.getLogger(QuoteProcessor.class);
  private static final int STRIPES = 8;

  private final SymbolCatalog catalog;
  private final KlineService klines;
  private final MarketProperties props;
  private final QuoteBook book = new QuoteBook();
  private final RecentTicks ticks = new RecentTicks();
  private final List<QuoteListener> listeners = new CopyOnWriteArrayList<>();
  private final Set<String> changed = ConcurrentHashMap.newKeySet();
  private final ExecutorService[] stripes = new ExecutorService[STRIPES];
  private final Clock clock;
  /** 品种 → 最后一次收到报价的时间（本进程的时钟） */
  private final Map<String, Instant> lastReceived = new ConcurrentHashMap<>();
  private final MeterRegistry metrics;
  private final Counter received;
  /** 两家报价源偏差过大、暂停开仓的品种（和品种自己的"暂停开仓"、风控的暂停是三个互不覆盖的开关） */
  private final Set<String> deviationPaused = ConcurrentHashMap.newKeySet();
  /** 报价源没有"交易用途"授权、只显示不开仓的品种 */
  private final Set<String> notTradable = ConcurrentHashMap.newKeySet();
  /** 品种 → 当前报价源 */
  private final Map<String, QuoteSource> sources = new ConcurrentHashMap<>();
  /** 官网"示例行情"（模拟价，不进交易链路） */
  private final QuoteBook samples = new QuoteBook();
  /** 报价服务的报价模式（none / simulated / live）；还没连上为空 */
  private volatile String quoteMode;

  QuoteProcessor(SymbolCatalog catalog, KlineService klines, MarketProperties props, Clock clock, MeterRegistry metrics) {
    this.catalog = catalog;
    this.klines = klines;
    this.props = props;
    this.clock = clock;
    this.metrics = metrics;
    this.received = Counter.builder("fx.quotes.received").description("收到的报价条数（含休市和不可成交的）").register(metrics);
    for (int i = 0; i < STRIPES; i++) {
      int n = i;
      stripes[i] = Executors.newSingleThreadExecutor(r -> new Thread(r, "quote-" + n));
    }
  }

  /** 报价服务推来的一条（已按序号排好）：交给该品种的处理线程 */
  public void submit(QuoteFrame f) {
    stripes[Math.floorMod(f.symbol().hashCode(), STRIPES)].execute(() -> {
      try {
        accept(f);
      } catch (RuntimeException e) {
        log.error("处理报价失败 {}", f.symbol(), e);
      }
    });
  }

  /** 测试和端到端直接喂一条干净的正常报价（相当于报价服务推来一条 FRESH、可开仓、没有报价源信息的报价），返回本地判断的质量状态 */
  public QuoteStatus process(RawQuote q) {
    return accept(new QuoteFrame(q.symbol(), 0, q.bid(), q.ask(), q.time().toEpochMilli(), QuoteFrame.FRESH, null, null, true, false));
  }

  /** 处理一条报价，返回本地判断的质量状态。测试里直接调用 */
  public QuoteStatus accept(QuoteFrame f) {
    Optional<SymbolSpec> spec = catalog.get(f.symbol());
    if (spec.isEmpty()) return QuoteStatus.ABNORMAL; // 没有配置的品种直接丢弃
    Instant now = clock.instant();
    received(f.symbol(), now);
    sourceState(f);
    if (!catalog.isOpen(f.symbol(), now)) {
      book.markStatus(f.symbol(), QuoteStatus.MARKET_CLOSED);
      changed.add(f.symbol());
      return QuoteStatus.MARKET_CLOSED;
    }
    QuoteStatus st = status(f);
    if (st != QuoteStatus.FRESH) {
      book.markStatus(f.symbol(), st);
      changed.add(f.symbol());
      return st;
    }
    Instant time = Instant.ofEpochMilli(f.time());
    boolean stale = now.toEpochMilli() - f.time() > props.staleAfter().toMillis();
    // 加点只加一次：卖价减、买价加（需求 4.3-2，修复现有系统 T1 的重复加点）。报价服务给的是干净价（TQ-3 B）
    BigDecimal markup = spec.get().markup();
    MarketQuote mq = new MarketQuote(f.symbol(), f.bid().subtract(markup), f.ask().add(markup), mid(f.bid(), f.ask()), time,
        stale ? QuoteStatus.STALE : QuoteStatus.FRESH, !stale);
    book.put(mq);
    ticks.add(f.symbol(), new RecentTicks.Tick(time, mq.bid(), mq.ask()));
    klines.accept(f.symbol(), mq.mid(), time);
    changed.add(f.symbol());
    if (stale) return QuoteStatus.STALE; // 旧报价只更新显示，不交给撮合（不按过期价触发止盈止损、强平）
    for (QuoteListener l : listeners) {
      try {
        l.onQuote(mq);
      } catch (RuntimeException e) {
        log.error("报价订阅者处理失败 {}", f.symbol(), e);
      }
    }
    return QuoteStatus.FRESH;
  }

  /** 报价服务给的状态；FRESH 的再做最后一道检查（价格非正、买卖倒挂） */
  private static QuoteStatus status(QuoteFrame f) {
    QuoteStatus st;
    try {
      st = QuoteStatus.valueOf(f.status());
    } catch (IllegalArgumentException | NullPointerException e) {
      return QuoteStatus.ABNORMAL;
    }
    if (st != QuoteStatus.FRESH) return st;
    if (f.bid().signum() <= 0 || f.ask().signum() <= 0 || f.bid().compareTo(f.ask()) >= 0) return QuoteStatus.ABNORMAL;
    return st;
  }

  static BigDecimal mid(BigDecimal bid, BigDecimal ask) {
    return bid.add(ask).divide(BigDecimal.TWO, 8, RoundingMode.DOWN);
  }

  /** 报价源、偏差、授权（测试直接喂的报价序号是 0，不改这些） */
  private void sourceState(QuoteFrame f) {
    if (f.seq() == 0) return;
    if (f.feed() != null) sources.put(f.symbol(), new QuoteSource(f.feed(), "simulated".equals(f.feed()) ? null : f.state()));
    setDeviationPaused(f.symbol(), "deviation".equals(f.state()));
    if (f.tradable()) notTradable.remove(f.symbol());
    else notTradable.add(f.symbol());
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
   * 报价源偏差（两家价格对不上）时暂停这个品种<b>开仓</b>；偏差回落后恢复。只动这一个开关，
   * 不碰品种自己的 {@code open_paused} 和风控的暂停。报价不受影响（继续可以成交），平仓、止盈止损、强平照常。
   */
  public void setDeviationPaused(String symbol, boolean paused) {
    if (paused) deviationPaused.add(symbol);
    else deviationPaused.remove(symbol);
  }

  public boolean isDeviationPaused(String symbol) {
    return deviationPaused.contains(symbol);
  }

  /** 这个品种的报价源有"交易用途"授权（或者没启用授权检查），可以开仓；false 时只显示、不开仓（TQ-11 A） */
  public boolean tradingAllowed(String symbol) {
    return !notTradable.contains(symbol);
  }

  /** 品种当前在用的报价源（后台品种页）；还没收到过带报价源的报价时为空 */
  public Optional<QuoteSource> quoteSource(String symbol) {
    return Optional.ofNullable(sources.get(symbol));
  }

  /** 这个品种的交易报价来自真实源（不是模拟源） */
  public boolean liveTrading(String symbol) {
    QuoteSource s = sources.get(symbol);
    return s != null && !"simulated".equals(s.feed());
  }

  /** 报价服务的模式（none / simulated / live）；没连报价服务为空 */
  public Optional<String> quoteMode() {
    return Optional.ofNullable(quoteMode);
  }

  /** 报价服务连上时（hello）告诉本类它的模式和每个品种在用的报价源（还没有报价的品种也有） */
  public void connected(Message.Hello hello) {
    this.quoteMode = hello.mode();
    for (Message.Source s : hello.sources()) sources.put(s.symbol(), new QuoteSource(s.feed(), "simulated".equals(s.feed()) ? null : s.state()));
  }

  /** 官网"示例行情"：模拟价加上品种的加点后直接记下（不推送、不进 K 线和撮合） */
  public void acceptSample(Message.Sample s) {
    catalog.get(s.symbol()).ifPresent(spec -> samples.put(new MarketQuote(s.symbol(), s.bid().subtract(spec.markup()), s.ask().add(spec.markup()),
        mid(s.bid(), s.ask()), Instant.ofEpochMilli(s.time()), QuoteStatus.FRESH, true)));
  }

  /** 官网"示例行情"的最新一条（超过 staleAfter 没更新视为不可成交） */
  public Optional<MarketQuote> latestSample(String symbol) {
    return samples.latest(symbol, clock.instant(), props.staleAfter().toMillis());
  }

  public QuoteBook sampleBook() {
    return samples;
  }

  /** 这个品种最后一次收到报价的时间；从没收到过时为空 */
  public Optional<Instant> lastReceived(String symbol) {
    return Optional.ofNullable(lastReceived.get(symbol));
  }

  public void subscribe(QuoteListener l) {
    listeners.add(l);
  }

  public QuoteBook book() {
    return book;
  }

  /** 某个品种最近 limit 笔报价（Tick 图），只在内存里，见 {@link RecentTicks} */
  public List<RecentTicks.Tick> recentTicks(String symbol, int limit) {
    return ticks.recent(symbol, limit);
  }

  /**
   * 最新报价。可以成交要同时满足：状态 FRESH、报价时间不超过 {@code stale-after}、<b>最后一次收到报价不超过 {@code max-quote-age}</b>（过期保护）；
   * 否则返回同一价格、不可成交（STALE）。从没收到过时为空。
   */
  public Optional<MarketQuote> latest(String symbol) {
    Instant now = clock.instant();
    Optional<MarketQuote> q = book.latest(symbol, now, props.staleAfter().toMillis());
    if (q.isEmpty() || !q.get().executable()) return q;
    Instant got = lastReceived.get(symbol);
    if (got != null && now.toEpochMilli() - got.toEpochMilli() <= props.maxQuoteAge().toMillis()) return q;
    MarketQuote m = q.get();
    return Optional.of(new MarketQuote(m.symbol(), m.bid(), m.ask(), m.mid(), m.time(), QuoteStatus.STALE, false));
  }

  /** 推送用：取出并清空自上次以来有变化的品种 */
  public Set<String> drainChanged() {
    Set<String> out = Set.copyOf(changed);
    changed.removeAll(out);
    return out;
  }

  @PreDestroy
  void stop() {
    for (ExecutorService e : stripes) e.shutdownNow();
  }
}
