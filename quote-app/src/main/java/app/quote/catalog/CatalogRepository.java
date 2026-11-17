package app.quote.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 品种、交易时段、休市日的表（quote_symbol、quote_session、quote_holiday）。 */
@Repository
public class CatalogRepository {

  public record SessionRow(String symbol, int dayOfWeek, int openMinute, int closeMinute) {}

  public record HolidayRow(String symbol, LocalDate tradeDate) {}

  private final JdbcTemplate jdbc;

  CatalogRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  List<QuoteSymbol> symbols() {
    return jdbc.query("SELECT code, category, digits, sim_start_price, name_zh, name_en FROM quote_symbol ORDER BY sort_order, code",
        (rs, i) -> new QuoteSymbol(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBigDecimal(4), rs.getString(5), rs.getString(6)));
  }

  List<SessionRow> sessions() {
    return jdbc.query("SELECT symbol, day_of_week, open_minute, close_minute FROM quote_session",
        (rs, i) -> new SessionRow(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)));
  }

  List<HolidayRow> holidays(LocalDate from) {
    return jdbc.query("SELECT symbol, trade_date FROM quote_holiday WHERE trade_date >= ?", (rs, i) -> new HolidayRow(rs.getString(1), rs.getObject(2, LocalDate.class)),
        from);
  }

  /** 整份替换（trading-app 后台改完同步过来）；一个事务 */
  @Transactional
  public void replace(List<QuoteSymbol> symbols, List<SessionRow> sessions, List<HolidayRow> holidays) {
    jdbc.update("DELETE FROM quote_session");
    jdbc.update("DELETE FROM quote_holiday");
    jdbc.update("DELETE FROM quote_symbol");
    int order = 0;
    for (QuoteSymbol s : symbols) {
      BigDecimal sim = s.simStartPrice();
      jdbc.update("INSERT INTO quote_symbol (code, category, name_zh, name_en, digits, sim_start_price, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?)", s.code(),
          s.category(), s.nameZh(), s.nameEn(), s.digits(), sim, ++order);
    }
    for (SessionRow r : sessions) {
      jdbc.update("INSERT INTO quote_session (symbol, day_of_week, open_minute, close_minute) VALUES (?, ?, ?, ?)", r.symbol(), r.dayOfWeek(), r.openMinute(),
          r.closeMinute());
    }
    for (HolidayRow h : holidays) jdbc.update("INSERT IGNORE INTO quote_holiday (symbol, trade_date) VALUES (?, ?)", h.symbol(), h.tradeDate());
  }
}
