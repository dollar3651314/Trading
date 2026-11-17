package app.trading.admin.security;

import app.trading.common.config.ConfigKey;
import app.trading.common.config.SystemConfig;
import app.trading.common.error.BizException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 后台接口的三道关（后台方案一图看懂）：
 * <ol>
 *   <li>IP 白名单（系统配置 {@code admin.ipWhitelist}，为空不限制）：只认 {@code request.getRemoteAddr()}，也就是 Nginx 传来的真实 IP，
 *       客户端自己带的 {@code X-Forwarded-For} 不起作用（只有内网代理传来的才会被 Tomcat 采用）。</li>
 *   <li>登录：后台自己的 Cookie。</li>
 *   <li>权限点：接口上的 {@link RequiresPermission}；超级管理员拥有全部。<b>没有声明的接口一律 403</b>（默认拒绝）。</li>
 * </ol>
 * 首次登录、重置密码后只能调用 {@link AdminLoginOnly} 的接口（改密码、当前信息、退出）。
 * <p>
 * 测试管理员（{@link TestAdmin}，只在测试环境）：带了请求头 {@code X-Trading-Test-Admin} 的请求不看 Cookie，IP 白名单照样检查；
 * 令牌不对或这个环境没开一律 401；接口不在白名单一律 403（{@code TEST_ADMIN_FORBIDDEN}）；每个 POST 另记一条审计 {@code test_admin.request}。
 */
@Component
class AdminInterceptor implements HandlerInterceptor {

  private static final Logger log = LoggerFactory.getLogger(AdminInterceptor.class);

  private final SystemConfig config;
  private final AdminAuthService auth;
  private final AdminProperties props;
  private final TestAdmin testAdmin;
  private final Audit audit;

  AdminInterceptor(SystemConfig config, AdminAuthService auth, AdminProperties props, TestAdmin testAdmin, Audit audit) {
    this.config = config;
    this.auth = auth;
    this.props = props;
    this.testAdmin = testAdmin;
    this.audit = audit;
  }

  @Override
  public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
    String ip = req.getRemoteAddr();
    req.setAttribute(AdminContext.IP_ATTR, ip);
    String list = config.text(ConfigKey.ADMIN_IP_WHITELIST, "");
    if (!list.isBlank()) {
      var ranges = IpRanges.parse(list);
      if (ranges.isEmpty() || !ranges.get().contains(ip)) {
        log.warn("后台请求 IP 不在白名单：{} {}", ip, req.getRequestURI());
        throw new BizException("IP_NOT_ALLOWED", "当前网络不能访问后台");
      }
    }
    String testToken = req.getHeader(TestAdmin.HEADER);
    if (testToken != null) return testAdmin(req, handler, testToken, ip);
    if (!(handler instanceof HandlerMethod hm)) return true;
    if (hm.hasMethodAnnotation(AdminPublic.class)) return true;
    AdminContext ctx = auth.authenticate(token(req), ip).orElseThrow(() -> new BizException("UNAUTHORIZED", "未登录或登录已失效"));
    req.setAttribute(AdminContext.ATTR, ctx);
    if (hm.hasMethodAnnotation(AdminLoginOnly.class)) return true;
    if (auth.mustChangePassword(ctx.adminId())) throw new BizException("PASSWORD_CHANGE_REQUIRED", "请先修改密码");
    RequiresPermission rp = hm.getMethodAnnotation(RequiresPermission.class);
    if (rp == null) {
      log.error("后台接口没有声明权限点，已拒绝：{}", hm);
      throw new BizException("FORBIDDEN", "没有权限");
    }
    if (!ctx.has(rp.value())) throw new BizException("FORBIDDEN", "没有权限");
    return true;
  }

  /** 测试管理员的请求：令牌对、这个环境开着、接口在白名单里才放行 */
  private boolean testAdmin(HttpServletRequest req, Object handler, String token, String ip) {
    if (!testAdmin.matches(token)) {
      log.warn("测试管理员令牌不对或这个环境没开：{} {}", ip, req.getRequestURI());
      throw new BizException("UNAUTHORIZED", "未登录或登录已失效");
    }
    if (!(handler instanceof HandlerMethod hm) || !TestAdmin.ALLOWED.contains(key(hm))) {
      log.warn("测试管理员调用了白名单以外的接口：{}", req.getRequestURI());
      throw new BizException("TEST_ADMIN_FORBIDDEN", "测试管理员不能调用这个接口");
    }
    RequiresPermission rp = hm.getMethodAnnotation(RequiresPermission.class);
    Set<Permission> perms = rp == null ? Set.of() : Set.of(rp.value());
    req.setAttribute(AdminContext.ATTR, new AdminContext(TestAdmin.ADMIN_ID, TestAdmin.USERNAME, ip, false, perms, 0L));
    req.setAttribute(TestAdmin.ATTR, Boolean.TRUE);
    return true;
  }

  static String key(HandlerMethod hm) {
    return hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName();
  }

  /** 测试管理员的每个 POST 另记一条（动作名、成功或 HTTP 状态）；审计写失败不影响返回 */
  @Override
  public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
    if (!Boolean.TRUE.equals(req.getAttribute(TestAdmin.ATTR)) || !"POST".equals(req.getMethod())) return;
    try {
      String action = handler instanceof HandlerMethod hm ? key(hm) : req.getRequestURI();
      boolean ok = ex == null && res.getStatus() < 400;
      audit.record("test_admin.request", action, false, ok ? "SUCCESS" : "FAILURE", ok ? null : "HTTP " + res.getStatus(), null, null);
    } catch (RuntimeException e) {
      log.warn("测试管理员请求的审计没写进去", e);
    }
  }

  private String token(HttpServletRequest req) {
    if (req.getCookies() == null) return null;
    for (Cookie c : req.getCookies()) if (props.cookieName().equals(c.getName())) return c.getValue();
    return null;
  }
}
