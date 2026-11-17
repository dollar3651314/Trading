package app.trading.admin.security;

import app.trading.common.health.TestAdminFlag;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 测试管理员（做法 07，只在测试环境）：给 GitHub 上的验收用例（{@code .github/workflows/staging-e2e.yml}）调后台接口，不用登录。
 * <ul>
 *   <li>凭据：请求头 {@code X-Trading-Test-Admin: <令牌>}。令牌由 Amos 用密码管理器生成（40 位），在服务器上 {@code sudo trading gen-test-admin}
 *       隐藏输入；服务器只存它的 SHA-256（{@code TEST_ADMIN_TOKEN_SHA256}），按 SHA-256 定长比较。屏幕、日志、仓库里都没有原文。</li>
 *   <li><b>测试环境判定不只看一个变量</b>，两条都满足才认：{@code FX_ENV_LABEL=test}；报价模式 {@code FX_QUOTE_MARKET_SOURCE} 是 {@code simulated}
 *       （trading-app 和报价服务用同一个 .env）。不看 TopUP：测试服务器上 Trading 和 TopUP 轮流跑，TopUP 常常停着（部署方案 07 页）。</li>
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

  private final byte[] hash;
  private final boolean testEnv;

  TestAdmin(@Value("${trading.test-admin.token-sha256:}") String tokenSha256, @Value("${trading.env-label:}") String envLabel,
      @Value("${trading.test-admin.quote-market-source:}") String quoteSource) {
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
    if (this.hash != null && testEnv) log.warn("测试管理员已开（测试环境）");
  }

  /** 不能启动的原因（只有变量名，没有值）；可以启动返回 null */
  static String refuse(String tokenSha256, String envLabel, String quoteSource) {
    if (tokenSha256 == null || tokenSha256.isBlank()) return null;
    if (!"test".equals(envLabel) || "live".equals(quoteSource)) {
      return "不是测试环境却配了 " + ENV + "：生产不能有测试管理员，从服务器 .env 删掉这一行再启动（sudo trading test-admin-off）";
    }
    return null;
  }

  /** 这个环境有没有开测试管理员：配了哈希并且两条测试环境条件都满足 */
  public boolean enabled() {
    return hash != null && testEnv;
  }

  /** 健康检查用（只报是否） */
  @Override
  public boolean testAdminOn() {
    return enabled();
  }

  /** 令牌对不对（没开时一律 false） */
  boolean matches(String token) {
    if (token == null || token.isBlank() || !enabled()) return false;
    return MessageDigest.isEqual(sha256(token.strip()), hash);
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

}
