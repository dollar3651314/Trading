package app.quote.open;

import app.quote.catalog.QuoteSymbol;
import app.quote.catalog.SymbolCatalog;
import app.quote.kline.Bar;
import app.quote.kline.KlineService;
import app.quote.protocol.QuoteFrame;
import app.quote.protocol.Timeframe;
import app.quote.stream.QuoteStream;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对外 REST（需求 03 页第二节）：品种列表、最新报价、K 线、用量、WebSocket 连接票据。鉴权、闸 1、限额在 {@link OpenAuthFilter}；
 * 这里只按闸 2（转售授权）和 Key 的可用品种过滤。字段白名单见 {@link OpenViews}。只计量不收费：没有价格、账单字段（AC-Q11）。
 */
@RestController
class OpenRestController {

  static final int MAX_QUOTES = 50;
  static final int MAX_BARS = 1000;

  private final Entitlements ent;
  private final SymbolCatalog catalog;
  private final QuoteStream stream;
  private final KlineService klines;
  private final Usage usage;
  private final Tickets tickets;
  private final Clock clock;

  OpenRestController(Entitlements ent, SymbolCatalog catalog, QuoteStream stream, KlineService klines, Usage usage, Tickets tickets, Clock clock) {
    this.ent = ent;
    this.catalog = catalog;
    this.stream = stream;
    this.klines = klines;
    this.usage = usage;
    this.tickets = tickets;
    this.clock = clock;
  }

  private static ApiKeys.Key key(HttpServletRequest req) {
    return (ApiKeys.Key) req.getAttribute(OpenAuthFilter.KEY_ATTR);
  }

  @GetMapping("/v1/symbols")
  Map<String, Object> symbols(HttpServletRequest req) {
    ApiKeys.Key k = key(req);
    Instant now = clock.instant();
    List<Map<String, Object>> items = new ArrayList<>();
    for (QuoteSymbol s : ent.visibleSymbols(k)) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("code", s.code());
      m.put("nameZh", s.nameZh());
      m.put("nameEn", s.nameEn());
      m.put("category", s.category());
      m.put("digits", s.digits());
      List<Map<String, Object>> sessions = new ArrayList<>();
      catalog.sessions(s.code()).forEach((day, list) -> {
        for (int[] x : list) sessions.add(Map.of("day", day, "open", OpenViews.hhmm(x[0]), "close", OpenViews.hhmm(x[1])));
      });
      m.put("sessions", sessions);
      QuoteFrame f = stream.latest(s.code()).orElse(null);
      boolean open = catalog.isOpen(s.code(), now);
      m.put("status", !open ? "CLOSED" : OpenViews.status(f, f != null && ent.resaleAllowed(k, f.feed(), s.code())));
      items.add(m);
    }
    return Map.of("items", items);
  }

  @GetMapping("/v1/quotes")
  ResponseEntity<Object> quotes(HttpServletRequest req, @RequestParam(required = false) String symbols) {
    ApiKeys.Key k = key(req);
    if (symbols == null || symbols.isBlank()) return OpenErrors.bad(req, "SYMBOLS_REQUIRED", "symbols 必填，逗号分隔，最多 " + MAX_QUOTES + " 个");
    Set<String> want = new LinkedHashSet<>();
    for (String s : symbols.split(",")) if (!s.isBlank()) want.add(s.strip().toUpperCase(java.util.Locale.ROOT));
    if (want.size() > MAX_QUOTES) return OpenErrors.bad(req, "TOO_MANY_SYMBOLS", "一次最多查 " + MAX_QUOTES + " 个品种");
    Instant now = clock.instant();
    List<Map<String, Object>> items = new ArrayList<>();
    List<String> unavailable = new ArrayList<>();
    for (String s : want) {
      if (!ent.visible(k, s)) {
        unavailable.add(s);
        continue;
      }
      QuoteFrame f = stream.latest(s).orElse(null);
      items.add(OpenViews.quote(s, f, f != null && ent.resaleAllowed(k, f.feed(), s), !catalog.isOpen(s, now)));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("items", items);
    out.put("unavailable", unavailable);
    return ResponseEntity.ok(out);
  }

  @GetMapping("/v1/klines")
  ResponseEntity<Object> klines(HttpServletRequest req, @RequestParam(required = false) String symbol, @RequestParam(required = false) String tf,
      @RequestParam(required = false) Long from, @RequestParam(required = false) Long to, @RequestParam(defaultValue = "300") int limit) {
    ApiKeys.Key k = key(req);
    Optional<Timeframe> t = tf == null ? Optional.empty() : Timeframe.of(tf);
    if (t.isEmpty()) return OpenErrors.bad(req, "TIMEFRAME_INVALID", "tf 只能是 1m、5m、15m、1h、4h、1d");
    if (symbol == null || !ent.visible(k, symbol)) return OpenErrors.bad(req, "SYMBOL_NOT_AVAILABLE", "这个 Key 拿不到这个品种");
    int n = Math.max(1, Math.min(limit, MAX_BARS));
    int digits = catalog.get(symbol).map(QuoteSymbol::digits).orElse(5);
    List<Bar> all = klines.history(symbol, t.get(), MAX_BARS);
    List<Map<String, Object>> items = new ArrayList<>();
    for (Bar b : all) {
      if (from != null && b.time() < from) continue;
      if (to != null && b.time() > to) continue;
      items.add(OpenViews.bar(b, digits));
    }
    if (items.size() > n) items = items.subList(items.size() - n, items.size());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("symbol", symbol);
    out.put("tf", t.get().code);
    out.put("items", items);
    return ResponseEntity.ok(out);
  }

  @GetMapping("/v1/usage")
  Map<String, Object> usage(HttpServletRequest req) {
    ApiKeys.Key k = key(req);
    LocalDate today = usage.day(clock.instant());
    List<Usage.Day> days = usage.days(k.keyId(), today.minusDays(29), today);
    Map<String, Object> out = new LinkedHashMap<>();
    Map<String, Object> limits = new LinkedHashMap<>();
    limits.put("restPerSecond", k.limits().restPerSecond());
    limits.put("restPerDay", k.limits().restPerDay());
    limits.put("wsConnections", k.limits().wsConnections());
    limits.put("wsSymbolsPerConnection", k.limits().wsSymbols());
    out.put("limits", limits);
    Usage.Day t = days.stream().filter(d -> d.day().equals(today)).findFirst().orElse(new Usage.Day(today, k.keyId(), 0, 0, 0, 0, 0));
    Map<String, Object> todayView = day(t);
    todayView.put("wsConnectionsNow", usage.connections(k.keyId()));
    out.put("today", todayView);
    out.put("days", days.stream().map(OpenRestController::day).toList());
    return out;
  }

  private static Map<String, Object> day(Usage.Day d) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("date", d.day().toString());
    m.put("restRequests", d.restCalls());
    m.put("restRejected", d.restRejected());
    m.put("wsPushes", d.wsPushes());
    m.put("wsConnectionSeconds", d.wsSeconds());
    m.put("wsPeakConnections", d.wsPeak());
    return m;
  }

  @PostMapping("/v1/ws-ticket")
  Map<String, Object> ticket(HttpServletRequest req) {
    String t = tickets.issue(key(req).keyId());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("ticket", t);
    out.put("expiresAt", tickets.expiresAt(t).toEpochMilli());
    out.put("path", "/v1/ws?ticket=" + t);
    return out;
  }
}
