package app.trading.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import app.trading.market.quote.RemoteQuotes;
import app.trading.support.MutableClock;
import app.trading.support.QuoteAppHarness;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 后台"报价服务"菜单（第 3 批，原型 P8～P11，AC-Q1、AC-Q2、AC-Q3、AC-Q11）：交易这边转发到<b>真实的报价服务</b>（{@link QuoteAppHarness}），这边做权限和审计。
 * <ul>
 *   <li>对外开放总开关：只有超管能改；打开要勾"Amos 已同意"、原因必填；成功失败都写审计；</li>
 *   <li>建客户、建 / 轮换 / 停用 Key、改限额、改默认值、授权登记：要对应的权限点、原因必填、写审计；Secret 只在建 Key 的返回里、不进审计；</li>
 *   <li>用量、导出 CSV、报价源状态、授权登记状态能查；没有账单、价格字段。</li>
 * </ul>
 */
@TestPropertySource(properties = {"trading.market.source=remote"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminQuoteServiceTest extends AdminTestSupport {

  static final QuoteAppHarness quoteApp = QuoteAppHarness.start(new MutableClock(), Map.of());

  @DynamicPropertySource
  static void quoteApp(DynamicPropertyRegistry r) {
    r.add("trading.market.quote-app.url", quoteApp::url);
    r.add("trading.market.quote-app.key", () -> QuoteAppHarness.KEY);
  }

  @AfterAll
  static void stopQuoteApp() {
    quoteApp.close();
  }

  @Autowired RemoteQuotes remote;
  final ObjectMapper json = new ObjectMapper();
  final org.springframework.jdbc.core.JdbcTemplate quoteDb = new org.springframework.jdbc.core.JdbcTemplate(quoteApp.bean(javax.sql.DataSource.class));

  @BeforeEach
  void connected() throws InterruptedException {
    long end = System.nanoTime() + 10_000_000_000L;
    while (!remote.connected() && System.nanoTime() < end) Thread.sleep(20);
  }

  @AfterEach
  void clean() {
    for (String t : List.of("quote_api_key", "quote_client", "quote_open_setting", "quote_usage_daily", "quote_source_license")) quoteDb.update("DELETE FROM " + t);
    quoteApp.bean(app.quote.open.ApiKeys.class).reload();
    quoteApp.bean(app.quote.open.OpenSettings.class).reload();
    quoteApp.bean(app.quote.license.LicenseRegistry.class).reload();
  }

  JsonNode body(MockHttpServletResponse r) throws Exception {
    return json.readTree(r.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  void 对外总开关_只有超管能改_打开要勾Amos已同意和原因_都写审计() throws Exception {
    Admin all = withRoles(role(Permission.QUOTE_SERVICE_VIEW, Permission.QUOTE_KEY_OPERATE, Permission.QUOTE_LIMIT_EDIT, Permission.QUOTE_LICENSE_EDIT));
    MockHttpServletResponse r = call(put("/api/admin/quote-service/gate"), all.cookie(), "{\"open\":true,\"amosApproved\":true,\"reason\":\"Amos 11/20 同意\"}");
    assertThat(r.getStatus()).as("有全部报价服务权限点也不行，只有超管").isEqualTo(403);
    Admin su = superAdmin();
    assertThat(call(put("/api/admin/quote-service/gate"), su.cookie(), "{\"open\":true,\"reason\":\"Amos 11/20 同意\"}").getStatus()).as("没勾 Amos 已同意").isEqualTo(400);
    assertThat(call(put("/api/admin/quote-service/gate"), su.cookie(), "{\"open\":true,\"amosApproved\":true,\"reason\":\" \"}").getStatus()).as("原因必填").isEqualTo(400);
    assertThat(body(call(get("/api/admin/quote-service/overview"), su.cookie(), null)).path("gate").path("open").asBoolean()).as("默认关，前面都没打开").isFalse();
    assertThat(call(put("/api/admin/quote-service/gate"), su.cookie(), "{\"open\":true,\"amosApproved\":true,\"reason\":\"Amos 11/20 同意\"}").getStatus()).isEqualTo(200);
    JsonNode gate = body(call(get("/api/admin/quote-service/overview"), su.cookie(), null)).path("gate");
    assertThat(gate.path("open").asBoolean()).isTrue();
    assertThat(gate.path("updatedBy").asString()).isEqualTo(su.username());
    assertThat(call(put("/api/admin/quote-service/gate"), su.cookie(), "{\"open\":false,\"reason\":\"收回\"}").getStatus()).isEqualTo(200);
    assertThat(audits("quote.gate_on")).anyMatch(a -> a.startsWith("FAILURE") && a.contains("只有超管")).anyMatch(a -> a.startsWith("FAILURE") && a.contains("Amos"))
        .anyMatch(a -> a.equals("SUCCESS " + su.username() + " Amos 11/20 同意"));
    assertThat(audits("quote.gate_off")).anyMatch(a -> a.startsWith("SUCCESS"));
  }

  @Test
  void 建客户建Key_Secret只在返回里出现一次_不进审计_停用轮换改限额改默认值都写审计() throws Exception {
    Admin op = withRoles(role(Permission.QUOTE_SERVICE_VIEW, Permission.QUOTE_KEY_OPERATE, Permission.QUOTE_LIMIT_EDIT));
    Admin viewer = withRoles(role(Permission.QUOTE_SERVICE_VIEW));
    assertThat(call(post("/api/admin/quote-service/clients"), viewer.cookie(), "{\"name\":\"示例\",\"reason\":\"测试\"}").getStatus()).as("只能看的不能建").isEqualTo(403);
    assertThat(call(post("/api/admin/quote-service/clients"), op.cookie(), "{\"name\":\"示例\"}").getStatus()).as("原因必填").isEqualTo(400);
    JsonNode cl = body(call(post("/api/admin/quote-service/clients"), op.cookie(), "{\"name\":\"示例量化工作室\",\"email\":\"dev@example.com\",\"reason\":\"TM-1 目标客户\"}"));
    assertThat(cl.path("code").asString()).startsWith("QC-");
    MockHttpServletResponse k = call(post("/api/admin/quote-service/keys"), op.cookie(),
        "{\"clientId\":" + cl.path("id").asLong() + ",\"kind\":\"EXTERNAL\",\"symbols\":[\"EURUSD\",\"XAUUSD\"],\"reason\":\"对接测试\"}");
    assertThat(k.getStatus()).isEqualTo(200);
    JsonNode created = body(k);
    String keyId = created.path("key").path("keyId").asString();
    String secret = created.path("secret").asString();
    assertThat(secret).startsWith("qs_");
    assertThat(created.path("key").path("usableNow").asBoolean()).as("外部 Key 在总开关关时现在不能用").isFalse();
    String overview = call(get("/api/admin/quote-service/overview"), viewer.cookie(), null).getContentAsString();
    assertThat(overview).contains(keyId).doesNotContain(secret);
    String auditText = String.join("\n", jdbc.queryForList("SELECT CONCAT(COALESCE(before_json, ''), COALESCE(after_json, ''), COALESCE(reason, '')) FROM admin_audit_log "
        + "WHERE action LIKE 'quote.%'", String.class));
    assertThat(auditText).contains(keyId).doesNotContain(secret);

    assertThat(call(put("/api/admin/quote-service/keys/" + keyId + "/limits"), op.cookie(),
        "{\"restPerSecond\":5,\"restPerDay\":5000,\"wsConnections\":1,\"wsSymbols\":20,\"reason\":\"客户要求\"}").getStatus()).isEqualTo(200);
    assertThat(call(put("/api/admin/quote-service/defaults"), op.cookie(),
        "{\"restPerSecond\":0,\"restPerDay\":5000,\"wsConnections\":1,\"wsSymbols\":20,\"reason\":\"测试\"}").getStatus()).as("每项要是正整数").isEqualTo(400);
    assertThat(call(put("/api/admin/quote-service/defaults"), op.cookie(),
        "{\"restPerSecond\":20,\"restPerDay\":200000,\"wsConnections\":2,\"wsSymbols\":50,\"reason\":\"调默认\"}").getStatus()).isEqualTo(200);
    JsonNode keyRow = body(call(get("/api/admin/quote-service/overview"), op.cookie(), null)).path("keys").get(0);
    assertThat(keyRow.path("limits").path("restPerDay").asInt()).as("改默认值不影响已建 Key").isEqualTo(5000);
    JsonNode rotated = body(call(post("/api/admin/quote-service/keys/" + keyId + "/rotate"), op.cookie(), "{\"reason\":\"客户说泄露了\"}"));
    String newKey = rotated.path("key").path("keyId").asString();
    assertThat(newKey).isNotEqualTo(keyId);
    assertThat(call(post("/api/admin/quote-service/keys/" + newKey + "/disable"), op.cookie(), "{\"reason\":\"不用了\"}").getStatus()).isEqualTo(200);
    assertThat(audits("quote.client_create")).filteredOn(a -> a.contains(op.username())).containsExactly("SUCCESS " + op.username() + " TM-1 目标客户");
    assertThat(audits("quote.key_create")).filteredOn(a -> a.contains(op.username())).containsExactly("SUCCESS " + op.username() + " 对接测试");
    assertThat(audits("quote.key_limits")).filteredOn(a -> a.contains(op.username())).containsExactly("SUCCESS " + op.username() + " 客户要求");
    assertThat(audits("quote.default_limits")).anyMatch(a -> a.startsWith("FAILURE")).anyMatch(a -> a.equals("SUCCESS " + op.username() + " 调默认"));
    assertThat(audits("quote.key_rotate")).filteredOn(a -> a.contains(op.username())).containsExactly("SUCCESS " + op.username() + " 客户说泄露了");
    assertThat(audits("quote.key_disable")).filteredOn(a -> a.contains(op.username())).containsExactly("SUCCESS " + op.username() + " 不用了");
    assertThat(String.join("", audits("quote.client_create"))).doesNotContain(secret);
  }

  @Test
  void 授权登记_用量_导出CSV_报价源状态_没有账单价格字段() throws Exception {
    Admin op = withRoles(role(Permission.QUOTE_SERVICE_VIEW, Permission.QUOTE_LICENSE_EDIT));
    String lic = "{\"feed\":\"alltick\",\"symbol\":\"XAUUSD\",\"trading\":{\"granted\":true,\"from\":\"2026-01-05\",\"until\":\"2026-11-01\"},"
        + "\"display\":{\"granted\":true},\"resale\":{\"granted\":false},\"note\":\"（示例）\"";
    assertThat(call(put("/api/admin/quote-service/licenses"), op.cookie(), lic + "}").getStatus()).as("原因必填").isEqualTo(400);
    assertThat(call(put("/api/admin/quote-service/licenses"), op.cookie(), lic + ",\"reason\":\"合同已签\"}").getStatus()).isEqualTo(200);
    JsonNode l = body(call(get("/api/admin/quote-service/licenses"), op.cookie(), null));
    JsonNode item = l.path("items").get(0);
    assertThat(item.path("trading").path("state").asString()).as("2026-11-01 到期，今天 2026-10-14，还剩 18 天").isEqualTo("soon");
    assertThat(item.path("trading").path("daysLeft").asInt()).isEqualTo(18);
    assertThat(item.path("resale").path("state").asString()).isEqualTo("none");
    assertThat(l.path("gate").path("open").asBoolean()).isFalse();
    assertThat(audits("quote.license_save")).anyMatch(a -> a.equals("SUCCESS " + op.username() + " 合同已签"));
    assertThat(call(put("/api/admin/quote-service/licenses"), op.cookie(),
        "{\"feed\":\"alltick\",\"symbol\":\"XAUUSD\",\"trading\":{\"granted\":true,\"from\":\"2026-12-01\",\"until\":\"2026-01-01\"},\"reason\":\"测试\"}").getStatus())
        .as("到期日早于授权日期").isEqualTo(400);

    MockHttpServletResponse u = call(get("/api/admin/quote-service/usage?from=2026-10-01&to=2026-10-14"), op.cookie(), null);
    assertThat(u.getStatus()).isEqualTo(200);
    MockHttpServletResponse csv = call(get("/api/admin/quote-service/usage/export?from=2026-10-01&to=2026-10-14"), op.cookie(), null);
    assertThat(csv.getStatus()).isEqualTo(200);
    assertThat(csv.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("日期,Key,接口,品种,REST 请求数");
    JsonNode src = body(call(get("/api/admin/quote-service/sources"), op.cookie(), null));
    assertThat(src.path("rows").size()).isGreaterThanOrEqualTo(50);
    assertThat(src.path("feeds").size()).isEqualTo(3);
    String all = u.getContentAsString() + call(get("/api/admin/quote-service/overview"), op.cookie(), null).getContentAsString();
    assertThat(all.toLowerCase(java.util.Locale.ROOT)).doesNotContain("price").doesNotContain("bill").doesNotContain("invoice").doesNotContain("payment");
  }
}
