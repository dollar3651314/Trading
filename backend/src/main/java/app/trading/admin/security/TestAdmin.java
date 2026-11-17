package app.trading.admin.security;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import app.trading.common.health.TestAdminFlag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 测试管理员（做法 07，只在测试环境）：给 GitHub 上的验收用例（{@code .github/workflows/staging-e2e.yml}）调后台接口，不用登录。
 * <ul>
 *   <li>凭据：请求头 {@code X-Trading-Test-Admin: <令牌>}。服务器只存令牌的 SHA-256（{@code TEST_ADMIN_TOKEN_SHA256}，由服务器上
 *       {@code sudo trading gen-test-admin} 生成；令牌原文只在终端显示一次），按 SHA-256 定长比较。</li>
 *   <li><b>测试环境判定不只看一个变量</b>，三条都满足才认：{@code FX_ENV_LABEL=test}；报价模式 {@code FX_QUOTE_MARKET_SOURCE} 是 {@code simulated}
 *       （trading-app 和报价服务用同一个 .env）；{@code FX_TOPUP_BASE_URL} 指向的 TopUP 健康检查返回 {@code "env":"staging"}（查不到这次不认，过一分钟再查）。</li>
 *   <li><b>生产拒绝启动</b>：配了 {@code TEST_ADMIN_TOKEN_SHA256} 但不是测试环境（没有 {@code FX_ENV_LABEL=test}，或报价模式 live）时构造就抛错，应用起不来，
 *       部署脚本的健康检查失败、自动换回并发告警邮件。</li>
 *   <li>白名单（{@link #ALLOWED}）是"允许列表"：只列允许的后台接口（类名#方法名），没列的一律 403，以后新加的接口默认不给。
 *       管理员和角色、系统配置（含后台 IP 白名单）、钱包提供方开关、风控阈值、加点和品种、对外开放总开关、KYC、客户资料都不在里面。</li>
 *   <li>返回里的客户姓名、邮箱打码（{@link TestAdminMasking}）。审计里操作人记 {@code test-admin}，每个 POST 另记一条 {@code test_admin.request}。</li>
 * </ul>
 */
@Component
public class TestAdmin implements TestAdminFlag {

  public static final String HEADER = "X-Trading-Test-Admin";
  public static final String USERNAME = "test-admin";
  /** 测试管理员没有 admin_user 行，审计和业务表里的管理员编号记 0 */
  public static final long ADMIN_ID = 0L;
  static final String ATTR = "fx.admin.test";
  static final String ENV = "TEST_ADMIN_TOKEN_SHA256";

  /** 测试管理员能调用的后台接口：只读的页面（不含客户资料、KYC）、提现第一道审核和驳回、重新查询、对账、钱包提供方的只读和"检查连接"、报价服务 4 页、建和停用内部测试 Key */
  static final Set<String> ALLOWED = Set.of(
      // 只读
      "MonitorController#dashboard", "MonitorController#params", "MonitorController#tradeSymbols", "MonitorController#orders",
      "MonitorController#positions", "MonitorController#closedPositions", "MonitorController#pending", "MonitorController#risk",
      "MonitorController#symbols", "MonitorController#sessions", "MonitorController#holidays", "MonitorController#fx",
      "OpsController#badges", "OpsController#deposits", "OpsController#withdrawals", "OpsController#withdrawalSummary", "OpsController#withdrawal",
      "OpsController#autoApproveSettings", "OpsController#autoApproveChanges", "OpsController#recon", "OpsController#reconStatus",
      "OpsController#payment", "OpsController#hotWallets", "OpsController#tasks",
      "QuoteServiceController#overview", "QuoteServiceController#usage", "QuoteServiceController#licenses", "QuoteServiceController#sources",
      // 改东西（都只收 POST）：提现第一道审核（交给 TopUP 后停在"等待放款审核"，第二道审在 TopUP 由 Amos 做）、驳回、重新查询；对账；检查提供方连接
      "OpsController#approve", "OpsController#reject", "OpsController#requery", "OpsController#runRecon", "OpsController#checkPayment",
      // 报价服务：建外部客户、建 Key（只能 INTERNAL_TEST）、停用 Key（只能停 INTERNAL_TEST），见 QuoteServiceController
      "QuoteServiceController#createClient", "QuoteServiceController#createKey", "QuoteServiceController#disable");

  private static final Logger log = LoggerFactory.getLogger(TestAdmin.class);
  private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
  private static final Duration RETRY = Duration.ofMinutes(1);

  /** TopUP 是不是测试环境（可替换，测试里用假的） */
  @FunctionalInterface
  public interface TopUpEnvProbe {
    /** true = TopUP 健康检查返回 env=staging；查不到或不是返回 false */
    boolean staging();
  }

  private final byte[] hash;
  private final boolean testEnv;
  private final TopUpEnvProbe probe;
  private final Clock clock;
  private volatile boolean topupStaging;
  private volatile Instant nextProbe = Instant.EPOCH;

  TestAdmin(@Value("${trading.test-admin.token-sha256:}") String tokenSha256, @Value("${trading.env-label:}") String envLabel,
      @Value("${trading.test-admin.quote-market-source:}") String quoteSource, TopUpEnvProbe probe, Clock clock) {
    String h = tokenSha256 == null ? "" : tokenSha256.strip().toLowerCase(Locale.ROOT);
    String label = envLabel == null ? "" : envLabel.strip();
    String qs = quoteSource == null ? "" : quoteSource.strip();
    this.testEnv = "test".equals(label) && "simulated".equals(qs);
    String refuse = refuse(h, label, qs);
    if (refuse != null) throw new IllegalStateException(refuse);
    if (!h.isEmpty() && !HEX64.matcher(h).matches()) {
      log.error("{} 格式不对（要 64 位十六进制），测试管理员不开", ENV);
      h = "";
    }
    this.hash = h.isEmpty() ? null : HexFormat.of().parseHex(h);
    this.probe = probe;
    this.clock = clock;
    if (this.hash != null) log.warn("测试管理员已配置（只在测试环境、TopUP 也是测试环境时生效）");
  }

  /** 不能启动的原因（只有变量名，没有值）；可以启动返回 null */
  static String refuse(String tokenSha256, String envLabel, String quoteSource) {
    if (tokenSha256 == null || tokenSha256.isBlank()) return null;
    if (!"test".equals(envLabel) || "live".equals(quoteSource)) {
      return "不是测试环境却配了 " + ENV + "：生产不能有测试管理员，从服务器 .env 删掉这一行再启动（sudo trading test-admin-off）";
    }
    return null;
  }

  /** 这个环境有没有开测试管理员（只给健康检查报"是否"）：配了哈希并且三条测试环境条件都满足 */
  public boolean enabled() {
    return hash != null && testEnv && topupIsStaging();
  }

  /**
   * 健康检查用（只报是否）：不在请求线程里等 TopUP，还没确认过时在后台查一次，下一次健康检查再看结果
   */
  @Override
  public boolean testAdminOn() {
    if (hash == null || !testEnv) return false;
    if (topupStaging) return true;
    if (!clock.instant().isBefore(nextProbe)) Thread.ofVirtual().start(this::topupIsStaging);
    return false;
  }

  /** 令牌对不对（没开时一律 false） */
  boolean matches(String token) {
    if (token == null || token.isBlank() || !enabled()) return false;
    return MessageDigest.isEqual(sha256(token.strip()), hash);
  }

  private synchronized boolean topupIsStaging() {
    if (topupStaging) return true;
    Instant now = clock.instant();
    if (now.isBefore(nextProbe)) return false;
    boolean ok;
    try {
      ok = probe.staging();
    } catch (RuntimeException e) {
      ok = false;
    }
    if (ok) topupStaging = true;
    else {
      nextProbe = now.plus(RETRY);
      log.warn("测试管理员这次不认：TopUP 健康检查不是测试环境或查不到（{} 后再查）", RETRY);
    }
    return ok;
  }

  private static byte[] sha256(String s) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 当前请求是不是测试管理员发起的 */
  public static boolean current() {
    RequestAttributes a = RequestContextHolder.getRequestAttributes();
    return a != null && Boolean.TRUE.equals(a.getAttribute(ATTR, RequestAttributes.SCOPE_REQUEST));
  }

  /** 默认的 TopUP 探测：GET {FX_TOPUP_BASE_URL}/api/health/，返回的 env 是 staging 才算 */
  @Component
  static class HttpTopUpEnvProbe implements TopUpEnvProbe {

    private final String baseUrl;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    HttpTopUpEnvProbe(@Value("${trading.payment.topup.base-url:}") String baseUrl, ObjectMapper json) {
      this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
      this.json = json;
    }

    @Override
    public boolean staging() {
      if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) return false;
      try {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/health/")).timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) return false;
        JsonNode n = json.readTree(r.body());
        return "staging".equals(n.path("env").asString(""));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      } catch (Exception e) {
        return false;
      }
    }
  }
}
