package app.trading.admin.ops;

import app.trading.admin.security.AdminContext;
import app.trading.admin.security.Audit;
import app.trading.common.error.BizException;
import app.trading.common.error.ValidationException;
import app.trading.market.api.QuoteServiceApi;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 后台"报价服务"菜单（第 3 批，原型 P8～P11，需求 03 页第三、四节）：数据在报价服务（quote-app）里，这里转发、做权限和审计。
 * <ul>
 *   <li>建客户、建 / 轮换 / 停用 Key、改限额、改默认值、授权登记、开关总开关都是高危操作：原因必填、写审计（含失败），二次确认在页面上做；</li>
 *   <li><b>对外开放总开关只有超管能改</b>；打开要勾"Amos 已同意"（L3，TY-3），报价服务那边也再检查一次；</li>
 *   <li>Secret 只在建 Key、轮换的返回里给页面一次，<b>不写进审计</b>。</li>
 * </ul>
 * 只计量不收费（TD-6 A）：没有价格、账单、支付相关的接口。
 */
@Service
class QuoteServiceOps {

  private static final Pattern KEY_ID = Pattern.compile("qk_[A-Za-z0-9]{1,40}");
  private static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

  private final QuoteServiceApi quote;
  private final Audit audit;
  private final ObjectMapper json;

  QuoteServiceOps(QuoteServiceApi quote, Audit audit, ObjectMapper json) {
    this.quote = quote;
    this.audit = audit;
    this.json = json;
  }

  // ---- 查 ----

  JsonNode overview() {
    return get("/internal/open/overview");
  }

  JsonNode licenses() {
    return get("/internal/open/licenses");
  }

  JsonNode sources() {
    return get("/internal/open/sources");
  }

  JsonNode usage(String from, String to, String keyId, Long clientId, String symbol) {
    return get("/internal/open/usage" + usageQuery(from, to, keyId, clientId, symbol));
  }

  byte[] exportUsage(String from, String to, String keyId, Long clientId, String symbol) {
    QuoteServiceApi.Response r = call("GET", "/internal/open/usage/export" + usageQuery(from, to, keyId, clientId, symbol), null);
    return r.body().getBytes(StandardCharsets.UTF_8);
  }

  private static String usageQuery(String from, String to, String keyId, Long clientId, String symbol) {
    StringBuilder q = new StringBuilder();
    if (from != null && DAY.matcher(from).matches()) q.append("&from=").append(from);
    if (to != null && DAY.matcher(to).matches()) q.append("&to=").append(to);
    if (keyId != null && KEY_ID.matcher(keyId).matches()) q.append("&keyId=").append(keyId);
    if (clientId != null) q.append("&clientId=").append(clientId);
    if (symbol != null && symbol.matches("[A-Z0-9]{1,16}")) q.append("&symbol=").append(symbol);
    return q.isEmpty() ? "" : "?" + q.substring(1);
  }

  // ---- 改（高危：原因必填、写审计） ----

  JsonNode createClient(String name, String email, String note, String reason) {
    String r = Audit.requireReason(reason);
    return audit.run("quote.client_create", String.valueOf(name), true, r, () -> {
      JsonNode res = send("POST", "/internal/open/clients", body("name", name, "email", email, "note", note));
      return Audit.Change.of(res, null, Map.of("code", res.path("code").asString(""), "name", res.path("name").asString("")));
    });
  }

  /** 返回里带 Secret（只这一次）；审计只记 Key ID */
  JsonNode createKey(Long clientId, String kind, List<String> symbols, String reason) {
    String r = Audit.requireReason(reason);
    if (clientId == null) throw new ValidationException(List.of(new ValidationException.Field("clientId", "required")));
    if (!"INTERNAL_TEST".equals(kind) && !"EXTERNAL".equals(kind)) throw new ValidationException(List.of(new ValidationException.Field("kind", "invalid")));
    return audit.run("quote.key_create", "client " + clientId, true, r, () -> {
      JsonNode res = send("POST", "/internal/open/keys", body("clientId", clientId, "kind", kind, "symbols", symbols == null ? List.of() : symbols));
      return Audit.Change.of(res, null, Map.of("keyId", res.path("key").path("keyId").asString(""), "kind", kind));
    });
  }

  JsonNode rotateKey(String keyId, String reason) {
    String r = Audit.requireReason(reason);
    String id = keyId(keyId);
    return audit.run("quote.key_rotate", id, true, r, () -> {
      JsonNode res = send("POST", "/internal/open/keys/" + id + "/rotate", null);
      return Audit.Change.of(res, Map.of("keyId", id, "active", true), Map.of("keyId", res.path("key").path("keyId").asString(""), "oldActive", false));
    });
  }

  void disableKey(String keyId, String reason) {
    String r = Audit.requireReason(reason);
    String id = keyId(keyId);
    audit.run("quote.key_disable", id, true, r, () -> {
      send("POST", "/internal/open/keys/" + id + "/disable", null);
      return Audit.Change.of(null, Map.of("active", true), Map.of("active", false));
    });
  }

  record LimitsBody(Integer restPerSecond, Integer restPerDay, Integer wsConnections, Integer wsSymbols, String reason) {}

