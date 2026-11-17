package app.quote.license;

import app.quote.license.QuoteLicense.Grant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报价源授权登记的内部接口（要内部 Key）：查看、新增或修改一行、删除一行。后台页面（二次确认、原因、审计）在第 3 批做，经 trading-app 后台调这里。
 */
@RestController
class LicenseController {

  static final Set<String> FEEDS = Set.of("alltick", "itick", "binance");
  private static final Pattern SYMBOL = Pattern.compile("\\*|[A-Z0-9]{1,16}");

  record GrantBody(Boolean granted, LocalDate from, LocalDate until) {}

  record Body(String feed, String symbol, GrantBody trading, GrantBody display, GrantBody resale, String note) {}

  private final LicenseRegistry registry;

  LicenseController(LicenseRegistry registry) {
    this.registry = registry;
  }

  @GetMapping("/internal/licenses")
  Map<String, Object> list() {
    return Map.of("enforced", registry.enforced(), "items", registry.all());
  }

  @PutMapping("/internal/licenses")
  ResponseEntity<Object> save(@RequestBody Body b) {
    if (b == null || b.feed() == null || !FEEDS.contains(b.feed())) return bad("feed 只能是 " + FEEDS);
    if (b.symbol() == null || !SYMBOL.matcher(b.symbol()).matches()) return bad("symbol 格式不对");
    if (b.note() != null && b.note().length() > 500) return bad("note 最多 500 字");
    Grant t;
    Grant d;
    Grant r;
    try {
      t = grant(b.trading());
      d = grant(b.display());
      r = grant(b.resale());
    } catch (IllegalArgumentException e) {
      return bad(e.getMessage());
    }
    QuoteLicense l = new QuoteLicense(b.feed(), b.symbol(), t, d, r, b.note());
    registry.save(l);
    return ResponseEntity.ok(l);
  }

  @DeleteMapping("/internal/licenses/{feed}/{symbol}")
  ResponseEntity<Object> delete(@PathVariable String feed, @PathVariable String symbol) {
    return registry.delete(feed, symbol) ? ResponseEntity.ok(Map.of("deleted", true)) : ResponseEntity.status(404).body(Map.of("code", "NOT_FOUND"));
  }

  private static Grant grant(GrantBody g) {
    if (g == null) return Grant.NONE;
    if (g.from() != null && g.until() != null && g.until().isBefore(g.from())) throw new IllegalArgumentException("到期日不能早于起始日");
    return new Grant(Boolean.TRUE.equals(g.granted()), g.from(), g.until());
  }

  private static ResponseEntity<Object> bad(String message) {
    return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION_FAILED", "message", message));
  }
}
