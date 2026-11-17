package app.quote.open;

import app.quote.catalog.QuoteSymbol;
import app.quote.catalog.SymbolCatalog;
import app.quote.kline.Bar;
import app.quote.protocol.QuoteFrame;
import app.quote.protocol.Timeframe;
import app.quote.stream.QuoteStream;
import app.quote.stream.StreamProperties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.JsonNode;

/**
 * 对外 WebSocket {@code /v1/ws}（需求 03 页第二节）：和内部同一套协议（TQ-2 A）——连上先 hello，subscribe 带 epoch 和每个品种最后的序号就按序号补齐，
 * 补不上发快照；连接中跳号发 resync；watchBars 关注 K 线；每秒心跳。对外的不同：
 * <ul>
 *   <li>subscribe 带 {@code symbols}（整份替换，退订 = 发一份更小的）；超过每条连接的品种数限额 → 回 {@code error SUBSCRIBE_LIMIT}，已订阅的照常推；
 *       拿不到的品种（Key 范围外、没有转售授权）→ 回 {@code error SYMBOL_NOT_AVAILABLE}，其余照常订阅；</li>
 *   <li>报价只出白名单字段（{@link OpenViews#wsQuote}）；现在的源没有转售授权、休市、暂无报价时推状态、不推价格；</li>
 *   <li>每秒检查一次：Key 停用 / 轮换（关闭码 4401）、外部 Key 遇到总开关关掉（4403）就断开（要求 5 秒内）；</li>
 *   <li>计量：推送条数按 Key、按品种；连接秒数和连接数峰值按 Key（{@link Usage}）。心跳不算推送。</li>
 * </ul>
 */
@Component
public class OpenStream implements QuoteStream.Tap {

  private static final Logger log = LoggerFactory.getLogger(OpenStream.class);
  static final CloseStatus KEY_DISABLED = new CloseStatus(4401, "KEY_DISABLED");
  static final CloseStatus GATE_CLOSED = new CloseStatus(4403, "OPEN_SERVICE_CLOSED");

  private static final class Conn {
    final WebSocketSession ws;
    final String keyId;
    final String lease;
    /** 已经发过起点、在推实时报价的品种 */
    final Set<String> live = ConcurrentHashMap.newKeySet();
    volatile Set<String> bars = Set.of();
    volatile Instant accounted;

    Conn(WebSocketSession ws, String keyId, String lease, Instant now) {
      this.lease = lease;
      this.ws = ws;
      this.keyId = keyId;
      this.accounted = now;
    }
  }

