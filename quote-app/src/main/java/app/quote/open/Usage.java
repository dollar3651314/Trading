package app.quote.open;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 限额和计量（需求 03 页第三节；只计量不收费，TD-6 A）。
 * <ul>
 *   <li>REST 每秒：按秒的固定窗口；每天：UTC 日累计（重启后从库里接上）。超了只拒超出的这一次（429 + Retry-After），被拒的单独记，不算进请求数；</li>
 *   <li>计量按 Key、按天、按接口、按品种：REST 请求数、WebSocket 推送条数、连接秒数、当天连接数峰值。先记内存，每分钟（和每次查询前）写进
 *       quote_usage_daily，所以用量页、用量接口和实际请求数一致（AC-Q4、AC-Q11）；进程崩溃最多丢最后一分钟的计数（推断够用）；</li>
 *   <li>保留 13 个月，每天清理一次。</li>
 * </ul>
 */
@Component
public class Usage {

  /** 一次 REST 请求的限额判断 */
  public sealed interface Decision permits Ok, Rejected {}

  public record Ok(long usedToday) implements Decision {}

  /**
   * @param scope second（每秒）/ day（每天）
   * @param retryAfter 多少秒后再试
   * @param resetAt 额度什么时候恢复
   */
  public record Rejected(String scope, int limit, long retryAfter, Instant resetAt) implements Decision {}

  /** 一天的汇总（用量接口、后台用量页） */
  public record Day(LocalDate day, String keyId, long restCalls, long restRejected, long wsPushes, long wsSeconds, int wsPeak) {}

  /** 一行明细（后台按品种看、导出 CSV） */
  public record Row(LocalDate day, String keyId, String endpoint, String symbol, long restCalls, long restRejected, long wsPushes, long wsSeconds, int wsPeak) {}

  private static final class Delta {
    final AtomicLong calls = new AtomicLong();
    final AtomicLong rejected = new AtomicLong();
    final AtomicLong pushes = new AtomicLong();
    final AtomicLong seconds = new AtomicLong();
    final AtomicInteger peak = new AtomicInteger();
  }

  private final JdbcTemplate jdbc;
  private final ApiKeys keys;
  private final OpenProperties props;
  private final Clock clock;
  /** day|key|endpoint|symbol → 还没写库的增量 */
  private final Map<String, Delta> pending = new ConcurrentHashMap<>();
  /** day|key → 当天已通过的 REST 请求数（限额用） */
  private final Map<String, AtomicLong> today = new ConcurrentHashMap<>();
  /** key → {秒, 这一秒的请求数} */
  private final Map<String, long[]> second = new ConcurrentHashMap<>();
  /** key → 现在的 WebSocket 连接数 */
  private final Map<String, AtomicInteger> open = new ConcurrentHashMap<>();
  /** day|key → 当天被拒（429）的次数（后台"今天已拒绝超出的 N 次请求"） */
  private final Map<String, AtomicLong> rejectedToday = new ConcurrentHashMap<>();
  private final Map<String, Instant> lastUsed = new ConcurrentHashMap<>();

  Usage(JdbcTemplate jdbc, ApiKeys keys, OpenProperties props, Clock clock) {
    this.jdbc = jdbc;
    this.keys = keys;
    this.props = props;
    this.clock = clock;
  }

  // ---- REST ----

  /** 判断一次 REST 请求能不能放行；放行就记一次（接口 endpoint，品种 symbol 可以为空） */
  public Decision rest(ApiKeys.Key k, String endpoint, String symbol) {
    Instant now = clock.instant();
    LocalDate day = day(now);
    long sec = now.getEpochSecond();
    Limits l = k.limits();
    long[] w = second.computeIfAbsent(k.keyId(), x -> new long[2]);
    AtomicLong used = today.computeIfAbsent(day + "|" + k.keyId(), x -> new AtomicLong(loadToday(k.keyId(), day)));
    synchronized (w) {
      if (w[0] != sec) {
        w[0] = sec;
        w[1] = 0;
      }
      if (w[1] >= l.restPerSecond()) return reject(k, day, endpoint, symbol, new Rejected("second", l.restPerSecond(), 1, Instant.ofEpochSecond(sec + 1)));
      if (used.get() >= l.restPerDay()) {
        Instant reset = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return reject(k, day, endpoint, symbol, new Rejected("day", l.restPerDay(), Math.max(1, reset.getEpochSecond() - sec), reset));
      }
      w[1]++;
      long n = used.incrementAndGet();
      delta(day, k.keyId(), endpoint, symbol).calls.incrementAndGet();
      lastUsed.put(k.keyId(), now);
      return new Ok(n);
    }
  }

