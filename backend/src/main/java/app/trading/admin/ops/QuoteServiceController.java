package app.trading.admin.ops;

import app.trading.admin.security.AdminContext;
import app.trading.admin.security.Permission;
import app.trading.admin.security.RequiresPermission;
import app.trading.admin.security.TestAdmin;
import app.trading.common.error.BizException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 后台"报价服务"菜单的接口（第 3 批，原型 P8～P11）。查看要 quote_service:view；改要对应的高危权限点；总开关只看是不是超管（{@link QuoteServiceOps#setGate}）。
 */
@RestController
class QuoteServiceController {

  record ClientBody(String name, String email, String note, String reason) {}

  record KeyBody(Long clientId, String kind, List<String> symbols, String reason) {}

  record ReasonBody(String reason) {}

  record GateBody(Boolean open, Boolean amosApproved, String reason) {}

  private final QuoteServiceOps ops;

  QuoteServiceController(QuoteServiceOps ops) {
    this.ops = ops;
  }

  @RequiresPermission(Permission.QUOTE_SERVICE_VIEW)
  @GetMapping("/api/admin/quote-service/overview")
  JsonNode overview() {
    return ops.overview();
  }

  @RequiresPermission(Permission.QUOTE_KEY_OPERATE)
  @PostMapping("/api/admin/quote-service/clients")
  JsonNode createClient(@RequestBody ClientBody b) {
    return ops.createClient(b == null ? null : b.name(), b == null ? null : b.email(), b == null ? null : b.note(), b == null ? null : b.reason());
  }

  @RequiresPermission(Permission.QUOTE_KEY_OPERATE)
  @PostMapping("/api/admin/quote-service/keys")
  JsonNode createKey(@RequestBody KeyBody b) {
    // 测试管理员（做法 07）只能建内部测试 Key
    if (TestAdmin.current() && (b == null || !"INTERNAL_TEST".equals(b.kind()))) throw new BizException("TEST_ADMIN_FORBIDDEN", "测试管理员只能建内部测试 Key");
    return ops.createKey(b == null ? null : b.clientId(), b == null ? null : b.kind(), b == null ? null : b.symbols(), b == null ? null : b.reason());
  }

  @RequiresPermission(Permission.QUOTE_KEY_OPERATE)
  @PostMapping("/api/admin/quote-service/keys/{keyId}/rotate")
  JsonNode rotate(@PathVariable String keyId, @RequestBody ReasonBody b) {
    return ops.rotateKey(keyId, b == null ? null : b.reason());
  }

  @RequiresPermission(Permission.QUOTE_KEY_OPERATE)
  @PostMapping("/api/admin/quote-service/keys/{keyId}/disable")
  void disable(@PathVariable String keyId, @RequestBody ReasonBody b) {
    // 测试管理员只能停用内部测试 Key：按报价服务的 Key 列表核对种类，查不到也不给
    if (TestAdmin.current() && !internalTestKey(keyId)) throw new BizException("TEST_ADMIN_FORBIDDEN", "测试管理员只能停用内部测试 Key");
    ops.disableKey(keyId, b == null ? null : b.reason());
  }

  private boolean internalTestKey(String keyId) {
    for (JsonNode k : ops.overview().path("keys")) {
      if (k.path("keyId").asString("").equals(keyId)) return "INTERNAL_TEST".equals(k.path("kind").asString(""));
    }
    return false;
  }

  @RequiresPermission(Permission.QUOTE_LIMIT_EDIT)
  @PutMapping("/api/admin/quote-service/keys/{keyId}/limits")
  JsonNode keyLimits(@PathVariable String keyId, @RequestBody QuoteServiceOps.LimitsBody b) {
    return ops.setKeyLimits(AdminContext.current(), keyId, b);
  }

  @RequiresPermission(Permission.QUOTE_LIMIT_EDIT)
  @PutMapping("/api/admin/quote-service/defaults")
  JsonNode defaults(@RequestBody QuoteServiceOps.LimitsBody b) {
    return ops.setDefaults(AdminContext.current(), b);
  }

  @RequiresPermission(Permission.QUOTE_SERVICE_VIEW)
  @GetMapping("/api/admin/quote-service/usage")
  JsonNode usage(@RequestParam(required = false) String from, @RequestParam(required = false) String to, @RequestParam(required = false) String keyId,
      @RequestParam(required = false) Long clientId, @RequestParam(required = false) String symbol) {
    return ops.usage(from, to, keyId, clientId, symbol);
  }

  @RequiresPermission(Permission.QUOTE_SERVICE_VIEW)
  @GetMapping("/api/admin/quote-service/usage/export")
  ResponseEntity<byte[]> export(@RequestParam(required = false) String from, @RequestParam(required = false) String to, @RequestParam(required = false) String keyId,
      @RequestParam(required = false) Long clientId, @RequestParam(required = false) String symbol) {
    return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"quote-usage.csv\"")
        .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8)).body(ops.exportUsage(from, to, keyId, clientId, symbol));
  }

  @RequiresPermission(Permission.QUOTE_SERVICE_VIEW)
  @GetMapping("/api/admin/quote-service/licenses")
  JsonNode licenses() {
    return ops.licenses();
  }

  @RequiresPermission(Permission.QUOTE_LICENSE_EDIT)
  @PutMapping("/api/admin/quote-service/licenses")
  JsonNode saveLicense(@RequestBody QuoteServiceOps.LicenseBody b) {
    return ops.saveLicense(b);
  }

  /** 总开关：只要登录（看页面要 quote_service:view），是不是超管在 ops 里判断，不是超管的也记一条失败审计 */
  @RequiresPermission(Permission.QUOTE_SERVICE_VIEW)
  @PutMapping("/api/admin/quote-service/gate")
  void gate(@RequestBody GateBody b) {
    ops.setGate(AdminContext.current(), b == null ? null : b.open(), b == null ? null : b.amosApproved(), b == null ? null : b.reason());
  }

  @RequiresPermission(Permission.QUOTE_SERVICE_VIEW)
  @GetMapping("/api/admin/quote-service/sources")
  JsonNode sources() {
    return ops.sources();
  }
}
