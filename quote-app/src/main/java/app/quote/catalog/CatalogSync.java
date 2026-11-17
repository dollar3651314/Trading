package app.quote.catalog;

import app.quote.catalog.CatalogRepository.HolidayRow;
import app.quote.catalog.CatalogRepository.SessionRow;
import app.quote.pipeline.SimulatedQuoteSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * trading-app 同步品种、交易时段、休市日、模拟起始价（PUT /internal/catalog，要内部 Key）。
 * 第 2 批过渡做法：这些参数仍在 trading-app 后台改，改完（和 trading-app 启动时）整份同步过来；第 3 批报价后台做好后改成在报价服务里改。
 * 同步后立即重新加载；模拟源下新出现起始价的品种立即开始报价（{@link SimulatedQuoteSource#seedFromDatabase}）。
 */
@RestController
class CatalogSync {

  record Symbol(String code, String category, Integer digits, BigDecimal simStartPrice) {}

  record Session(String symbol, Integer dayOfWeek, Integer openMinute, Integer closeMinute) {}

  record Holiday(String symbol, LocalDate date) {}

  record Body(List<Symbol> symbols, List<Session> sessions, List<Holiday> holidays) {}

  private static final Pattern CODE = Pattern.compile("[A-Z0-9]{1,16}");

  private final CatalogRepository repo;
  private final SymbolCatalog catalog;
  private final ObjectProvider<SimulatedQuoteSource> simulated;

  CatalogSync(CatalogRepository repo, SymbolCatalog catalog, ObjectProvider<SimulatedQuoteSource> simulated) {
    this.repo = repo;
    this.catalog = catalog;
    this.simulated = simulated;
  }

  @PutMapping("/internal/catalog")
  ResponseEntity<Map<String, Object>> sync(@RequestBody Body b) {
    if (b == null || b.symbols() == null || b.symbols().isEmpty()) return bad("symbols 不能为空");
    List<QuoteSymbol> symbols = new ArrayList<>();
    for (Symbol s : b.symbols()) {
      if (s == null || s.code() == null || !CODE.matcher(s.code()).matches() || s.category() == null || s.category().isBlank() || s.digits() == null
          || s.digits() < 0 || s.digits() > 10 || (s.simStartPrice() != null && s.simStartPrice().signum() <= 0)) {
        return bad("品种格式不对：" + (s == null ? null : s.code()));
      }
      symbols.add(new QuoteSymbol(s.code(), s.category(), s.digits(), s.simStartPrice()));
    }
    List<SessionRow> sessions = new ArrayList<>();
    for (Session s : b.sessions() == null ? List.<Session>of() : b.sessions()) {
      if (s == null || s.symbol() == null || s.dayOfWeek() == null || s.dayOfWeek() < 1 || s.dayOfWeek() > 7 || s.openMinute() == null || s.closeMinute() == null
          || s.openMinute() < 0 || s.closeMinute() > 1440 || s.openMinute() >= s.closeMinute()) {
        return bad("交易时段格式不对");
      }
      sessions.add(new SessionRow(s.symbol(), s.dayOfWeek(), s.openMinute(), s.closeMinute()));
    }
    List<HolidayRow> holidays = new ArrayList<>();
    for (Holiday h : b.holidays() == null ? List.<Holiday>of() : b.holidays()) {
      if (h == null || h.symbol() == null || h.date() == null) return bad("休市日格式不对");
      holidays.add(new HolidayRow(h.symbol(), h.date()));
    }
    repo.replace(symbols, sessions, holidays);
    catalog.reload();
    SimulatedQuoteSource sim = simulated.getIfAvailable();
    List<String> seeded = sim == null ? List.of() : sim.seedFromDatabase();
    return ResponseEntity.ok(Map.of("symbols", symbols.size(), "seeded", seeded));
  }

  private static ResponseEntity<Map<String, Object>> bad(String message) {
    return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION_FAILED", "message", message));
  }
}