  private Rejected reject(ApiKeys.Key k, LocalDate day, String endpoint, String symbol, Rejected r) {
    delta(day, k.keyId(), endpoint, symbol).rejected.incrementAndGet();
    rejectedToday.computeIfAbsent(day + "|" + k.keyId(), x -> new AtomicLong()).incrementAndGet();
    return r;
  }

  /** 今天已通过的 REST 请求数（80% 提醒、后台用量页） */
  public long usedToday(String keyId) {
    LocalDate day = day(clock.instant());
    return today.computeIfAbsent(day + "|" + keyId, x -> new AtomicLong(loadToday(keyId, day))).get();
  }

  public long rejectedToday(String keyId) {
    AtomicLong n = rejectedToday.get(day(clock.instant()) + "|" + keyId);
    if (n != null) return n.get();
    Long v = jdbc.queryForObject("SELECT COALESCE(SUM(rest_rejected), 0) FROM quote_usage_daily WHERE day = ? AND key_id = ?", Long.class, day(clock.instant()), keyId);
    return v == null ? 0 : v;
  }

  private long loadToday(String keyId, LocalDate day) {
    Long v = jdbc.queryForObject("SELECT COALESCE(SUM(rest_calls), 0) FROM quote_usage_daily WHERE day = ? AND key_id = ?", Long.class, day, keyId);
    return v == null ? 0 : v;
  }

  // ---- WebSocket ----

  /** 现在的连接数 */
  public int connections(String keyId) {
    AtomicInteger n = open.get(keyId);
    return n == null ? 0 : n.get();
  }

  /** 新连接：超过连接数限额返回 false（不记） */
  public boolean wsOpen(ApiKeys.Key k) {
    AtomicInteger n = open.computeIfAbsent(k.keyId(), x -> new AtomicInteger());
    while (true) {
      int cur = n.get();
      if (cur >= k.limits().wsConnections()) return false;
      if (n.compareAndSet(cur, cur + 1)) {
        Instant now = clock.instant();
        delta(day(now), k.keyId(), "", "").peak.accumulateAndGet(cur + 1, Math::max);
        lastUsed.put(k.keyId(), now);
        return true;
      }
    }
  }

  public void wsClose(String keyId) {
    AtomicInteger n = open.get(keyId);
    if (n != null) n.updateAndGet(v -> Math.max(0, v - 1));
  }

  /** 连接时长（秒），由 {@link OpenStream} 定时和断开时记 */
  public void wsSeconds(String keyId, long seconds) {
    if (seconds > 0) delta(day(clock.instant()), keyId, "", "").seconds.addAndGet(seconds);
  }

  /** 推了一条（报价或 K 线） */
  public void push(String keyId, String symbol) {
    delta(day(clock.instant()), keyId, "", symbol).pushes.incrementAndGet();
  }

  // ---- 写库、查询、清理 ----

  private Delta delta(LocalDate day, String keyId, String endpoint, String symbol) {
    return pending.computeIfAbsent(day + "|" + keyId + "|" + (endpoint == null ? "" : endpoint) + "|" + (symbol == null ? "" : symbol), x -> new Delta());
  }

