package app.trading.market.config;

import app.trading.market.api.SymbolSpec;
import app.trading.market.config.SymbolMapper.HolidayRow;
import app.trading.market.config.SymbolMapper.SessionRow;
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
 * 品种、交易时段、休市日的内存快照。启动时加载；后台修改后调用 {@link #reload()}（S16）。
 * 交易时段按 UTC 判断：每周时段 [open, close) 分钟；休市日全天不交易。没有配置任何时段的品种视为休市（不默认开放，和现有系统的"缺配置就放行"相反）。
 */
@Component
public class SymbolCatalog {

  private final SymbolMapper mapper;
  private final Clock clock;
  private volatile Map<String, SymbolSpec> symbols = Map.of();
  private volatile Map<String, List<int[]>[]> sessions = Map.of();
  private volatile Set<String> holidays = Set.of();

  SymbolCatalog(SymbolMapper mapper, Clock clock) {
    this.mapper = mapper;
    this.clock = clock;
    reload();
  }

  private final List<Runnable> reloadListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

  /** 每次 {@link #reload()} 之后回调（连着报价服务时用来把品种和时段同步过去） */
  public void onReload(Runnable r) {
    reloadListeners.add(r);
  }

  /**
   * 同步给报价服务的整份数据（PUT /internal/catalog）：启用的品种（代码、类别、小数位、模拟起始价）、交易时段、昨天起的休市日。
   * 第 2 批过渡做法：这些仍在本进程后台改，报价服务保存一份自己用（第 3 批报价后台做好后改成在报价服务里改）。
   */
  public synchronized Map<String, Object> syncPayload() {
    Map<String, java.math.BigDecimal> sim = new HashMap<>();
    for (SymbolMapper.SimPriceRow r : mapper.simStartPrices()) sim.put(r.code(), r.simStartPrice());
    List<Map<String, Object>> syms = new ArrayList<>();
    for (SymbolSpec s : symbols.values()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("code", s.code());
      m.put("category", s.category());
      m.put("digits", s.digits());
      m.put("simStartPrice", sim.get(s.code()));
      // 第 3 批：对外品种列表要中英文名
      m.put("nameZh", s.nameZh());
      m.put("nameEn", s.nameEn());
      syms.add(m);
    }
    List<Map<String, Object>> sess = new ArrayList<>();
    for (SessionRow r : mapper.sessions()) {
      if (symbols.containsKey(r.symbol())) sess.add(Map.of("symbol", r.symbol(), "dayOfWeek", r.dayOfWeek(), "openMinute", r.openMinute(), "closeMinute", r.closeMinute()));
    }
    List<Map<String, Object>> hol = new ArrayList<>();
    for (HolidayRow r : mapper.holidays(LocalDate.now(clock).minusDays(1))) hol.add(Map.of("symbol", r.symbol(), "date", r.tradeDate().toString()));
    return Map.of("symbols", syms, "sessions", sess, "holidays", hol);
  }

  public synchronized void reload() {
    Map<String, SymbolSpec> s = new LinkedHashMap<>();
    for (SymbolSpec spec : mapper.enabledSymbols()) s.put(spec.code(), spec);
    Map<String, List<int[]>[]> sess = new HashMap<>();
    for (SessionRow r : mapper.sessions()) {
      @SuppressWarnings("unchecked")
      List<int[]>[] week = sess.computeIfAbsent(r.symbol(), k -> new List[8]);
      if (week[r.dayOfWeek()] == null) week[r.dayOfWeek()] = new ArrayList<>();
      week[r.dayOfWeek()].add(new int[] {r.openMinute(), r.closeMinute()});
    }
    Set<String> h = new HashSet<>();
    // 按注入的时钟取"昨天起"的休市日（以前用数据库的当前日期，和代码里其他地方用的时钟不一致：测试时钟固定时，真实日期一过就读不到测试用的休市日）
    for (HolidayRow r : mapper.holidays(LocalDate.now(clock).minusDays(1))) h.add(r.symbol() + "|" + r.tradeDate());
    symbols = s;
    sessions = sess;
    holidays = h;
    for (Runnable r : reloadListeners) r.run();
  }

  public List<SymbolSpec> all() {
    return List.copyOf(symbols.values());
  }

  public Optional<SymbolSpec> get(String code) {
    return Optional.ofNullable(symbols.get(code));
  }

  /** UTC 日期 d 是不是这个品种的交易日：这一天（星期几）有交易时段，并且不是休市日。隔夜费按交易日结算 */
  public boolean isTradingDay(String code, LocalDate d) {
    if (holidays.contains("*|" + d) || holidays.contains(code + "|" + d)) return false;
    List<int[]>[] week = sessions.get(code);
    return week != null && week[d.getDayOfWeek().getValue()] != null && !week[d.getDayOfWeek().getValue()].isEmpty();
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
