package app.quote.open;

import app.quote.QuoteProperties;
import app.quote.catalog.QuoteSymbol;
import app.quote.catalog.SymbolCatalog;
import app.quote.feed.QuoteFeedsProperties;
import app.quote.feed.QuoteRouter;
import app.quote.feed.QuoteSource;
import app.quote.health.AlertCheck;
import app.quote.license.LicenseRegistry;
import app.quote.license.QuoteLicense;
import app.quote.pipeline.QuoteProcessor;
import app.quote.protocol.QuoteFrame;
import app.quote.stream.QuoteStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台"报价服务"菜单用的内部接口（要内部 Key，经 trading-app 后台调；审计、原因必填、二次确认、只有超管能开总开关在 trading-app 做）：
 * 外部客户和 Key（P8）、限额和用量（P9）、授权登记状态和总开关（P10）、报价源状态（P11）。
 * <p>打开总开关这里也要求 {@code amosApproved=true} 和原因（双保险）。Secret 只在建 Key、轮换的返回里出现一次。
 */
@RestController
class OpenAdminController {

  record ClientBody(String name, String email, String note) {}

  record KeyBody(Long clientId, String kind, List<String> symbols) {}

  record LimitsBody(Integer restPerSecond, Integer restPerDay, Integer wsConnections, Integer wsSymbols, String by, String reason) {}

  record GateBody(Boolean open, Boolean amosApproved, String by, String reason) {}

  private final ApiKeys keys;
  private final OpenSettings settings;
  private final Usage usage;
  private final OpenStream stream;
  private final SecretBox box;
  private final LicenseRegistry licenses;
  private final SymbolCatalog catalog;
  private final QuoteStream quotes;
  private final QuoteProcessor processor;
  private final ObjectProvider<QuoteRouter> router;
  private final QuoteFeedsProperties feeds;
  private final ObjectProvider<List<AlertCheck>> alerts;
  private final SourceStats stats;
  private final QuoteProperties quote;
  private final Clock clock;

  OpenAdminController(ApiKeys keys, OpenSettings settings, Usage usage, OpenStream stream, SecretBox box, LicenseRegistry licenses, SymbolCatalog catalog,
      QuoteStream quotes, QuoteProcessor processor, ObjectProvider<QuoteRouter> router, QuoteFeedsProperties feeds, ObjectProvider<List<AlertCheck>> alerts,
      SourceStats stats, QuoteProperties quote, Clock clock) {
    this.keys = keys;
    this.settings = settings;
    this.usage = usage;
    this.stream = stream;
    this.box = box;
    this.licenses = licenses;
    this.catalog = catalog;
    this.quotes = quotes;
    this.processor = processor;
    this.router = router;
    this.feeds = feeds;
    this.alerts = alerts;
    this.stats = stats;
    this.quote = quote;
    this.clock = clock;
  }

  // ---- P8 外部客户和 Key ----

