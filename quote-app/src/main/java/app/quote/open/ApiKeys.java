package app.quote.open;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 外部客户和 Key（表 quote_client、quote_api_key）。Key 的内存快照（含解密后的 Secret，验签用）：改了立即重新加载，另外每 10 秒重新加载一次，
 * 所以停用、轮换、改限额最晚 10 秒内生效（经后台改的立即生效，对外 WebSocket 由 {@link OpenStream} 每秒检查一次并断开）。
 * <p>Secret 只在 {@link #createKey}、{@link #rotate} 的返回值里出现一次；{@link Key} 和所有列表都不带 Secret。
 * <p>重新加载的并发（审查 🟡，修 AC_Q2 偶发失败）：定时重新加载和改动后的重新加载都是"读整张表 → 换掉整个快照"，以前不加锁，
 * 定时的那次读到旧数据、在改动之后才换上去，就把刚做的改动盖掉（新 Key 401、旧 Key 还能用最多 10 秒，改的限额丢了）。现在：
 * <ul>
 *   <li>读和换在同一把锁里（{@link #reload}），后开始读的一定后换上去，旧快照盖不掉新快照；</li>
 *   <li>改动后的重新加载在事务提交之后才做（{@link #reloadAfterCommit}），不会读到没提交的数据，也不会在提交前被定时的那次抢先读到旧数据又换上去；</li>
 *   <li>轮换从库里读旧 Key 并加行锁（{@code SELECT … FOR UPDATE}），不信内存快照：限额以库里为准，同一个 Key 同时轮换两次只有一次成功。</li>
 * </ul>
 */
@Component
public class ApiKeys {

  public enum Kind {
    /** 内部测试：不受对外开放总开关限制（需求 03 页第四节） */
    INTERNAL_TEST,
    EXTERNAL
  }

  public record Client(long id, String code, String name, String email, boolean active, String note, Instant createdAt) {}

  /**
   * @param symbols 可用品种；空集合 = 全部已授权品种
   */
  public record Key(String keyId, long clientId, Kind kind, boolean active, Set<String> symbols, Limits limits, Instant createdAt, Instant disabledAt,
      Instant lastUsedAt, String rotatedFrom) {

    public boolean external() {
      return kind == Kind.EXTERNAL;
    }
  }

  /** 刚建好（或轮换出来）的 Key：secret 只在这里出现这一次 */
  public record Created(Key key, String secret) {}

  private record Entry(Key key, byte[] secret) {}

  private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9]{1,16}");
  private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

  private final JdbcTemplate jdbc;
  private final SecretBox box;
  private final OpenSettings settings;
  private final Clock clock;
  private volatile Map<String, Entry> keys = Map.of();
  /** 重新加载（读库 + 换快照）串行做 */
  private final ReentrantLock reloadLock = new ReentrantLock();
  /** 测试用：读完库、换快照之前调用（模拟定时重新加载读到旧数据后被拖慢）；生产为 null */
  private volatile Runnable beforeSwap;

  ApiKeys(JdbcTemplate jdbc, SecretBox box, OpenSettings settings, Clock clock) {
    this.jdbc = jdbc;
    this.box = box;
    this.settings = settings;
    this.clock = clock;
    reload();
  }

  // ---- 查 ----

  public Optional<Key> find(String keyId) {
    Entry e = keyId == null ? null : keys.get(keyId);
    return e == null ? Optional.empty() : Optional.of(e.key());
  }

  /** 验签用的 Secret（只在本进程内存里）；Key 不存在、停用或解不开时为空 */
  Optional<byte[]> secret(String keyId) {
    Entry e = keyId == null ? null : keys.get(keyId);
    return e == null || !e.key().active() || e.secret() == null ? Optional.empty() : Optional.of(e.secret());
  }

  /** 从库里查（不经过内存快照，后台改动前判断存在、取改动前的值用） */
  public Optional<Key> findInDb(String keyId) {
    return keyId == null ? Optional.empty() : load(keyId, false);
  }

  public List<Key> keys() {
    return keys.values().stream().map(Entry::key).sorted((a, b) -> b.createdAt().compareTo(a.createdAt())).toList();
  }

  public List<Client> clients() {
    return jdbc.query("SELECT id, code, name, email, status, note, created_at FROM quote_client ORDER BY id", (rs, i) -> client(rs));
  }

  public Optional<Client> client(long id) {
    return jdbc.query("SELECT id, code, name, email, status, note, created_at FROM quote_client WHERE id = ?", (rs, i) -> client(rs), id).stream().findFirst();
  }

  // ---- 改 ----

  /** 建外部客户；名称重复、邮箱格式不对抛 {@link IllegalArgumentException} */
  @Transactional
  public Client createClient(String name, String email, String note) {
    if (name == null || name.isBlank() || name.strip().length() > 100) throw new IllegalArgumentException("名称必填，最多 100 字");
    if (email != null && !email.isBlank() && (!EMAIL.matcher(email.strip()).matches() || email.length() > 200)) throw new IllegalArgumentException("联系邮箱格式不对");
    if (note != null && note.length() > 500) throw new IllegalArgumentException("备注最多 500 字");
    Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM quote_client", Long.class);
    String code = String.format("QC-%04d", (max == null ? 0 : max) + 1);
    try {
      jdbc.update("INSERT INTO quote_client (code, name, email, note, created_at) VALUES (?, ?, ?, ?, ?)", code, name.strip(), blank(email), blank(note), now());
    } catch (DuplicateKeyException e) {
      throw new IllegalArgumentException("同名客户已存在");
    }
    return clients().stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow();
  }

  /** 建 Key：限额复制当前默认值；symbols 为空 = 全部已授权品种 */
  @Transactional
  public Created createKey(long clientId, Kind kind, Set<String> symbols) {
    Client c = client(clientId).orElseThrow(() -> new IllegalArgumentException("没有这个客户"));
    if (!c.active()) throw new IllegalArgumentException("客户已停用");
    return insert(clientId, kind, normalize(symbols), settings.defaults(), null);
  }

  /** 轮换：新 Key 继承旧 Key 的客户、类型、品种、限额（从库里读并加行锁，不用内存快照）；旧 Key 立即停用 */
  @Transactional
  public Created rotate(String keyId) {
    Key old = load(keyId, true).orElseThrow(() -> new IllegalArgumentException("没有这个 Key"));
    if (!old.active()) throw new IllegalArgumentException("Key 已停用，不能轮换");
    jdbc.update("UPDATE quote_api_key SET status = 'DISABLED', disabled_at = ? WHERE key_id = ?", now(), keyId);
    return insert(old.clientId(), old.kind(), old.symbols(), old.limits(), keyId);
  }

  /** 停用；已经停用的返回 false */
  public boolean disable(String keyId) {
    int n = jdbc.update("UPDATE quote_api_key SET status = 'DISABLED', disabled_at = ? WHERE key_id = ? AND status = 'ACTIVE'", now(), keyId);
    reloadAfterCommit();
    return n > 0;
  }

  /** 改这一个 Key 的限额（不影响其他 Key 和默认值） */
  public Key setLimits(String keyId, Limits l) {
    l.validate();
    int n = jdbc.update("UPDATE quote_api_key SET rest_per_second = ?, rest_per_day = ?, ws_connections = ?, ws_symbols = ? WHERE key_id = ?", l.restPerSecond(),
        l.restPerDay(), l.wsConnections(), l.wsSymbols(), keyId);
    if (n == 0) throw new IllegalArgumentException("没有这个 Key");
    reloadAfterCommit();
    return load(keyId, false).orElseThrow();
  }

  /** 最近使用时间（用量写库时一起写） */
  void touch(Map<String, Instant> lastUsed) {
    lastUsed.forEach((k, t) -> jdbc.update("UPDATE quote_api_key SET last_used_at = ? WHERE key_id = ? AND (last_used_at IS NULL OR last_used_at < ?)",
        LocalDateTime.ofInstant(t, ZoneOffset.UTC), k, LocalDateTime.ofInstant(t, ZoneOffset.UTC)));
  }

  private Created insert(long clientId, Kind kind, Set<String> symbols, Limits l, String rotatedFrom) {
    if (!box.ready()) throw new IllegalStateException("没有配置 FX_QUOTE_KEY_ENC_KEY，不能建 Key");
    String keyId = "qk_" + SecretBox.random(20);
    String secret = "qs_" + SecretBox.random(40);
    jdbc.update("INSERT INTO quote_api_key (key_id, client_id, kind, secret_enc, symbols, rest_per_second, rest_per_day, ws_connections, ws_symbols, rotated_from, "
        + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", keyId, clientId, kind.name(), box.seal(secret), symbols.isEmpty() ? null : String.join(",", symbols),
        l.restPerSecond(), l.restPerDay(), l.wsConnections(), l.wsSymbols(), rotatedFrom, now());
    reloadAfterCommit();
    return new Created(load(keyId, false).orElseThrow(), secret);
  }

  private static Set<String> normalize(Set<String> symbols) {
    Set<String> out = new LinkedHashSet<>();
    if (symbols == null) return out;
    for (String s : symbols) {
      if (s == null || s.isBlank()) continue;
      String c = s.strip().toUpperCase(java.util.Locale.ROOT);
      if (!SYMBOL.matcher(c).matches()) throw new IllegalArgumentException("品种代码不对：" + s);
      out.add(c);
    }
    if (String.join(",", out).length() > 2000) throw new IllegalArgumentException("品种太多");
    return out;
  }

  private static final String COLS = "key_id, client_id, kind, status, secret_enc, symbols, rest_per_second, rest_per_day, ws_connections, ws_symbols, created_at, "
      + "disabled_at, last_used_at, rotated_from";

  /** 从库里读一个 Key（不经过内存快照）；forUpdate 时加行锁，要在事务里调用 */
  private Optional<Key> load(String keyId, boolean forUpdate) {
    return jdbc.query("SELECT " + COLS + " FROM quote_api_key WHERE key_id = ?" + (forUpdate ? " FOR UPDATE" : ""), (rs, i) -> key(rs), keyId).stream().findFirst();
  }

  /** 改动后的重新加载：在事务里就等提交之后再做（回滚了就不做），不在事务里立即做 */
  private void reloadAfterCommit() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          reload();
        }
      });
    } else {
      reload();
    }
  }

  /** 重新加载整个快照。读库和换快照在同一把锁里：后开始读的一定后换上去，旧快照盖不掉新快照 */
  @Scheduled(fixedDelayString = "${quote.open.keys-reload:10s}")
  public void reload() {
    reloadLock.lock();
    try {
      Map<String, Entry> m = new HashMap<>();
      for (Object[] r : jdbc.query("SELECT " + COLS + " FROM quote_api_key", (rs, i) -> new Object[] {key(rs), rs.getBytes("secret_enc")})) {
        Key k = (Key) r[0];
        m.put(k.keyId(), new Entry(k, k.active() ? box.open((byte[]) r[1]) : null));
      }
      Runnable hook = beforeSwap;
      if (hook != null) hook.run();
      keys = Map.copyOf(m);
    } finally {
      reloadLock.unlock();
    }
  }

  /** 测试用（Spring 给这个类生成了代理，测试不能直接改字段）：设读完库、换快照之前的钩子，null = 不要 */
  void beforeSwap(Runnable hook) {
    this.beforeSwap = hook;
  }

  /** 测试用：这个线程是否在等重新加载的锁 */
  boolean waitingForReload(Thread t) {
    return reloadLock.hasQueuedThread(t);
  }

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  private static Instant instant(ResultSet rs, String col) throws SQLException {
    LocalDateTime t = rs.getObject(col, LocalDateTime.class);
    return t == null ? null : t.toInstant(ZoneOffset.UTC);
  }

  private static Client client(ResultSet rs) throws SQLException {
    return new Client(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("email"), "ACTIVE".equals(rs.getString("status")),
        rs.getString("note"), instant(rs, "created_at"));
  }

  private static Key key(ResultSet rs) throws SQLException {
    String syms = rs.getString("symbols");
    Set<String> set = syms == null || syms.isBlank() ? Set.of() : Set.copyOf(new LinkedHashSet<>(Arrays.asList(syms.split(","))));
    return new Key(rs.getString("key_id"), rs.getLong("client_id"), Kind.valueOf(rs.getString("kind")), "ACTIVE".equals(rs.getString("status")), set,
        new Limits(rs.getInt("rest_per_second"), rs.getInt("rest_per_day"), rs.getInt("ws_connections"), rs.getInt("ws_symbols")), instant(rs, "created_at"),
        instant(rs, "disabled_at"), instant(rs, "last_used_at"), rs.getString("rotated_from"));
  }

  static String utf8(byte[] b) {
    return new String(b, StandardCharsets.UTF_8);
  }
}
