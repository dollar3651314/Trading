package app.quote.pipeline;

import app.quote.QuoteProperties;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import app.quote.feed.QuoteRouter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 模拟行情源（quote.market.source=simulated）：开发和测试环境用，价格围绕起始价做"回归均值"的随机游走，点差固定。
 * <p>游走（联调 O-1，11/19）：以前每 0.5 秒乘 (1 + 0.0002 × 正态随机数)，一天约 8% 的波动、而且越走越远（EURUSD 两天从 1.157 走到 1.037），
 * 0.01 手就有几美元盈亏，不像真的。现在按对数价格的 Ornstein–Uhlenbeck 过程：每天约 {@link #DAILY_VOL}（0.5%，接近外汇日内波动），
 * 长期离起始价的标准差约 {@link #LONG_RUN_SD}（1%），回到起始价的半衰期约 5 天（模拟 5 天：每天高低差 0.4%～1.1%，终点离起始价 1% 以内）。
 * 步长按 {@code simulated-interval} 换算，改报价间隔不改变每天的波动。
 * 起始价格取自原型数据（resources/simulated-prices.csv）。正式环境接 LP（S20），不使用这个类。
 * v2：csv 里没有的品种（后台新增的）用后台填的"模拟起始价"（quote_symbol.sim_start_price，trading-app 后台改完同步过来）：启动时从库里读，
 * 同步后由 {@code catalog.CatalogSync} 调用 {@link #seedFromDatabase} 立即加入。csv 里有的品种始终以 csv 为准。
 * <p>报价源接入（内部文档 第 3.2、6 节）：交易报价来自真实源的品种（{@link QuoteRouter#liveTrading}），模拟价只写进
 * 官网"示例行情"（{@link QuoteProcessor#submitSample}），<b>不进交易链路</b>；{@code live} 模式下也运行，只给官网用。
 */
@Component
@ConditionalOnExpression("'${quote.market.source:none}' == 'simulated' or '${quote.market.source:none}' == 'live'")
public class SimulatedQuoteSource {

  /** price[0] = 当前价；ref = 回归的中心（起始价，或端到端测试 {@link #moveTo} 移到的价） */
  private record Walk(String symbol, int scale, BigDecimal spread, double[] price, double[] ref) {}

  /** 每天的波动（对数价格的标准差） */
  static final double DAILY_VOL = 0.005;
  /** 长期离中心价的标准差 */
  static final double LONG_RUN_SD = 0.01;

  /** 后台起始价的品种用的点差：起始价的万分之一，按小数位四舍五入，至少 1 个最小价格单位（csv 里外汇大约是这个比例） */
  static final BigDecimal SEED_SPREAD_RATE = new BigDecimal("0.0001");

  private final QuoteProcessor processor;
  private final QuoteRouter router;
  private final QuoteProperties props;
  private final SimStartPrices startPrices;
  private final Clock clock;
  /** 计时线程遍历，后台线程追加（{@link #seed}），用写时复制 */
  private final List<Walk> walks = new CopyOnWriteArrayList<>();
  private final Random random = new Random();
  private ScheduledExecutorService timer;

  /** 库里填了模拟起始价的品种（{@code catalog.SymbolCatalog} 提供） */
  public interface SimStartPrices {
    record Entry(String symbol, BigDecimal price, int digits) {}

    List<Entry> simStartPrices();
  }

  SimulatedQuoteSource(QuoteProcessor processor, QuoteRouter router, QuoteProperties props, SimStartPrices startPrices, Clock clock) {
    this.processor = processor;
    this.router = router;
    this.props = props;
    this.startPrices = startPrices;
    this.clock = clock;
    try (var in = new BufferedReader(new InputStreamReader(new ClassPathResource("simulated-prices.csv").getInputStream(), StandardCharsets.UTF_8))) {
      in.lines().filter(l -> !l.isBlank() && !l.startsWith("#") && !l.startsWith("code,")).forEach(l -> {
        String[] p = l.split(",");
        BigDecimal price = new BigDecimal(p[1]);
        walks.add(new Walk(p[0], Math.max(price.scale(), new BigDecimal(p[2]).scale()), new BigDecimal(p[2]), new double[] {price.doubleValue()},
            new double[] {price.doubleValue()}));
      });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @EventListener(ApplicationReadyEvent.class)
  void start() {
    seedFromDatabase();
    timer = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "simulated-quotes"));
    long ms = props.simulatedInterval().toMillis();
    timer.scheduleAtFixedRate(this::tick, ms, ms, TimeUnit.MILLISECONDS);
  }

  /** 库里填了模拟起始价、csv 里没有的品种加入随机游走（启动时调用；测试里调用它模拟重启）。返回这次加入的品种 */
  public List<String> seedFromDatabase() {
    return startPrices.simStartPrices().stream().filter(e -> seed(e.symbol(), e.price(), e.digits())).map(SimStartPrices.Entry::symbol).toList();
  }

  /** 是否已经在给这个品种报价（csv 或后台起始价） */
  public boolean has(String symbol) {
    return walks.stream().anyMatch(w -> w.symbol().equals(symbol));
  }

  /**
   * 后台起始价：还没有在报价的品种从 startPrice 开始随机游走（和其他品种同样的规则），立即推一条报价。
   * 已经在报价的品种（csv 里有，或之前已经加入）不变：避免价格跳变触发质量守卫、打到已有持仓的止盈止损。
   *
   * @return 是否加入了
   */
  public synchronized boolean seed(String symbol, BigDecimal startPrice, int digits) {
    if (symbol == null || startPrice == null || startPrice.signum() <= 0 || has(symbol)) return false;
    Walk w = new Walk(symbol, digits, seedSpread(startPrice, digits), new double[] {startPrice.doubleValue()}, new double[] {startPrice.doubleValue()});
    walks.add(w);
    synchronized (w) {
      emit(quote(w, clock.instant()));
    }
    return true;
  }

  static BigDecimal seedSpread(BigDecimal price, int digits) {
    BigDecimal point = BigDecimal.ONE.movePointLeft(digits);
    return price.multiply(SEED_SPREAD_RATE).setScale(digits, RoundingMode.HALF_UP).max(point);
  }

  private void tick() {
    Instant now = clock.instant();
    double sigma = stepSigma(props.simulatedInterval());
    double theta = stepTheta(sigma);
    for (Walk w : walks) {
      synchronized (w) {
        w.price()[0] = step(w.price()[0], w.ref()[0], sigma, theta, random.nextGaussian());
        emit(quote(w, now));
      }
    }
  }

  /** 每一步的波动：每天 DAILY_VOL，一天的步数 = 86400 秒 / 间隔 */
  static double stepSigma(java.time.Duration interval) {
    double steps = 86_400_000.0 / Math.max(1, interval.toMillis());
    return DAILY_VOL / Math.sqrt(steps);
  }

  /** 每一步往中心拉回的比例：长期标准差 = sigma / sqrt(2θ) = LONG_RUN_SD */
  static double stepTheta(double sigma) {
    return sigma * sigma / (2 * LONG_RUN_SD * LONG_RUN_SD);
  }

  /** 对数价格的 OU 一步：x = ln(price / ref)，x ← x × (1 − θ) + sigma × z */
  static double step(double price, double ref, double sigma, double theta, double z) {
    double x = Math.log(price / ref);
    return ref * Math.exp(x * (1 - theta) + sigma * z);
  }

  /** 交易报价来自真实源的品种只写官网示例行情，其余进交易链路 */
  private void emit(RawQuote q) {
    if (router.liveTrading(q.symbol())) processor.submitSample(q);
    else processor.submit(q);
  }

  private static RawQuote quote(Walk w, Instant now) {
    BigDecimal bid = BigDecimal.valueOf(w.price()[0]).setScale(w.scale(), RoundingMode.HALF_UP);
    return new RawQuote(w.symbol(), bid, bid.add(w.spread()), now);
  }

  /**
   * 把某个品种的原始卖价（不含加点）移到 bid，立即推一条报价，之后从这里继续随机游走。
   * 只给端到端测试用（{@link E2eMove}，profile e2e），让止盈止损按测试的节奏触发。
   *
   * @return 推出的那条原始报价；没有这个品种时为空
   */
  public Optional<RawQuote> moveTo(String symbol, BigDecimal bid) {
    for (Walk w : walks) {
      if (!w.symbol().equals(symbol)) continue;
      synchronized (w) {
        w.price()[0] = bid.doubleValue();
        // 端到端测试按自己的节奏移价：之后围绕新价格游走，不被拉回原来的起始价
        w.ref()[0] = bid.doubleValue();
        RawQuote q = quote(w, clock.instant());
        emit(q);
        return Optional.of(q);
      }
    }
    return Optional.empty();
  }

  @PreDestroy
  void stop() {
    if (timer != null) timer.shutdownNow();
  }
}
