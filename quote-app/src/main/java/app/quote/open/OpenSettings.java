package app.quote.open;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 全平台设置（表 quote_open_setting）：对外开放总开关、默认限额。内存快照，改了立即重新加载，另外每分钟重新加载一次。
 * <p><b>总开关默认关</b>：库里没有这一行、值不是 {@code true}、读库出错，都按关处理；代码和配置里没有任何能绕过它的开关。
 * 只有 {@link #setGate} 能改（经 trading-app 后台，只有超管、要勾"Amos 已同意"、原因必填、写审计）。
 */
@Component
public class OpenSettings {

  static final String GATE = "gate_open";
  static final String DEFAULTS = "default_limits";

  /** 总开关的状态和最后一次修改 */
  public record Gate(boolean open, String updatedBy, String reason, Instant updatedAt) {}

  private final JdbcTemplate jdbc;
  private volatile Gate gate = new Gate(false, null, null, null);
  private volatile Limits defaults = Limits.DEFAULT;

  OpenSettings(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    reload();
  }

  /** 对外开放总开关是否打开 */
  public boolean gateOpen() {
    return gate.open();
  }

  public Gate gate() {
    return gate;
  }

  public Limits defaults() {
    return defaults;
  }

  public void setGate(boolean open, String by, String reason) {
    put(GATE, open ? "true" : "false", by, reason);
  }

  public void setDefaults(Limits l, String by, String reason) {
    l.validate();
    put(DEFAULTS, l.restPerSecond() + "," + l.restPerDay() + "," + l.wsConnections() + "," + l.wsSymbols(), by, reason);
  }

  private void put(String name, String value, String by, String reason) {
    jdbc.update("INSERT INTO quote_open_setting (name, value, updated_by, reason) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE value = VALUES(value), "
        + "updated_by = VALUES(updated_by), reason = VALUES(reason)", name, value, cut(by, 64), cut(reason, 500));
    reload();
  }

  @Scheduled(fixedDelayString = "${quote.open.settings-reload:60s}")
  public void reload() {
    Map<String, Object[]> rows = new HashMap<>();
    try {
      jdbc.query("SELECT name, value, updated_by, reason, updated_at FROM quote_open_setting", rs -> {
        rows.put(rs.getString(1), new Object[] {rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, java.time.LocalDateTime.class)});
      });
    } catch (RuntimeException e) {
      gate = new Gate(false, null, null, null); // 读不到就按关处理
      return;
    }
    Object[] g = rows.get(GATE);
    gate = g == null ? new Gate(false, null, null, null)
        : new Gate("true".equals(g[0]), (String) g[1], (String) g[2], g[3] == null ? null : ((java.time.LocalDateTime) g[3]).toInstant(java.time.ZoneOffset.UTC));
    Object[] d = rows.get(DEFAULTS);
    defaults = d == null ? Limits.DEFAULT : parse((String) d[0]);
  }

  private static Limits parse(String v) {
    try {
      String[] p = v.split(",");
      return new Limits(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])).validate();
    } catch (RuntimeException e) {
      return Limits.DEFAULT;
    }
  }

  private static String cut(String s, int n) {
    return s == null ? null : s.length() <= n ? s : s.substring(0, n);
  }
}
