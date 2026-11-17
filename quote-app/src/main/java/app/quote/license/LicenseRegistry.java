package app.quote.license;

import app.quote.QuoteProperties;
import app.quote.feed.QuoteRouter;
import app.quote.feed.TradingLicenses;
import app.quote.license.QuoteLicense.Grant;
import app.quote.license.QuoteLicense.Use;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 报价源授权登记（技术方案 08 页，TQ-11 A）：表 quote_source_license 的内存快照，改完立即重新加载，另外每分钟重新加载一次（到期日按天生效）。
 * <ul>
 *   <li>{@link #tradingUse}：生产（启用检查时）只有"交易用途"已授权的源的报价能用来<b>开仓</b>；报价照常推（只显示），
 *       {@code QuoteRouter} 在两家都有新价时优先用有授权的那家；</li>
 *   <li>{@link #allowed}：三项里任意一项（登录后展示、转售给第 3 批用）。</li>
 * </ul>
 * 查找顺序：具体品种那一行 → 这家的 {@code *} 行 → 没有登记（= 没有授权）。日期按注入时钟的 UTC 日期。
 */
@Component

public class LicenseRegistry implements TradingLicenses {

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final boolean enforced;
  private volatile Map<String, QuoteLicense> byKey = Map.of();

  LicenseRegistry(JdbcTemplate jdbc, Clock clock, LicenseProperties props, QuoteProperties quote) {
    this.jdbc = jdbc;
    this.clock = clock;
    boolean live = QuoteRouter.mode(quote.source()) == QuoteRouter.Mode.LIVE;
    // 生产（报价模式 live）一律检查：FX_QUOTE_LICENSE_ENFORCE=false 只在非生产有效（第 3 批审查：不能用一个变量关掉生产的转售、交易用途检查）
    if (live && "false".equals(props.enforce())) {
      org.slf4j.LoggerFactory.getLogger(LicenseRegistry.class).warn("报价模式是 live（生产），忽略 FX_QUOTE_LICENSE_ENFORCE=false，授权检查照常启用");
    }
    this.enforced = live || "true".equals(props.enforce());
    reload();
  }

  @Override
  public boolean enforced() {
    return enforced;
  }

  @Override
  public boolean tradingUse(String feed, String symbol) {
    return !enforced || allowed(feed, symbol, Use.TRADING);
  }

  /** 这家报价商这个品种今天有没有这一项授权（不管有没有启用检查） */
  public boolean allowed(String feed, String symbol, Use use) {
    if (feed == null || symbol == null) return false;
    QuoteLicense l = byKey.get(feed + "|" + symbol);
    if (l == null) l = byKey.get(feed + "|" + QuoteLicense.ALL);
    return l != null && l.grant(use).validOn(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
  }

  public List<QuoteLicense> all() {
    return byKey.values().stream().sorted((a, b) -> (a.feed() + a.symbol()).compareTo(b.feed() + b.symbol())).toList();
  }

  /** 新增或整行替换 */
  public void save(QuoteLicense l) {
    jdbc.update("INSERT INTO quote_source_license (feed, symbol, trading_use, trading_from, trading_until, display_use, display_from, display_until, resale, "
        + "resale_from, resale_until, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE trading_use = VALUES(trading_use), "
        + "trading_from = VALUES(trading_from), trading_until = VALUES(trading_until), display_use = VALUES(display_use), display_from = VALUES(display_from), "
        + "display_until = VALUES(display_until), resale = VALUES(resale), resale_from = VALUES(resale_from), resale_until = VALUES(resale_until), note = VALUES(note)",
        l.feed(), l.symbol(), l.trading().granted(), l.trading().from(), l.trading().until(), l.display().granted(), l.display().from(), l.display().until(),
        l.resale().granted(), l.resale().from(), l.resale().until(), l.note());
    reload();
  }

  /** 删掉一行（= 没有授权）；返回是否删了 */
  public boolean delete(String feed, String symbol) {
    int n = jdbc.update("DELETE FROM quote_source_license WHERE feed = ? AND symbol = ?", feed, symbol);
    reload();
    return n > 0;
  }

  @Scheduled(fixedDelayString = "${quote.license.reload-interval:60s}")
  public void reload() {
    Map<String, QuoteLicense> m = new HashMap<>();
    for (QuoteLicense l : jdbc.query("SELECT feed, symbol, trading_use, trading_from, trading_until, display_use, display_from, display_until, resale, resale_from, "
        + "resale_until, note FROM quote_source_license", (rs, i) -> row(rs))) {
      m.put(l.feed() + "|" + l.symbol(), l);
    }
    byKey = Map.copyOf(m);
  }

  private static QuoteLicense row(ResultSet rs) throws SQLException {
    return new QuoteLicense(rs.getString("feed"), rs.getString("symbol"), grant(rs, "trading_use", "trading_from", "trading_until"),
        grant(rs, "display_use", "display_from", "display_until"), grant(rs, "resale", "resale_from", "resale_until"), rs.getString("note"));
  }

  private static Grant grant(ResultSet rs, String flag, String from, String until) throws SQLException {
    return new Grant(rs.getBoolean(flag), rs.getObject(from, LocalDate.class), rs.getObject(until, LocalDate.class));
  }
}
