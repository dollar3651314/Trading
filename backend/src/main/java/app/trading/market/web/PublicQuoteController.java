package app.trading.market.web;

import app.trading.market.api.MarketApi;
import app.trading.market.api.MarketQuote;
import app.trading.market.api.SymbolSpec;
import app.trading.market.quote.QuoteBook;
import app.trading.market.quote.QuoteProcessor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 官网公开行情（v2 需求 4.2、AC2-02、AC2-03；内部文档 第 3 节）：{@code GET /api/public/quotes}，<b>不需要登录</b>。
 * <ul>
 *   <li>只返回配置的那几个品种（{@link PublicQuoteProperties#symbols()}）的代码、中英文名、<b>卖价</b>、当日涨跌幅、报价时间、状态；
 *       不返回买价、加点、账户、持仓、敞口、阈值等任何其他字段（约定 4；字段由 {@code PublicQuoteControllerTest} 逐个检查）。</li>
 *   <li>休市（不在交易时段）返回 {@code CLOSED}，没有报价、报价过期或异常返回 {@code NO_QUOTE}；这两种状态下价格、涨跌幅、时间都是 null，
 *       <b>不返回过期价格</b>。</li>
 *   <li>整份结果缓存 {@code cacheTtl}（默认 1 秒）：所有访客共用一份，刷接口不增加计算量；另外受通用限流（{@code IpRateLimitFilter}，按 IP 每分钟 600 次）
 *       和 Nginx 外层限流约束。官网每秒轮询一次，一个页面每分钟 60 次。</li>
 *   <li>{@code sample}：有品种的价格来自模拟报价源时为 true，官网据此标"示例行情"。</li>
 *   <li><b>默认不展示真实报价源的价格</b>（内部文档 第 6 节：两家数据商的条款都禁止未授权的公开展示）：
 *       {@code FX_PUBLIC_QUOTES_SOURCE=simulated}（默认）时，交易报价来自真实源的品种改取模拟源的"示例行情"
 *       （{@link QuoteProcessor#latestSample}），{@code sample} 为 true；{@code live} 时才取交易报价。</li>
 *   <li><b>第 3 批（TD-9 A、TY-4、AC-Q10）：生产不显示价格</b>：报价服务是 live 模式（生产）且没有明确打开 {@code FX_PUBLIC_QUOTES_SOURCE=live}
 *       （要先另谈公开展示授权、Amos 同意）时，{@code pricesHidden} 为 true，每个品种只给代码、中英文名、状态，<b>不返回价格、涨跌幅、时间</b>；
 *       官网每行显示"登录后查看实时行情"。三项授权（交易用途、登录后展示、转售）都不覆盖不登录的公开展示。</li>
 * </ul>
 * 选轮询不用 WebSocket 的理由见技术方案：结果本来就按秒缓存，轮询简单、无状态、经过 Nginx 和限流不用特殊处理，断网恢复后自然继续。
 */
@RestController
@EnableConfigurationProperties(PublicQuoteProperties.class)
class PublicQuoteController {

  private static final Logger log = LoggerFactory.getLogger(PublicQuoteController.class);

  /** 状态：开市且有新鲜报价 */
  static final String OPEN = "OPEN";
  /** 状态：不在交易时段（官网显示"休市"） */
  static final String CLOSED = "CLOSED";
  /** 状态：开市但没有可用报价（没收到、过期、异常；官网显示"暂无报价"） */
  static final String NO_QUOTE = "NO_QUOTE";

  /** 一个品种。价格、涨跌幅、时间只在 OPEN 时有值 */
  record Item(String symbol, String nameZh, String nameEn, String bid, String changePct, String time, String status) {}

  /** 整份结果；pricesHidden（第 3 批）：生产不显示价格，官网只列品种名和"登录后查看实时行情" */
  record PublicQuotes(boolean sample, @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT) boolean pricesHidden,
      List<Item> items) {}

  private record Cached(Instant at, PublicQuotes body) {}

  private final MarketApi market;
  private final QuoteProcessor processor;
  private final PublicQuoteProperties props;
  private final Clock clock;
  private volatile Cached cached;

  PublicQuoteController(MarketApi market, QuoteProcessor processor, PublicQuoteProperties props, Clock clock) {
    this.market = market;
    this.processor = processor;
    this.props = props;
    this.clock = clock;
    if (props.live()) log.warn("官网公开行情展示交易报价（FX_PUBLIC_QUOTES_SOURCE=live）：真实报价源必须已有书面的公开展示授权");
    for (String code : props.symbols()) {
      if (market.symbol(code).isEmpty()) log.warn("官网公开行情配置了品种表里没有的代码 {}，忽略", code);
    }
  }

  @GetMapping("/api/public/quotes")
  ResponseEntity<PublicQuotes> quotes() {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(current());
  }

  /** 缓存过期才重新计算；并发时最多多算一次，结果一样，不加锁 */
  PublicQuotes current() {
    Instant now = clock.instant();
    Cached c = cached;
    if (c == null || now.isBefore(c.at()) || !now.isBefore(c.at().plus(props.cacheTtl()))) {
      c = new Cached(now, build(now));
      cached = c;
    }
    return c.body();
  }

  /** 测试用：清掉缓存 */
  void clearCache() {
    cached = null;
  }

  /** 生产（报价服务 live 模式）不显示价格，除非明确打开了 FX_PUBLIC_QUOTES_SOURCE=live（另谈公开展示授权、Amos 同意以后） */
  boolean pricesHidden() {
    return !props.live() && processor.quoteMode().map("live"::equals).orElse(false);
  }

  private PublicQuotes build(Instant now) {
    List<Item> items = new ArrayList<>();
    if (pricesHidden()) {
      for (String code : props.symbols()) {
        market.symbol(code).ifPresent(s -> items.add(empty(s, market.isTradingOpen(s.code(), now) ? OPEN : CLOSED)));
      }
      return new PublicQuotes(false, true, List.copyOf(items));
    }
    boolean sample = false;
    for (String code : props.symbols()) {
      Optional<SymbolSpec> s = market.symbol(code);
      if (s.isEmpty()) continue;
      boolean fromSample = !props.live() && processor.liveTrading(code);
      // 价格来自模拟源：取示例行情，或交易报价本来就是模拟价（报价服务是模拟模式）
      sample |= fromSample || processor.quoteMode().map("simulated"::equals).orElse(false) && !processor.liveTrading(code);
      items.add(item(s.get(), now, fromSample));
    }
    return new PublicQuotes(sample, false, List.copyOf(items));
  }

  private Item item(SymbolSpec s, Instant now, boolean fromSample) {
    if (!market.isTradingOpen(s.code(), now)) return empty(s, CLOSED);
    Optional<MarketQuote> q = fromSample ? processor.latestSample(s.code()) : processor.latest(s.code());
    Optional<QuoteBook.Entry> e = (fromSample ? processor.sampleBook() : processor.book()).get(s.code());
    if (q.isEmpty() || !q.get().executable() || e.isEmpty()) return empty(s, NO_QUOTE);
    BigDecimal bid = q.get().bid();
    return new Item(s.code(), s.nameZh(), s.nameEn(), Views.price(bid, s.digits()), changePct(e.get(), bid, now), q.get().time().toString(), OPEN);
  }

  /** 当日涨跌幅（%，两位小数）= (卖价 − 今日开盘卖价) ÷ 今日开盘卖价 × 100；和客户端 lib/trading.ts 的 changePercent 同一口径（按 UTC 日） */
  static String changePct(QuoteBook.Entry e, BigDecimal bid, Instant now) {
    long today = now.atZone(ZoneOffset.UTC).toLocalDate().toEpochDay();
    BigDecimal open = e.day() == today ? e.dayOpen() : bid;
    if (open.signum() == 0) return "0.00";
    return bid.subtract(open).multiply(BigDecimal.valueOf(100)).divide(open, 2, RoundingMode.HALF_UP).toPlainString();
  }

  private static Item empty(SymbolSpec s, String status) {
    return new Item(s.code(), s.nameZh(), s.nameEn(), null, null, null, status);
  }
}
