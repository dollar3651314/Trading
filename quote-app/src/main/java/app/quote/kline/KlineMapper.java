package app.quote.kline;

import app.quote.protocol.Timeframe;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * K 线的 SQL（表 market_kline，第 2 批第二步从 trading-app 搬到报价服务的库，表结构和名字不变；拆分前是 MyBatis 的 KlineMapper.xml，语句照搬）。
 * 都走主键 (symbol, timeframe, open_time)。
 */
@Repository
public class KlineMapper {

  private final JdbcTemplate jdbc;

  KlineMapper(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public int upsert(Bar bar) {
    return jdbc.update("INSERT INTO market_kline (symbol, timeframe, open_time, open, high, low, close, ticks) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
        + "ON DUPLICATE KEY UPDATE high = VALUES(high), low = VALUES(low), close = VALUES(close), ticks = VALUES(ticks)", bar.symbol(), bar.tf().code,
        bar.time(), bar.open(), bar.high(), bar.low(), bar.close(), bar.ticks());
  }

  /** 最近 limit 根已收盘的 K 线，按时间从新到旧 */
  public List<Bar> recent(String symbol, String tf, int limit) {
    return jdbc.query("SELECT symbol, timeframe, open_time, open, high, low, close, ticks FROM market_kline WHERE symbol = ? AND timeframe = ? "
        + "ORDER BY open_time DESC LIMIT ?", (rs, i) -> new Bar(rs.getString(1), Timeframe.of(rs.getString(2)).orElseThrow(), rs.getLong(3), rs.getBigDecimal(4),
            rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getInt(8)), symbol, tf, limit);
  }

  /** 表里有 K 线的品种（包括已经停用的） */
  public List<String> symbols() {
    return jdbc.queryForList("SELECT DISTINCT symbol FROM market_kline", String.class);
  }

  /** 从新到旧第 keep + 1 根的开始时间：比它早（含它）的都要删；不够 keep + 1 根时为 null */
  public Long cutoff(String symbol, String tf, int keep) {
    List<Long> r = jdbc.queryForList("SELECT open_time FROM market_kline WHERE symbol = ? AND timeframe = ? ORDER BY open_time DESC LIMIT 1 OFFSET ?", Long.class,
        symbol, tf, keep);
    return r.isEmpty() ? null : r.getFirst();
  }

  /** 删掉开始时间 ≤ cutoff 的，一次最多 limit 行（分批删，每批一个短事务，不长时间锁表） */
  public int deleteUpTo(String symbol, String tf, long cutoff, int limit) {
    return jdbc.update("DELETE FROM market_kline WHERE symbol = ? AND timeframe = ? AND open_time <= ? ORDER BY open_time LIMIT ?", symbol, tf, cutoff, limit);
  }

  public long count(String symbol, String tf) {
    Long n = jdbc.queryForObject("SELECT COUNT(*) FROM market_kline WHERE symbol = ? AND timeframe = ?", Long.class, symbol, tf);
    return n == null ? 0 : n;
  }
}