  @GetMapping("/internal/open/overview")
  Map<String, Object> overview() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("gate", gateView());
    out.put("defaults", settings.defaults());
    out.put("keyEncReady", box.ready());
    List<ApiKeys.Client> clients = keys.clients();
    List<ApiKeys.Key> all = keys.keys();
    Map<Long, ApiKeys.Client> byId = new HashMap<>();
    for (ApiKeys.Client c : clients) byId.put(c.id(), c);
    out.put("clients", clients.stream().map(c -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", c.id());
      m.put("code", c.code());
      m.put("name", c.name());
      m.put("email", c.email());
      m.put("active", c.active());
      m.put("note", c.note());
      m.put("createdAt", c.createdAt());
      m.put("keyCount", all.stream().filter(k -> k.clientId() == c.id()).count());
      return m;
    }).toList());
    out.put("keys", all.stream().map(k -> keyView(k, byId.get(k.clientId()))).toList());
    return out;
  }

  private Map<String, Object> keyView(ApiKeys.Key k, ApiKeys.Client c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("keyId", k.keyId());
    m.put("clientId", k.clientId());
    m.put("clientName", c == null ? null : c.name());
    m.put("kind", k.kind().name());
    m.put("active", k.active());
    m.put("usableNow", k.active() && (!k.external() || settings.gateOpen()));
    m.put("symbols", k.symbols().stream().sorted().toList());
    m.put("limits", k.limits());
    m.put("createdAt", k.createdAt());
    m.put("disabledAt", k.disabledAt());
    m.put("lastUsedAt", k.lastUsedAt());
    m.put("rotatedFrom", k.rotatedFrom());
    m.put("usedToday", usage.usedToday(k.keyId()));
    m.put("rejectedToday", usage.rejectedToday(k.keyId()));
    m.put("connections", usage.connections(k.keyId()));
    return m;
  }

  @PostMapping("/internal/open/clients")
  ResponseEntity<Object> createClient(@RequestBody ClientBody b) {
    try {
      return ResponseEntity.ok(keys.createClient(b == null ? null : b.name(), b == null ? null : b.email(), b == null ? null : b.note()));
    } catch (IllegalArgumentException e) {
      return bad(e.getMessage());
    }
  }

  @PostMapping("/internal/open/keys")
  ResponseEntity<Object> createKey(@RequestBody KeyBody b) {
    if (b == null || b.clientId() == null) return bad("clientId 必填");
    ApiKeys.Kind kind;
    try {
      kind = ApiKeys.Kind.valueOf(String.valueOf(b.kind()));
    } catch (IllegalArgumentException e) {
      return bad("kind 只能是 INTERNAL_TEST 或 EXTERNAL");
    }
    try {
      return ResponseEntity.ok(created(keys.createKey(b.clientId(), kind, b.symbols() == null ? Set.of() : new LinkedHashSet<>(b.symbols()))));
    } catch (IllegalArgumentException e) {
      return bad(e.getMessage());
    } catch (IllegalStateException e) {
      return ResponseEntity.status(503).body(Map.of("code", "KEY_ENC_NOT_CONFIGURED", "message", e.getMessage()));
    }
  }

  /** 建 Key、轮换的返回：Secret 只在这里出现这一次 */
  private Map<String, Object> created(ApiKeys.Created c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("key", keyView(c.key(), keys.client(c.key().clientId()).orElse(null)));
    m.put("secret", c.secret());
    return m;
  }

  @PostMapping("/internal/open/keys/{keyId}/rotate")
  ResponseEntity<Object> rotate(@PathVariable String keyId) {
    try {
      ApiKeys.Created c = keys.rotate(keyId);
      stream.dropKey(keyId);
      return ResponseEntity.ok(created(c));
    } catch (IllegalArgumentException e) {
      return bad(e.getMessage());
    } catch (IllegalStateException e) {
      return ResponseEntity.status(503).body(Map.of("code", "KEY_ENC_NOT_CONFIGURED", "message", e.getMessage()));
    }
  }

  @PostMapping("/internal/open/keys/{keyId}/disable")
  ResponseEntity<Object> disable(@PathVariable String keyId) {
    if (keys.find(keyId).isEmpty()) return ResponseEntity.status(404).body(Map.of("code", "NOT_FOUND", "message", "没有这个 Key"));
    boolean changed = keys.disable(keyId);
    stream.dropKey(keyId);
    return ResponseEntity.ok(Map.of("disabled", true, "changed", changed));
  }

  @PutMapping("/internal/open/keys/{keyId}/limits")
  ResponseEntity<Object> limits(@PathVariable String keyId, @RequestBody LimitsBody b) {
    try {
      Limits before = keys.find(keyId).map(ApiKeys.Key::limits).orElse(null);
      ApiKeys.Key k = keys.setLimits(keyId, limits(b));
      return ResponseEntity.ok(Map.of("before", before, "after", k.limits()));
    } catch (IllegalArgumentException e) {
      return bad(e.getMessage());
    }
  }

  @PutMapping("/internal/open/defaults")
  ResponseEntity<Object> defaults(@RequestBody LimitsBody b) {
    try {
      Limits before = settings.defaults();
      settings.setDefaults(limits(b), b.by(), b.reason());
      return ResponseEntity.ok(Map.of("before", before, "after", settings.defaults()));
    } catch (IllegalArgumentException e) {
      return bad(e.getMessage());
    }
  }

  private static Limits limits(LimitsBody b) {
    if (b == null || b.restPerSecond() == null || b.restPerDay() == null || b.wsConnections() == null || b.wsSymbols() == null) {
      throw new IllegalArgumentException("四项限额都要填正整数");
    }
    return new Limits(b.restPerSecond(), b.restPerDay(), b.wsConnections(), b.wsSymbols()).validate();
  }

  // ---- P10 总开关、授权登记 ----

  @PutMapping("/internal/open/gate")
  ResponseEntity<Object> gate(@RequestBody GateBody b) {
    if (b == null || b.open() == null) return bad("open 必填");
    if (b.reason() == null || b.reason().isBlank()) return bad("原因必填");
    if (b.open() && !Boolean.TRUE.equals(b.amosApproved())) return bad("打开对外总开关要 Amos 同意（L3），没勾\"Amos 已同意\"不能打开");
    boolean before = settings.gateOpen();
    settings.setGate(b.open(), b.by(), b.reason().strip());
    if (!b.open()) stream.dropExternal();
    return ResponseEntity.ok(Map.of("before", before, "after", settings.gateOpen()));
  }

  private Map<String, Object> gateView() {
    OpenSettings.Gate g = settings.gate();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("open", g.open());
    m.put("updatedBy", g.updatedBy());
    m.put("reason", g.reason());
    m.put("updatedAt", g.updatedAt());
    return m;
  }

  /** 授权登记（三项各自的状态：ok / soon（30 天内到期）/ expired / none）、总开关、生产里只显示不开仓的品种 */
  @GetMapping("/internal/open/licenses")
  Map<String, Object> licenses() {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    List<Map<String, Object>> items = new ArrayList<>();
    for (QuoteLicense l : licenses.all()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("feed", l.feed());
      m.put("symbol", l.symbol());
      for (QuoteLicense.Use u : QuoteLicense.Use.values()) m.put(u.name().toLowerCase(java.util.Locale.ROOT), grantView(l.grant(u), today));
      m.put("note", l.note());
      items.add(m);
    }
    List<String> notTradable = new ArrayList<>();
    for (QuoteSymbol s : catalog.all()) {
      boolean any = false;
      for (String f : candidates(s.code())) any |= licenses.allowed(f, s.code(), QuoteLicense.Use.TRADING);
      if (!any) notTradable.add(s.code());
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("gate", gateView());
    out.put("enforced", licenses.enforced());
    out.put("today", today.toString());
    out.put("items", items);
    out.put("notTradable", notTradable);
    return out;
  }

  private static Map<String, Object> grantView(QuoteLicense.Grant g, LocalDate today) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("granted", g.granted());
    m.put("from", g.from());
    m.put("until", g.until());
    String state;
    Long left = g.until() == null ? null : ChronoUnit.DAYS.between(today, g.until());
    if (!g.granted()) state = "none";
    else if (left != null && left < 0) state = "expired";
    else if (g.from() != null && today.isBefore(g.from())) state = "notYet";
    else if (left != null && left <= OpenAlerts.EXPIRY_DAYS) state = "soon";
    else state = "ok";
    m.put("state", state);
    m.put("daysLeft", left);
    return m;
  }

  /** 这个品种配置里的报价源（前两个能用的；没有真实源时是映射里写的全部） */
  private List<String> candidates(String symbol) {
    List<QuoteFeedsProperties.Route> rs = feeds.parsedRoutes().getOrDefault(symbol, List.of());
    List<String> usable = rs.stream().filter(feeds::usable).map(QuoteFeedsProperties.Route::feed).limit(2).toList();
    return usable.isEmpty() ? rs.stream().map(QuoteFeedsProperties.Route::feed).toList() : usable;
  }

  // ---- P9 用量 ----

  @GetMapping("/internal/open/usage")
  Map<String, Object> usage(@RequestParam(required = false) String from, @RequestParam(required = false) String to, @RequestParam(required = false) String keyId,
      @RequestParam(required = false) Long clientId, @RequestParam(required = false) String symbol) {
    LocalDate today = usage.day(clock.instant());
    LocalDate f = from == null || from.isBlank() ? today : LocalDate.parse(from);
    LocalDate t = to == null || to.isBlank() ? today : LocalDate.parse(to);
    List<String> ids = keyFilter(keyId, clientId);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("from", f.toString());
    out.put("to", t.toString());
    out.put("rows", usage.rows(ids, symbol, f, t, 5000));
    List<Usage.Day> days = new ArrayList<>();
    if (ids == null) days.addAll(usage.days(null, f, t));
    else for (String id : ids) days.addAll(usage.days(id, f, t));
    out.put("days", days);
    return out;
  }

  /** 导出 CSV（UTF-8 带 BOM，Excel 直接打开不乱码） */
  @GetMapping("/internal/open/usage/export")
  ResponseEntity<String> export(@RequestParam(required = false) String from, @RequestParam(required = false) String to, @RequestParam(required = false) String keyId,
      @RequestParam(required = false) Long clientId, @RequestParam(required = false) String symbol) {
    LocalDate today = usage.day(clock.instant());
    LocalDate f = from == null || from.isBlank() ? today : LocalDate.parse(from);
    LocalDate t = to == null || to.isBlank() ? today : LocalDate.parse(to);
    StringBuilder sb = new StringBuilder("﻿日期,Key,接口,品种,REST 请求数,被拒（429）,WebSocket 推送条数,连接秒数,连接数峰值\n");
    for (Usage.Row r : usage.rows(keyFilter(keyId, clientId), symbol, f, t, 100_000)) {
      sb.append(r.day()).append(',').append(r.keyId()).append(',').append(r.endpoint().isEmpty() ? "WebSocket" : "REST /v1/" + r.endpoint()).append(',')
          .append(r.symbol()).append(',').append(r.restCalls()).append(',').append(r.restRejected()).append(',').append(r.wsPushes()).append(',')
          .append(r.wsSeconds()).append(',').append(r.wsPeak()).append('\n');
    }
    return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"quote-usage-" + f + "-" + t + ".csv\"")
        .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8)).body(sb.toString());
  }

  /** keyId / clientId 都没给 = 不筛（null）；给了 clientId 就是这个客户的全部 Key */
  private List<String> keyFilter(String keyId, Long clientId) {
    if (keyId != null && !keyId.isBlank()) return List.of(keyId);
    if (clientId != null) return keys.keys().stream().filter(k -> k.clientId() == clientId).map(ApiKeys.Key::keyId).toList();
    return null;
  }

  // ---- P11 报价源状态 ----

  @GetMapping("/internal/open/sources")
  Map<String, Object> sources() {
    QuoteRouter r = router.getIfAvailable();
    Instant now = clock.instant();
    Map<String, List<QuoteFeedsProperties.Route>> routes = feeds.parsedRoutes();
    List<Map<String, Object>> rows = new ArrayList<>();
    for (QuoteSymbol s : catalog.all()) {
      List<QuoteFeedsProperties.Route> rs = routes.getOrDefault(s.code(), List.of()).stream().filter(feeds::usable).limit(2).toList();
      QuoteSource cur = r == null ? null : r.current(s.code()).orElse(null);
      QuoteFrame last = quotes.latest(s.code()).orElse(null);
      SourceStats.View st = stats.of(s.code());
      boolean open = catalog.isOpen(s.code(), now);
      String state;
      if (!open) state = "closed";
      else if (last == null || !last.fresh()) state = "down";
      else if (cur != null && "deviation".equals(cur.state())) state = "deviation";
      else if (cur != null && "backup".equals(cur.state())) state = "backup";
      else state = "ok";
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("symbol", s.code());
      m.put("primary", rs.isEmpty() ? null : rs.get(0).feed());
      m.put("backup", rs.size() < 2 ? null : rs.get(1).feed());
      m.put("current", cur == null ? null : cur.feed());
      m.put("lastQuoteAt", processor.lastReceived(s.code()).orElse(null));
      m.put("switches24h", st.switches24h());
      m.put("deviations24h", st.deviations24h());
      m.put("lastSwitchAt", st.lastSwitchAt());
      m.put("state", state);
      rows.add(m);
    }
    List<Map<String, Object>> cards = new ArrayList<>();
    Map<String, AlertCheck.Item> byName = new HashMap<>();
    for (AlertCheck a : alerts.getIfAvailable(List::of)) for (AlertCheck.Item i : a.evaluate()) byName.put(i.name(), i);
    for (String f : List.of("alltick", "itick", "binance")) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("feed", f);
      m.put("configured", feeds.configured(f));
      AlertCheck.Item i = byName.get("quote." + f);
      m.put("ok", i == null || i.ok());
      m.put("detail", i == null ? null : i.detail());
      cards.add(m);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("mode", QuoteRouter.mode(quote.source()).name().toLowerCase(java.util.Locale.ROOT));
    out.put("feeds", cards);
    out.put("rows", rows);
    return out;
  }

  private static ResponseEntity<Object> bad(String message) {
    return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION_FAILED", "message", message));
  }
}
