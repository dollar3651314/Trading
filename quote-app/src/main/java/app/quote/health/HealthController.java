package app.quote.health;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查 GET /api/health（部署脚本和端到端脚本等它通过）：数据库能连上就是 UP；{@code alerts} 列出每个告警项（UP / DOWN），
 * 告警项不影响 {@code status}。不返回告警详情（详情在日志和 trading-app 的告警邮件里）。
 */
@RestController
class HealthController {

  private final JdbcTemplate jdbc;
  private final List<AlertCheck> alerts;

  HealthController(JdbcTemplate jdbc, List<AlertCheck> alerts) {
    this.jdbc = jdbc;
    this.alerts = alerts;
  }

  @GetMapping("/api/health")
  ResponseEntity<Map<String, Object>> health() {
    boolean db;
    try {
      db = Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class));
    } catch (RuntimeException e) {
      db = false;
    }
    Map<String, String> items = new LinkedHashMap<>();
    for (AlertCheck a : alerts) for (AlertCheck.Item i : a.evaluate()) items.put(i.name(), i.ok() ? "UP" : "DOWN");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", db ? "UP" : "DOWN");
    body.put("alerts", items);
    return ResponseEntity.status(db ? 200 : 503).body(body);
  }
}
