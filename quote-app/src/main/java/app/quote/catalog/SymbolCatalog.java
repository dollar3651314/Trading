package app.quote.catalog;

import app.quote.catalog.CatalogRepository.HolidayRow;
import app.quote.catalog.CatalogRepository.SessionRow;
import app.quote.pipeline.SimulatedQuoteSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 品种、交易时段、休市日的内存快照（从拆分前 trading-app 的 {@code market.config.SymbolCatalog} 搬来，规则不变）。
 * 启动时加载；trading-app 同步过来以后调用 {@link #reload()}。
 * 交易时段按 UTC 判断：每周时段 [open, close) 分钟；休市日全天不交易。没有配置任何时段的品种视为休市。
 */
@Component
public class SymbolCatalog implements SimulatedQuoteSource.SimStartPrices {

  private final CatalogRepository repo;
  private final Clock clock;
  private volatile Map<String, QuoteSymbol> symbols = Map.of();
  private volatile Map<String, List<int[]>[]> sessions = Map.of();
  private volatile Set<String> holidays = Set.of();

  SymbolCatalog(CatalogRepository repo, Clock clock) {
    this.repo = repo;
    this.clock = clock;
    reload();
  }

  public synchronized void reload() {
    Map<String, QuoteSymbol> s = new LinkedHashMap<>();
    for (QuoteSymbol q : repo.symbols()) s.put(q.code(), q);
    Map<String, List<int[]>[]> sess = new HashMap<>();
    for (SessionRow r : repo.sessions()) {
      @SuppressWarnings("unchecked")
      List<int[]>[] week = sess.computeIfAbsent(r.symbol(), k -> new List[8]);
      if (week[r.dayOfWeek()] == null) week[r.dayOfWeek()] = new ArrayList<>();
      week[r.dayOfWeek()].add(new int[] {r.openMinute(), r.closeMinute()});
    }
    Set<String> h = new HashSet<>();
    for (HolidayRow r : repo.holidays(LocalDate.now(clock).minusDays(1))) h.add(r.symbol() + "|" + r.tradeDate());
    symbols = s;
    sessions = sess;
    holidays = h;
  }

  /** 填了模拟起始价的品种（模拟源启动和同步后从这里读） */
  @Override
  public List<SimulatedQuoteSource.SimStartPrices.Entry> simStartPrices() {
    return symbols.values().stream().filter(s -> s.simStartPrice() != null)
        .map(s -> new SimulatedQuoteSource.SimStartPrices.Entry(s.code(), s.simStartPrice(), s.digits())).toList();
  }

  public List<QuoteSymbol> all() {
    return List.copyOf(symbols.values());
  }

  public Optional<QuoteSymbol> get(String code) {
    return Optional.ofNullable(symbols.get(code));
  }

  /**
   * 这个品种的每周交易时段（UTC，第 3 批对外品种列表用）：周几（1=周一 … 7=周日）→ [开始分钟, 结束分钟) 的列表；没有配置为空。
   */
  public Map<Integer, List<int[]>> sessions(String code) {
    List<int[]>[] week = sessions.get(code);
    Map<Integer, List<int[]>> out = new LinkedHashMap<>();
    if (week == null) return out;
    for (int d = 1; d <= 7; d++) if (week[d] != null) out.put(d, List.copyOf(week[d]));
    return out;
  }

  public boolean isOpen(String code, Instant at) {
    ZonedDateTime t = at.atZone(ZoneOffset.UTC);
    LocalDate d = t.toLocalDate();
    if (holidays.contains("*|" + d) || holidays.contains(code + "|" + d)) return false;
    List<int[]>[] week = sessions.get(code);
    if (week == null) return false;
    List<int[]> today = week[t.getDayOfWeek().getValue()];
    if (today == null) return false;
    int minute = t.getHour() * 60 + t.getMinute();
    for (int[] s : today) if (minute >= s[0] && minute < s[1]) return true;
    return false;
  }
}