  /** 把内存里的增量写进库（每分钟一次；查询前也调一次） */
  @Scheduled(fixedDelayString = "${quote.open.usage-flush:60s}")
  public synchronized void flush() {
    for (var e : pending.entrySet()) {
      Delta d = e.getValue();
      long calls = d.calls.getAndSet(0);
      long rej = d.rejected.getAndSet(0);
      long pushes = d.pushes.getAndSet(0);
      long secs = d.seconds.getAndSet(0);
      int peak = d.peak.getAndSet(0);
      if (calls == 0 && rej == 0 && pushes == 0 && secs == 0 && peak == 0) {
        // 前一天的、已经写完的才移走（今天的留着，免得和正在记的计数抢）
        if (!e.getKey().startsWith(day(clock.instant()) + "|")) pending.remove(e.getKey(), d);
        continue;
      }
      String[] p = e.getKey().split("\\|", -1);
      jdbc.update("INSERT INTO quote_usage_daily (day, key_id, endpoint, symbol, rest_calls, rest_rejected, ws_pushes, ws_seconds, ws_peak) VALUES "
          + "(?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE rest_calls = rest_calls + VALUES(rest_calls), rest_rejected = rest_rejected + VALUES(rest_rejected), "
          + "ws_pushes = ws_pushes + VALUES(ws_pushes), ws_seconds = ws_seconds + VALUES(ws_seconds), ws_peak = GREATEST(ws_peak, VALUES(ws_peak))",
          LocalDate.parse(p[0]), p[1], p[2], p[3], calls, rej, pushes, secs, peak);
    }
    if (!lastUsed.isEmpty()) {
      Map<String, Instant> copy = new HashMap<>(lastUsed);
      copy.forEach(lastUsed::remove);
      keys.touch(copy);
    }
    // 过了零点：清掉前一天的内存计数（库里已经有）
    LocalDate d = day(clock.instant());
    today.keySet().removeIf(k -> !k.startsWith(d + "|"));
    rejectedToday.keySet().removeIf(k -> !k.startsWith(d + "|"));
  }

  /** 按天汇总：keyId 为空 = 所有 Key；[from, to] 含两头 */
  public List<Day> days(String keyId, LocalDate from, LocalDate to) {
    flush();
    List<Object> args = new ArrayList<>(List.of(from, to));
    String where = "day BETWEEN ? AND ?";
    if (keyId != null) {
      where += " AND key_id = ?";
      args.add(keyId);
    }
    return jdbc.query("SELECT day, key_id, SUM(rest_calls), SUM(rest_rejected), SUM(ws_pushes), SUM(ws_seconds), MAX(ws_peak) FROM quote_usage_daily WHERE " + where
        + " GROUP BY day, key_id ORDER BY day DESC, key_id", (rs, i) -> new Day(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getLong(3), rs.getLong(4),
            rs.getLong(5), rs.getLong(6), rs.getInt(7)), args.toArray());
  }

  /** 明细：keyIds 为空 = 所有；symbol 为空 = 所有 */
  public List<Row> rows(List<String> keyIds, String symbol, LocalDate from, LocalDate to, int limit) {
    flush();
    List<Object> args = new ArrayList<>(List.of(from, to));
    StringBuilder where = new StringBuilder("day BETWEEN ? AND ?");
    if (keyIds != null) {
      if (keyIds.isEmpty()) return List.of();
      where.append(" AND key_id IN (").append(String.join(",", keyIds.stream().map(x -> "?").toList())).append(")");
      args.addAll(keyIds);
    }
    if (symbol != null && !symbol.isBlank()) {
      where.append(" AND symbol = ?");
      args.add(symbol);
    }
    args.add(limit);
    return jdbc.query("SELECT day, key_id, endpoint, symbol, rest_calls, rest_rejected, ws_pushes, ws_seconds, ws_peak FROM quote_usage_daily WHERE " + where
        + " ORDER BY day DESC, key_id, endpoint, symbol LIMIT ?", (rs, i) -> new Row(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getString(3),
            rs.getString(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getInt(9)), args.toArray());
  }

  /** 每天清理 13 个月以前的用量（UTC 20:20） */
  @Scheduled(cron = "${quote.open.usage-retention-cron:0 20 20 * * *}", zone = "UTC")
  public int purge() {
    LocalDate keepFrom = day(clock.instant()).minusMonths(props.retentionMonths());
    return jdbc.update("DELETE FROM quote_usage_daily WHERE day < ?", keepFrom);
  }

  boolean hasActiveKeys() {
    return keys.keys().stream().anyMatch(ApiKeys.Key::active);
  }

  /** 今天用量 ≥ 80% 每日限额的 Key（提醒邮件） */
  public Map<String, Long> over80() {
    Map<String, Long> out = new LinkedHashMap<>();
    for (ApiKeys.Key k : keys.keys()) {
      if (!k.active()) continue;
      long used = usedToday(k.keyId());
      if (used * 10 >= (long) k.limits().restPerDay() * 8) out.put(k.keyId(), used);
    }
    return out;
  }

  LocalDate day(Instant t) {
    return LocalDate.ofInstant(t, ZoneOffset.UTC);
  }
}
