package app.trading.market.web;

import app.trading.market.api.MarketApi;
import app.trading.market.api.SymbolSpec;
import app.quote.protocol.Message;
import app.trading.market.kline.Klines;
import app.trading.market.quote.QuoteProcessor;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 行情推送 WebSocket（/api/ws/market，需要登录，握手时由登录检查过滤器校验 Cookie）。
 * <ul>
 *   <li>连上后先推一次全部报价，之后每 250 毫秒推一次有变化的报价：{"type":"quotes","data":[…]}</li>
 *   <li>K 线：客户端发 {"op":"subKline","symbol":"XAUUSD","tf":"15m"} 订阅（同时只订阅一个），之后推 {"type":"kline",…}；
 *       K 线在报价服务里算，这里把所有客户端订阅的"品种|周期"告诉报价服务（{@link Klines#watch}），收到变化再转给订阅的客户端</li>
 *   <li>第 3 批（原型 P3"暂无报价"）：连着报价服务时（source=remote），开市的品种没有一个能成交的报价（超过 {@code max-quote-age} 3 秒没收到新报价等）就推
 *       {"type":"quoteStatus","available":false}，恢复后推 true（连上时先推一次当前状态）；客户端据此显示"报价暂时中断"、灰掉下单</li>
 * </ul>
 */
@Component
class MarketPush extends TextWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(MarketPush.class);

  private final QuoteProcessor processor;
  private final MarketApi market;
  private final ObjectMapper json;
  private final Klines klines;
  private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
  /** 会话 → 订阅的 "symbol|tf" */
  private final Map<String, String> klineSubs = new ConcurrentHashMap<>();
  private final Map<String, Message.Bar> pendingBars = new ConcurrentHashMap<>();
  private final app.trading.market.MarketProperties props;
  private final java.time.Clock clock;
  private volatile Boolean lastAvailable;
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "market-push"));

  MarketPush(QuoteProcessor processor, MarketApi market, Klines klines, ObjectMapper json, app.trading.market.MarketProperties props, java.time.Clock clock) {
    this.clock = clock;
    this.processor = processor;
    this.market = market;
    this.json = json;
    this.klines = klines;
    this.props = props;
    klines.onUpdate(b -> pendingBars.put(b.symbol() + "|" + b.tf(), b));
    long ms = props.pushInterval().toMillis();
    timer.scheduleAtFixedRate(this::flush, ms, ms, TimeUnit.MILLISECONDS);
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession s) {
    WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(s, 2000, 512 * 1024);
    sessions.put(s.getId(), safe);
    send(safe, Map.of("type", "quotes", "data", quotes(null)));
    if (remote()) send(safe, Map.of("type", "quoteStatus", "available", quoteAvailable()));
  }

  private boolean remote() {
    return "remote".equalsIgnoreCase(String.valueOf(props.source()).strip());
  }

  /**
   * 开市的品种里有没有至少一个现在可以成交的报价（新鲜、没过期、最近 max-quote-age 内收到过，和撮合的过期保护同一口径）；没有开市的品种算有。
   * 报价服务停了（收不到报价）或者只推过期 / 暂无报价状态时为 false
   */
  boolean quoteAvailable() {
    java.time.Instant now = clock.instant();
    boolean anyOpen = false;
    for (SymbolSpec s : market.symbols()) {
      if (!market.isTradingOpen(s.code(), now)) continue;
      anyOpen = true;
      if (processor.latest(s.code()).map(app.trading.market.api.MarketQuote::executable).orElse(false)) return true;
    }
    return !anyOpen;
  }

  @Override
  protected void handleTextMessage(WebSocketSession s, TextMessage msg) {
    try {
      JsonNode n = json.readTree(msg.getPayload());
      String op = n.path("op").asString("");
      if ("subKline".equals(op)) klineSubs.put(s.getId(), n.path("symbol").asString("") + "|" + n.path("tf").asString(""));
      else if ("unsubKline".equals(op)) klineSubs.remove(s.getId());
      else return;
      watchBars();
    } catch (RuntimeException e) {
      log.debug("忽略无法解析的消息");
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
    sessions.remove(s.getId());
    if (klineSubs.remove(s.getId()) != null) watchBars();
  }

  /** 告诉报价服务现在要哪些 K 线的实时变化（所有客户端订阅的并集） */
  private void watchBars() {
    klines.watch(java.util.Set.copyOf(klineSubs.values()));
  }

  private List<Views.QuoteView> quotes(Set<String> only) {
    List<Views.QuoteView> out = new ArrayList<>();
    for (var e : processor.book().all()) {
      if (only != null && !only.contains(e.quote().symbol())) continue;
      market.symbol(e.quote().symbol()).ifPresent(s -> out.add(Views.quote(e, s, market)));
    }
    return out;
  }

  void flush() {
    try {
      if (remote()) {
        boolean available = quoteAvailable();
        if (lastAvailable == null || lastAvailable != available) {
          lastAvailable = available;
          Map<String, Object> st = Map.of("type", "quoteStatus", "available", available);
          sessions.values().forEach(x -> send(x, st));
        }
      }
      Set<String> changed = processor.drainChanged();
      if (!changed.isEmpty() && !sessions.isEmpty()) {
        Map<String, Object> msg = Map.of("type", "quotes", "data", quotes(changed));
        sessions.values().forEach(s -> send(s, msg));
      }
      if (!pendingBars.isEmpty()) {
        Map<String, Message.Bar> bars = Map.copyOf(pendingBars);
        pendingBars.keySet().removeAll(bars.keySet());
        klineSubs.forEach((sid, key) -> {
          Message.Bar b = bars.get(key);
          WebSocketSession s = sessions.get(sid);
          if (b == null || s == null) return;
          int digits = market.symbol(b.symbol()).map(SymbolSpec::digits).orElse(8);
          send(s, Map.of("type", "kline", "symbol", b.symbol(), "tf", b.tf(), "bar", Views.kline(b, digits)));
        });
      }
    } catch (RuntimeException e) {
      log.error("行情推送失败", e);
    }
  }

  private void send(WebSocketSession s, Object payload) {
    try {
      if (s.isOpen()) s.sendMessage(new TextMessage(json.writeValueAsString(payload)));
    } catch (IOException | RuntimeException e) {
      log.debug("推送失败，连接可能已断开");
    }
  }

  @PreDestroy
  void stop() {
    timer.shutdownNow();
  }
}