  JsonNode setKeyLimits(AdminContext actor, String keyId, LimitsBody b) {
    String r = Audit.requireReason(b == null ? null : b.reason());
    String id = keyId(keyId);
    return audit.run("quote.key_limits", id, true, r, () -> {
      JsonNode res = send("PUT", "/internal/open/keys/" + id + "/limits", limits(b, actor, r));
      return Audit.Change.of(res, map(res.path("before")), map(res.path("after")));
    });
  }

  JsonNode setDefaults(AdminContext actor, LimitsBody b) {
    String r = Audit.requireReason(b == null ? null : b.reason());
    return audit.run("quote.default_limits", "default", true, r, () -> {
      JsonNode res = send("PUT", "/internal/open/defaults", limits(b, actor, r));
      return Audit.Change.of(res, map(res.path("before")), map(res.path("after")));
    });
  }

  /** 对外开放总开关：只有超管；打开要勾"Amos 已同意"；原因必填；写审计（含失败） */
  void setGate(AdminContext actor, Boolean open, Boolean amosApproved, String reason) {
    if (open == null) throw new ValidationException(List.of(new ValidationException.Field("open", "required")));
    String action = open ? "quote.gate_on" : "quote.gate_off";
    if (!actor.superAdmin()) {
      audit.record(action, "gate", true, "FAILURE", "FORBIDDEN（只有超管能改对外总开关）", null, null);
      throw new BizException("FORBIDDEN", "只有超管能改对外总开关");
    }
    String r = Audit.requireReason(reason);
    if (open && !Boolean.TRUE.equals(amosApproved)) {
      audit.record(action, "gate", true, "FAILURE", r + "（失败：没勾 Amos 已同意）", null, null);
      throw new ValidationException(List.of(new ValidationException.Field("amosApproved", "required")));
    }
    audit.run(action, "gate", true, r, () -> {
      JsonNode res = send("PUT", "/internal/open/gate", body("open", open, "amosApproved", Boolean.TRUE.equals(amosApproved), "by", actor.username(), "reason", r));
      return Audit.Change.of(null, Map.of("open", res.path("before").asBoolean()), Map.of("open", res.path("after").asBoolean(), "amosApproved",
          Boolean.TRUE.equals(amosApproved)));
    });
  }

  record GrantBody(Boolean granted, String from, String until) {}

  record LicenseBody(String feed, String symbol, GrantBody trading, GrantBody display, GrantBody resale, String note, String reason) {}

  /** 授权登记 / 续登记（整行替换；不存合同原文和价格） */
  JsonNode saveLicense(LicenseBody b) {
    String r = Audit.requireReason(b == null ? null : b.reason());
    String target = b.feed() + " × " + b.symbol();
    return audit.run("quote.license_save", target, true, r, () -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("feed", b.feed());
      m.put("symbol", b.symbol());
      m.put("trading", b.trading());
      m.put("display", b.display());
      m.put("resale", b.resale());
      m.put("note", b.note());
      JsonNode res = send("PUT", "/internal/licenses", json.writeValueAsString(m));
      Map<String, Object> after = new LinkedHashMap<>(m);
      after.remove("note");
      return Audit.Change.of(res, null, after);
    });
  }

  // ---- 转发 ----

  private static String keyId(String keyId) {
    if (keyId == null || !KEY_ID.matcher(keyId).matches()) throw new BizException("NOT_FOUND", "没有这个 Key");
    return keyId;
  }

  private String limits(LimitsBody b, AdminContext actor, String reason) {
    return body("restPerSecond", b.restPerSecond(), "restPerDay", b.restPerDay(), "wsConnections", b.wsConnections(), "wsSymbols", b.wsSymbols(), "by",
        actor.username(), "reason", reason);
  }

  private String body(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return json.writeValueAsString(m);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(JsonNode n) {
    return n == null || n.isMissingNode() || n.isNull() ? null : json.convertValue(n, Map.class);
  }

  private JsonNode get(String path) {
    return send("GET", path, null);
  }

  /** 发出去；报价服务返回 4xx 时把它的原因翻成业务错误（页面显示"保存失败：原因"） */
  private JsonNode send(String method, String path, String body) {
    QuoteServiceApi.Response r = call(method, path, body);
    return json.readTree(r.body());
  }

  private QuoteServiceApi.Response call(String method, String path, String body) {
    QuoteServiceApi.Response r;
    try {
      r = quote.call(method, path, body).orElseThrow(() -> new BizException("QUOTE_SERVICE_UNAVAILABLE", "没有连接报价服务"));
    } catch (IllegalStateException e) {
      throw new BizException("QUOTE_SERVICE_UNAVAILABLE", "报价服务暂时连不上，没有变，请重试");
    }
    if (r.status() == 404) throw new BizException("NOT_FOUND", message(r, "没有找到"));
    if (r.status() >= 400 && r.status() < 500) throw new BizException("QUOTE_SERVICE_REJECTED", message(r, "报价服务拒绝了这次操作"));
    if (r.status() >= 500) throw new BizException("QUOTE_SERVICE_UNAVAILABLE", message(r, "报价服务暂时不可用，没有变，请重试"));
    return r;
  }

  private String message(QuoteServiceApi.Response r, String fallback) {
    try {
      String m = json.readTree(r.body()).path("message").asString("");
      return m.isBlank() ? fallback : m;
    } catch (RuntimeException e) {
      return fallback;
    }
  }

  static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
