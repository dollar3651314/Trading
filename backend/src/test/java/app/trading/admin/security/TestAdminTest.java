package app.trading.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 测试管理员（做法 07）：测试环境三条条件、生产拒绝启动、白名单（默认拒绝）、改东西只收 POST、审计、打码、健康检查的 testAdmin。
 * 令牌是本测试里的一次性值，不是任何环境的密钥。
 */
@TestPropertySource(properties = {"trading.env-label=test", "trading.test-admin.quote-market-source=simulated",
    "trading.test-admin.token-sha256=" + TestAdminTest.HASH})
@Import(TestAdminTest.StagingTopUp.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TestAdminTest extends AdminTestSupport {

  static final String TOKEN = "ci-only-test-admin-token-not-a-secret";
  static final String HASH = "00cc95978cded3bb472caa251bc27093b02fd64a5fb93fbc0a73ba93a69b3561";

  @TestConfiguration
  static class StagingTopUp {
    @Bean
    @Primary
    TestAdmin.TopUpEnvProbe stagingTopUp() {
      return () -> true;
    }
  }

  @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
  @Autowired ObjectMapper json;

  static String sha256(String s) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
  }

  MockHttpServletResponse as(String token, MockHttpServletRequestBuilder b, String body) throws Exception {
    return call(b.header(TestAdmin.HEADER, token), null, body);
  }

  @Test
  void 测试用的哈希就是测试令牌的SHA256() throws Exception {
    assertThat(sha256(TOKEN)).isEqualTo(HASH);
  }

  @Test
  void 生产配了测试管理员就拒绝启动_只报变量名() {
    assertThat(TestAdmin.refuse(HASH, "", "simulated")).contains("TEST_ADMIN_TOKEN_SHA256").doesNotContain(HASH);
    assertThat(TestAdmin.refuse(HASH, "test", "live")).isNotNull();
    assertThat(TestAdmin.refuse(HASH, "prod", "simulated")).isNotNull();
    assertThat(TestAdmin.refuse(HASH, "test", "simulated")).isNull();
    assertThat(TestAdmin.refuse("", "", "live")).isNull();
    Clock c = Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC);
    assertThatThrownBy(() -> new TestAdmin(HASH, "", "", () -> true, c)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 三条测试环境条件少一条都不认() throws Exception {
    Clock c = Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC);
    assertThat(new TestAdmin(HASH, "test", "simulated", () -> true, c).matches(TOKEN)).isTrue();
    assertThat(new TestAdmin(HASH, "test", "simulated", () -> true, c).matches(TOKEN + "x")).isFalse();
    // TopUP 不是测试环境或查不到：这次不认，一分钟内不再查
    AtomicInteger probes = new AtomicInteger();
    TestAdmin notStaging = new TestAdmin(HASH, "test", "simulated", () -> {
      probes.incrementAndGet();
      return false;
    }, c);
    assertThat(notStaging.matches(TOKEN)).isFalse();
    assertThat(notStaging.matches(TOKEN)).isFalse();
    assertThat(probes.get()).isEqualTo(1);
    assertThat(new TestAdmin(HASH, "test", "simulated", () -> { throw new IllegalStateException("down"); }, c).matches(TOKEN)).isFalse();
    // 报价模式不是 simulated（没传或别的值）
    assertThat(new TestAdmin(HASH, "test", "", () -> true, c).matches(TOKEN)).isFalse();
    assertThat(new TestAdmin(HASH, "test", "none", () -> true, c).enabled()).isFalse();
    // 没配哈希、哈希格式不对
    assertThat(new TestAdmin("", "test", "simulated", () -> true, c).enabled()).isFalse();
    assertThat(new TestAdmin("not-hex", "test", "simulated", () -> true, c).enabled()).isFalse();
  }

  @Test
  void 令牌不对返回401_白名单里的只读接口返回200() throws Exception {
    assertThat(as("wrong-token", get("/api/admin/dashboard"), null).getStatus()).isEqualTo(401);
    assertThat(as("", get("/api/admin/dashboard"), null).getStatus()).isEqualTo(401);
    assertThat(as(TOKEN, get("/api/admin/dashboard"), null).getStatus()).isEqualTo(200);
    assertThat(as(TOKEN, get("/api/admin/withdrawals"), null).getStatus()).isEqualTo(200);
    assertThat(as(TOKEN, get("/api/admin/recon/status"), null).getStatus()).isEqualTo(200);
  }

  @Test
  void 白名单以外一律403() throws Exception {
    for (var b : new MockHttpServletRequestBuilder[] {get("/api/admin/admins"), get("/api/admin/customers"), get("/api/admin/kyc"),
        get("/api/admin/configs"), get("/api/admin/me"), get("/api/admin/audit-logs"), get("/api/admin/roles")}) {
      MockHttpServletResponse r = as(TOKEN, b, null);
      assertThat(r.getStatus()).as(b.toString()).isEqualTo(403);
      assertThat(r.getContentAsString()).contains("TEST_ADMIN_FORBIDDEN");
    }
    assertThat(as(TOKEN, put("/api/admin/configs/admin.ipWhitelist"), "{\"value\":\"\",\"reason\":\"x\"}").getStatus()).isEqualTo(403);
    assertThat(as(TOKEN, post("/api/admin/payment/switch"), "{\"provider\":\"TOPUP\",\"reason\":\"x\"}").getStatus()).isEqualTo(403);
    assertThat(as(TOKEN, post("/api/admin/risk/global-pause"), "{\"enabled\":true,\"reason\":\"x\"}").getStatus()).isEqualTo(403);
    assertThat(as(TOKEN, put("/api/admin/quote-service/gate"), "{\"open\":true,\"amosApproved\":true,\"reason\":\"x\"}").getStatus()).isEqualTo(403);
    assertThat(as(TOKEN, post("/api/admin/withdrawals/1/emergency-cancel"), "{\"reason\":\"x\"}").getStatus()).isEqualTo(403);
  }

  @Test
  void 报价服务只能建内部测试Key() throws Exception {
    MockHttpServletResponse r = as(TOKEN, post("/api/admin/quote-service/keys"), "{\"clientId\":1,\"kind\":\"EXTERNAL\",\"reason\":\"x\"}");
    assertThat(r.getStatus()).isEqualTo(403);
    assertThat(r.getContentAsString()).contains("TEST_ADMIN_FORBIDDEN");
  }

  @Test
  void 改东西的接口用GET调返回405_POST记一条审计() throws Exception {
    assertThat(as(TOKEN, get("/api/admin/recon/run"), null).getStatus()).isEqualTo(405);
    int before = audits("test_admin.request").size();
    assertThat(as(TOKEN, post("/api/admin/recon/run"), "{}").getStatus()).isEqualTo(200);
    var after = audits("test_admin.request");
    assertThat(after).hasSize(before + 1);
    assertThat(after.getLast()).startsWith("SUCCESS test-admin");
    // 驳回不存在的提现：失败也记
    as(TOKEN, post("/api/admin/withdrawals/999999999/reject"), "{\"reason\":\"e2e\"}");
    assertThat(audits("test_admin.request").getLast()).startsWith("FAILURE test-admin");
  }

  @Test
  void 白名单里每一项都是真实存在的后台接口_没有高危权限点_只有报价Key例外() {
    Set<String> found = new HashSet<>();
    for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
      HandlerMethod h = e.getValue();
      String key = AdminInterceptor.key(h);
      if (!TestAdmin.ALLOWED.contains(key)) continue;
      found.add(key);
      RequiresPermission rp = h.getMethodAnnotation(RequiresPermission.class);
      if (rp != null && rp.value().highRisk) assertThat(rp.value()).as(key).isEqualTo(Permission.QUOTE_KEY_OPERATE);
      assertThat(e.getKey().getPatternValues()).allMatch(p -> p.startsWith("/api/admin/"));
    }
    assertThat(found).containsExactlyInAnyOrderElementsOf(TestAdmin.ALLOWED);
  }

  @Test
  void 返回里的客户姓名和邮箱打码() throws Exception {
    JsonNode n = json.readTree("{\"rows\":[{\"id\":\"1\",\"customerId\":7,\"customerName\":\"张三\",\"email\":\"zs@example.com\","
        + "\"address\":\"TXYZ\",\"reason\":\"联系 zs@example.com 核对\",\"amount\":\"10\"}]}");
    JsonNode m = TestAdminMasking.mask(n);
    JsonNode row = m.path("rows").get(0);
    assertThat(row.path("customerName").asString()).isEqualTo("张***");
    assertThat(row.path("email").asString()).isEqualTo("z***");
    assertThat(row.path("reason").asString()).isEqualTo("联系 z*** 核对");
    assertThat(row.path("address").asString()).isEqualTo("TXYZ");
    assertThat(row.path("amount").asString()).isEqualTo("10");
  }

  @Test
  void 健康检查在测试环境报testAdmin是否开着() throws Exception {
    MockHttpServletResponse r = call(get("/api/health"), null, null);
    assertThat(r.getContentAsString()).contains("\"env\":\"test\"").contains("\"testAdmin\":true").doesNotContain(HASH);
  }
}