  private final QuoteStream stream;
  private final ApiKeys keys;
  private final Entitlements ent;
  private final Usage usage;
  private final SymbolCatalog catalog;
  private final StreamProperties streamProps;
  private final OpenProperties props;
  private final Clock clock;
  private final Map<String, Conn> conns = new ConcurrentHashMap<>();
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "quote-open-stream"));

  OpenStream(QuoteStream stream, ApiKeys keys, Entitlements ent, Usage usage, SymbolCatalog catalog, StreamProperties streamProps, OpenProperties props, Clock clock) {
    this.stream = stream;
    this.keys = keys;
    this.ent = ent;
    this.usage = usage;
    this.catalog = catalog;
    this.streamProps = streamProps;
    this.props = props;
    this.clock = clock;
    stream.addTap(this);
  }

  @EventListener(ApplicationReadyEvent.class)
  void start() {
    long hb = streamProps.heartbeat().toMillis();
    timer.scheduleAtFixedRate(this::heartbeat, hb, hb, TimeUnit.MILLISECONDS);
    long sw = props.sweep().toMillis();
    timer.scheduleAtFixedRate(this::sweep, sw, sw, TimeUnit.MILLISECONDS);
  }

  public int connections() {
    return conns.size();
  }

  void open(WebSocketSession ws, String keyId, String lease) {
    WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(ws, (int) streamProps.sendTimeLimit().toMillis(), streamProps.sendBufferLimit());
    Conn c = new Conn(safe, keyId, lease, clock.instant());
    conns.put(ws.getId(), c);
    Map<String, Object> hello = new LinkedHashMap<>();
    hello.put("type", "hello");
    hello.put("epoch", stream.epoch());
    hello.put("serverTime", clock.millis());
    send(c, hello);
  }

  void close(String id) {
    Conn c = conns.remove(id);
    if (c != null) {
      account(c);
      usage.wsClose(c.keyId, c.lease);
    }
  }

  /** 客户端发来的一条消息 */
  void handle(String id, String text) {
    Conn c = conns.get(id);
    if (c == null) return;
    JsonNode n;
    try {
      n = OpenErrors.JSON.readTree(text);
    } catch (RuntimeException e) {
      error(c, "BAD_MESSAGE", "不是 JSON", null);
      return;
    }
    String op = n == null ? "" : n.path("op").asString("");
    switch (op) {
      case "subscribe" -> subscribe(c, n);
      case "watchBars" -> watchBars(c, n);
      case "resync" -> resync(c, n);
      default -> error(c, "BAD_MESSAGE", "只能发 subscribe、resync、watchBars", null);
    }
  }

  private void subscribe(Conn c, JsonNode n) {
    ApiKeys.Key k = keys.find(c.keyId).orElse(null);
    if (k == null || !k.active()) return;
    Set<String> want = new LinkedHashSet<>();
    for (JsonNode s : n.path("symbols")) want.add(s.asString("").strip().toUpperCase(java.util.Locale.ROOT));
    want.remove("");
    if (want.size() > k.limits().wsSymbols()) {
      error(c, "SUBSCRIBE_LIMIT", "每条连接最多订阅 " + k.limits().wsSymbols() + " 个品种，这次订阅没有生效，已订阅的照常推送", null);
      return;
    }
    List<String> denied = new ArrayList<>();
    Set<String> ok = new LinkedHashSet<>();
    for (String s : want) {
      if (ent.visible(k, s)) ok.add(s);
      else denied.add(s);
    }
    if (!denied.isEmpty()) error(c, "SYMBOL_NOT_AVAILABLE", "这个 Key 拿不到这些品种", denied);
    boolean sameEpoch = stream.epoch().equals(n.path("epoch").asString(null));
    JsonNode since = n.path("since");
    c.live.retainAll(ok);
    c.bars = filterBars(c.bars, ok);
    for (String s : ok) {
      if (c.live.contains(s)) continue;
      Long after = sameEpoch && since.has(s) ? since.path(s).asLong() : null;
      stream.attach(s, after, f -> deliver(c, k, f), () -> c.live.add(s));
    }
    Map<String, Object> ack = new LinkedHashMap<>();
    ack.put("type", "subscribed");
    ack.put("symbols", List.copyOf(ok));
    send(c, ack);
  }

  private void resync(Conn c, JsonNode n) {
    ApiKeys.Key k = keys.find(c.keyId).orElse(null);
    if (k == null) return;
    JsonNode syms = n.path("symbols");
    for (String s : syms.propertyNames()) {
      if (!c.live.contains(s)) continue;
      long v = syms.path(s).asLong(-1);
      stream.attach(s, v < 0 ? null : v, f -> deliver(c, k, f), () -> {});
    }
  }

  private void watchBars(Conn c, JsonNode n) {
    Set<String> keysWanted = new LinkedHashSet<>();
    for (JsonNode x : n.path("keys")) keysWanted.add(x.asString(""));
    c.bars = filterBars(keysWanted, c.live);
  }

  /** 只留"品种|周期"格式对、品种已订阅的 */
  private static Set<String> filterBars(Set<String> want, Set<String> symbols) {
    Set<String> out = new LinkedHashSet<>();
    for (String k : want) {
      int i = k.indexOf('|');
      if (i <= 0) continue;
      if (symbols.contains(k.substring(0, i)) && Timeframe.of(k.substring(i + 1)).isPresent()) out.add(k);
    }
    return Set.copyOf(out);
  }

  @Override
  public void onQuote(QuoteFrame f) {
    if (conns.isEmpty()) return;
    for (Conn c : conns.values()) {
      if (!c.live.contains(f.symbol())) continue;
      ApiKeys.Key k = keys.find(c.keyId).orElse(null);
      if (k == null || !k.active()) continue; // 由 sweep 断开
      deliver(c, k, f);
    }
  }

  private void deliver(Conn c, ApiKeys.Key k, QuoteFrame f) {
    boolean licensed = ent.inScope(k, f.symbol()) && ent.resaleAllowed(k, f.feed(), f.symbol());
    if (send(c, OpenViews.wsQuote(f, licensed))) usage.push(c.keyId, f.symbol());
  }

  @Override
  public void onBar(Bar b) {
    if (conns.isEmpty()) return;
    String key = b.symbol() + "|" + b.tf().code;
    int digits = catalog.get(b.symbol()).map(QuoteSymbol::digits).orElse(5);
    String feed = null;
    boolean feedKnown = false;
    for (Conn c : conns.values()) {
      if (!c.bars.contains(key)) continue;
      ApiKeys.Key k = keys.find(c.keyId).orElse(null);
      if (k == null || !k.active()) continue;
      if (!feedKnown) {
        feed = ent.currentFeed(b.symbol()).orElse(null);
        feedKnown = true;
      }
      if (!ent.resaleAllowed(k, feed, b.symbol())) continue;
      if (send(c, OpenViews.wsBar(b, digits))) usage.push(c.keyId, b.symbol());
    }
  }

  private void heartbeat() {
    try {
      if (conns.isEmpty()) return;
      Map<String, Object> hb = new LinkedHashMap<>();
      hb.put("type", "heartbeat");
      hb.put("serverTime", clock.millis());
      for (Conn c : conns.values()) send(c, hb);
    } catch (RuntimeException e) {
      log.error("对外心跳失败", e);
    }
  }

  /** Key 停用、总开关关掉的连接断开；顺便记连接时长 */
  void sweep() {
    try {
      for (Conn c : List.copyOf(conns.values())) {
        ApiKeys.Key k = keys.find(c.keyId).orElse(null);
        if (k == null || !k.active()) drop(c, KEY_DISABLED);
        else if (!ent.gatePasses(k)) drop(c, GATE_CLOSED);
        else account(c);
      }
    } catch (RuntimeException e) {
      log.error("检查对外连接失败", e);
    }
  }

  /** 立即断开这个 Key 的所有连接（停用、轮换后调用） */
  public void dropKey(String keyId) {
    for (Conn c : List.copyOf(conns.values())) if (c.keyId.equals(keyId)) drop(c, KEY_DISABLED);
  }

  /** 立即断开所有外部 Key 的连接（总开关关掉后调用） */
  public void dropExternal() {
    for (Conn c : List.copyOf(conns.values())) {
      ApiKeys.Key k = keys.find(c.keyId).orElse(null);
      if (k == null || !ent.gatePasses(k)) drop(c, GATE_CLOSED);
    }
  }

  private void drop(Conn c, CloseStatus why) {
    if (conns.remove(c.ws.getId()) == null) return;
    account(c);
    usage.wsClose(c.keyId, c.lease);
    try {
      c.ws.close(why);
    } catch (IOException | RuntimeException ignored) {
      // 已经断了
    }
  }

  private void account(Conn c) {
    Instant now = clock.instant();
    long secs = now.getEpochSecond() - c.accounted.getEpochSecond();
    if (secs > 0) {
      usage.wsSeconds(c.keyId, secs);
      c.accounted = c.accounted.plusSeconds(secs);
    }
  }

  private void error(Conn c, String code, String message, List<String> symbols) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", "error");
    m.put("code", code);
    m.put("message", message);
    if (symbols != null) m.put("symbols", symbols);
    send(c, m);
  }

  private boolean send(Conn c, Map<String, Object> m) {
    try {
      if (!c.ws.isOpen()) return false;
      c.ws.sendMessage(new TextMessage(OpenErrors.JSON.writeValueAsString(m)));
      return true;
    } catch (IOException | RuntimeException e) {
      log.warn("对外报价连接 {} 发送失败，断开：{}", c.ws.getId(), e.getClass().getSimpleName());
      drop(c, CloseStatus.SESSION_NOT_RELIABLE);
      return false;
    }
  }

  @PreDestroy
  void stop() {
    timer.shutdownNow();
  }
}
