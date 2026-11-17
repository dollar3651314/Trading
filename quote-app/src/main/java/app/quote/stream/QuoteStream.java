package app.quote.stream;

import app.quote.QuoteProperties;
import app.quote.feed.QuoteRouter;
import app.quote.feed.QuoteSource;
import app.quote.feed.TradingLicenses;
import app.quote.health.AlertCheck;
import app.quote.pipeline.CleanQuote;
import app.quote.protocol.Message;
import app.quote.protocol.QuoteCodec;
import app.quote.protocol.QuoteFrame;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * 内部 WebSocket 的发送端（协议见 quote-protocol 的 {@code app.quote.protocol}）：
 * <ul>
 *   <li>每个品种一个序号（从 1 开始，每推一条加 1），在该品种的锁里编号、记进缓存、发给已订阅的连接，所以同一品种在每条连接上严格按序号；</li>
 *   <li>每个品种在内存里留最近 {@code replayBuffer} 条：客户端重连时带上 epoch 和每个品种最后的序号，缓存里还有就补齐，没有就发快照；</li>
 *   <li>{@code epoch} 是这次启动的随机编号，重启后变（序号重新从 1 开始，客户端据此丢掉旧序号）；</li>
 *   <li>每条报价带当前报价源和状态（{@link QuoteRouter#current}）、能不能用来开仓（"交易用途"授权，{@link TradingLicenses}）；</li>
 *   <li>每秒一条心跳，带本进程的告警项（报价源断线、切备源、偏差），trading-app 照样发告警邮件；</li>
 *   <li>K 线变化（{@code bar}）只发给用 {@code watchBars} 关注了那个"品种|周期"的连接（trading-app 按客户端图表订阅的来关注）。</li>
 * </ul>
 * 第 3 批：对外 WebSocket（{@code app.quote.open}）用 {@link #addTap} 在同一把品种锁里收每条报价和 K 线变化，用 {@link #attach} 补齐或拿快照，
 * 所以对外和对内是同一套序号、同一套补齐规则（TQ-2 A）。
 * 发送用 {@link ConcurrentWebSocketSessionDecorator}：一条连接发不出去（超时或积压太多）就断开它，客户端重连后补齐，不拖慢其他连接和报价处理线程。
 */
@Component
public class QuoteStream {

  private static final Logger log = LoggerFactory.getLogger(QuoteStream.class);

  /** 一个品种的序号和最近的报价（按品种加锁） */
  private static final class Sym {
    long seq;
    final Deque<QuoteFrame> buffer = new ArrayDeque<>();
    QuoteFrame latest;
  }

  /** 一条连接 */
  private static final class Conn {
    final WebSocketSession ws;
    /** 已经发过起点（补齐或快照）的品种；之后这些品种的实时报价才发给它 */
    final Set<String> live = ConcurrentHashMap.newKeySet();
    volatile boolean all;
    /** 关注的 K 线（"品种|周期"） */
    volatile Set<String> bars = Set.of();

    Conn(WebSocketSession ws) {
      this.ws = ws;
    }

    boolean wants(String symbol) {
      return all || live.contains(symbol);
    }
  }

  private final StreamProperties props;
  private final ObjectProvider<QuoteRouter> router;
  private final TradingLicenses licenses;
  private final ObjectProvider<List<AlertCheck>> alerts;
  private final QuoteProperties quote;
  private final app.quote.catalog.SymbolCatalog catalog;
  private final Clock clock;
  private final String epoch = UUID.randomUUID().toString();
  private final Map<String, Sym> syms = new ConcurrentHashMap<>();
  private final Map<String, Conn> conns = new ConcurrentHashMap<>();
  private final List<Tap> taps = new java.util.concurrent.CopyOnWriteArrayList<>();
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "quote-stream-heartbeat"));

  QuoteStream(StreamProperties props, ObjectProvider<QuoteRouter> router, TradingLicenses licenses, ObjectProvider<List<AlertCheck>> alerts, QuoteProperties quote,
      app.quote.catalog.SymbolCatalog catalog, app.quote.kline.KlineService klines, Clock clock) {
    this.catalog = catalog;
    klines.onUpdate(this::bar);
    this.props = props;
    this.router = router;
    this.licenses = licenses;
    this.alerts = alerts;
    this.quote = quote;
    this.clock = clock;
  }

  /** 第 3 批：在品种锁里收每条报价（编号以后）和每根 K 线变化（对外 WebSocket、报价源状态统计用）。回调要快，不能阻塞 */
  public interface Tap {
    void onQuote(QuoteFrame f);

    default void onBar(app.quote.kline.Bar b) {}
  }

  public void addTap(Tap t) {
    taps.add(t);
  }

  /**
   * 第 3 批：在这个品种的锁里，按和内部连接一样的规则把 after 之后的报价交给 sink（缓存里有就按序号补齐，没有或 after 为空就给快照），
   * 然后执行 live（调用方在这里把品种标成"已在推"），保证补齐和之后的实时报价之间不漏不重。这个品种还没有报价时只执行 live。
   */
  public void attach(String symbol, Long after, java.util.function.Consumer<QuoteFrame> sink, Runnable live) {
    Sym s = syms.computeIfAbsent(symbol, k -> new Sym());
    synchronized (s) {
      if (s.latest != null && (after == null || after < s.seq)) {
        if (after != null && !s.buffer.isEmpty() && s.buffer.peekFirst().seq() <= after + 1) {
          for (QuoteFrame f : s.buffer) if (f.seq() > after) sink.accept(f);
        } else {
          sink.accept(s.latest.asSnapshot());
        }
      }
      live.run();
    }
  }

  @EventListener(ApplicationReadyEvent.class)
  void start() {
    long ms = props.heartbeat().toMillis();
    timer.scheduleAtFixedRate(this::heartbeat, ms, ms, TimeUnit.MILLISECONDS);
  }

  public String epoch() {
    return epoch;
  }

  /** 已连接的客户端数 */
  public int connections() {
    return conns.size();
  }

  /** 推一条报价：编号、进缓存、发给要这个品种的连接。由该品种的处理线程调用 */
  public void publish(CleanQuote q) {
    Optional<QuoteSource> src = Optional.ofNullable(router.getIfAvailable()).flatMap(r -> r.current(q.symbol()));
    String feed = src.map(QuoteSource::feed).orElse(null);
    String state = src.map(QuoteSource::state).orElse(null);
    boolean tradable = !licenses.enforced() || (feed != null && licenses.tradingUse(feed, q.symbol()));
    Sym s = syms.computeIfAbsent(q.symbol(), k -> new Sym());
    synchronized (s) {
      QuoteFrame f = new QuoteFrame(q.symbol(), ++s.seq, q.bid(), q.ask(), q.time().toEpochMilli(), q.status().name(), state, feed, tradable, false);
      s.latest = f;
      s.buffer.addLast(f);
      while (s.buffer.size() > props.replayBuffer()) s.buffer.removeFirst();
      String text = QuoteCodec.encode(f);
      for (Conn c : conns.values()) if (c.wants(q.symbol())) send(c, text);
      for (Tap t : taps) tap(() -> t.onQuote(f));
    }
  }

  /** 官网示例行情（不编号、不缓存） */
  public void sample(String symbol, BigDecimal bid, BigDecimal ask, Instant time) {
    String text = QuoteCodec.encode(new Message.Sample(symbol, bid, ask, time.toEpochMilli()));
    for (Conn c : conns.values()) if (c.all) send(c, text);
  }

  /** 一根 K 线有变化：发给关注了它的连接（不编号、不缓存，只用来显示）。由该品种的处理线程调用 */
  void bar(app.quote.kline.Bar b) {
    for (Tap t : taps) tap(() -> t.onBar(b));
    String key = b.symbol() + "|" + b.tf().code;
    String text = null;
    for (Conn c : conns.values()) {
      if (!c.bars.contains(key)) continue;
      if (text == null) text = QuoteCodec.encode(new Message.Bar(b.symbol(), b.tf().code, b.time(), b.open(), b.high(), b.low(), b.close(), b.ticks()));
      send(c, text);
    }
  }

  /** watchBars：整份替换这条连接关注的 K 线 */
  void watchBars(String id, Message.WatchBars w) {
    Conn c = conns.get(id);
    if (c != null) c.bars = w.keys();
  }

  /** 这个品种最新的一条（测试和健康检查看）；没有为空 */
  public Optional<QuoteFrame> latest(String symbol) {
    Sym s = syms.get(symbol);
    if (s == null) return Optional.empty();
    synchronized (s) {
      return Optional.ofNullable(s.latest);
    }
  }

  void open(WebSocketSession ws) {
    WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(ws, (int) props.sendTimeLimit().toMillis(), props.sendBufferLimit());
    Conn c = new Conn(safe);
    conns.put(ws.getId(), c);
    send(c, QuoteCodec.encode(new Message.Hello(epoch, mode(), clock.millis(), sources())));
    log.info("内部报价连接 {} 已建立（现在 {} 条）", ws.getId(), conns.size());
  }

  void close(String id) {
    if (conns.remove(id) != null) log.info("内部报价连接 {} 已断开（现在 {} 条）", id, conns.size());
  }

  /** subscribe：epoch 相同的按序号补齐，补不上或第一次连接发快照；之后全部品种的实时报价都发 */
  void subscribe(String id, Message.Subscribe sub) {
    Conn c = conns.get(id);
    if (c == null) return;
    Map<String, Long> since = epoch.equals(sub.epoch()) && sub.since() != null ? sub.since() : Map.of();
    for (var e : syms.entrySet()) {
      Sym s = e.getValue();
      synchronized (s) {
        catchUp(c, e.getKey(), s, since.get(e.getKey()));
        c.live.add(e.getKey());
      }
    }
    c.all = true;
  }

  /** resync：连接中跳号，按同样的规则补齐或发快照 */
  void resync(String id, Message.Resync r) {
    Conn c = conns.get(id);
    if (c == null) return;
    for (var e : r.symbols().entrySet()) {
      Sym s = syms.get(e.getKey());
      if (s == null) continue;
      synchronized (s) {
        catchUp(c, e.getKey(), s, e.getValue() == null || e.getValue() < 0 ? null : e.getValue());
      }
    }
  }

  /** 在品种锁里调用：从 after 的下一条补到最新；缓存里没有 after+1 就发快照（after 为空直接发快照） */
  private void catchUp(Conn c, String symbol, Sym s, Long after) {
    if (s.latest == null) return;
    if (after != null && after >= s.seq) return; // 已经是最新
    if (after != null && !s.buffer.isEmpty() && s.buffer.peekFirst().seq() <= after + 1) {
      for (QuoteFrame f : s.buffer) if (f.seq() > after) send(c, QuoteCodec.encode(f));
      return;
    }
    send(c, QuoteCodec.encode(s.latest.asSnapshot()));
  }

  void error(String id, String code, String message) {
    Conn c = conns.get(id);
    if (c != null) send(c, QuoteCodec.encode(new Message.Error(code, message)));
  }

  private void heartbeat() {
    try {
      if (conns.isEmpty()) return;
      List<Message.Alert> items = new ArrayList<>();
      for (AlertCheck a : alerts.getIfAvailable(List::of)) {
        for (AlertCheck.Item i : a.evaluate()) items.add(new Message.Alert(i.name(), i.titleZh(), i.titleEn(), i.ok(), i.detail()));
      }
      String text = QuoteCodec.encode(new Message.Heartbeat(clock.millis(), items));
      for (Conn c : conns.values()) send(c, text);
    } catch (RuntimeException e) {
      log.error("发心跳失败", e);
    }
  }

  /** 每个品种现在在用的报价源（hello 里带，后台品种页用；还没报价的品种也有） */
  private List<Message.Source> sources() {
    QuoteRouter r = router.getIfAvailable();
    if (r == null) return List.of();
    List<Message.Source> out = new ArrayList<>();
    for (var sym : catalog.all()) r.current(sym.code()).ifPresent(src -> out.add(new Message.Source(sym.code(), src.feed(), src.state())));
    return out;
  }

  private String mode() {
    return QuoteRouter.mode(quote.source()).name().toLowerCase(java.util.Locale.ROOT);
  }

  private static void tap(Runnable r) {
    try {
      r.run();
    } catch (RuntimeException e) {
      log.error("报价分发（对外）出错", e);
    }
  }

  private void send(Conn c, String text) {
    try {
      if (c.ws.isOpen()) c.ws.sendMessage(new TextMessage(text));
    } catch (IOException | RuntimeException e) {
      // 发不出去（超时、积压太多、已断开）：断开这条连接，客户端重连后补齐
      log.warn("内部报价连接 {} 发送失败，断开：{}", c.ws.getId(), e.getClass().getSimpleName());
      conns.remove(c.ws.getId());
      try {
        c.ws.close(CloseStatus.SESSION_NOT_RELIABLE);
      } catch (IOException | RuntimeException ignored) {
        // 已经断了
      }
    }
  }

  /** 测试用：从服务器这边断开所有连接（模拟网络中断），客户端会重连并补齐 */
  public void dropAll() {
    for (Conn c : List.copyOf(conns.values())) {
      conns.remove(c.ws.getId());
      try {
        c.ws.close(CloseStatus.GOING_AWAY);
      } catch (IOException | RuntimeException ignored) {
        // 已经断了
      }
    }
  }

  @PreDestroy
  void stop() {
    timer.shutdownNow();
  }
}
