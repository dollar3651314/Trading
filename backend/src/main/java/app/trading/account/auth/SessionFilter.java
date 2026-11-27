package app.trading.account.auth;

import app.trading.common.e2e.E2eGuard;
import app.trading.common.trace.TraceIdFilter;
import app.trading.common.web.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 客户端接口的登录检查：/api/ 下除公开接口和后台接口（/api/admin/，S16 另有一套）外，都要求有效会话。
 * 通过后把用户 ID 放进 {@link CurrentUser}，其他模块从那里读。
 */
@Component
class SessionFilter extends OncePerRequestFilter {

  /** 不需要登录的接口。/api/public/quotes 是官网公开行情（v2，只读，只有几个品种的卖价和涨跌幅，见 market.web.PublicQuoteController）；
   * /api/pay/callback/topup 是 TopUP 的回调（不靠登录，靠验签，见 payment.core.PaymentCallbackController） */
  static final Set<String> PUBLIC = Set.of(
      "/api/health", "/api/auth/email-code", "/api/auth/register", "/api/auth/login", "/api/auth/logout", "/api/auth/password/reset",
      "/api/public/quotes", "/api/pay/callback/topup",
      // TG 机器人第 2 批：从机器人一键登录（login_url 落地页、一次性链接换会话）、登录方式（第一阶段只走 Telegram）
      "/api/tg/login", "/api/tg/link", "/api/auth/options",
      // TG 机器人第 4 批 4c：网页"申请冻结提现"（TG 丢了的人也要能用，不登录；要图形验证码）
      "/api/public/withdraw-freeze/captcha", "/api/public/withdraw-freeze");

  private final SessionService sessions;
  private final AuthProperties props;
  /** 端到端测试模式（profile e2e，S19）：/api/e2e/** 不检查登录。其他配置下这些接口根本不存在，照常要求登录（返回 401） */
  private final boolean e2e;

  SessionFilter(SessionService sessions, AuthProperties props, Environment env) {
    this.sessions = sessions;
    this.props = props;
    this.e2e = env.acceptsProfiles(Profiles.of(E2eGuard.PROFILE));
  }

  static String token(HttpServletRequest req, String cookieName) {
    Cookie[] cookies = req.getCookies();
    if (cookies == null) return null;
    for (Cookie c : cookies) if (cookieName.equals(c.getName())) return c.getValue();
    return null;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    String p = req.getRequestURI();
    return !p.startsWith("/api/") || p.startsWith("/api/admin/") || PUBLIC.contains(p) || (e2e && p.startsWith(E2eGuard.PATH_PREFIX));
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
    Rows.SessionRow s = sessions.resolveSession(token(req, props.cookieName()));
    Long userId = s == null ? null : s.userId();
    if (userId == null) {
      res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      res.setContentType(MediaType.APPLICATION_JSON_VALUE);
      res.setCharacterEncoding(StandardCharsets.UTF_8.name());
      res.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"未登录或登录已失效\",\"fields\":[]" + TraceIdFilter.jsonField() + "}");
      return;
    }
    CurrentUser.set(req, userId);
    CurrentUser.setBotSession(req, "BOT".equals(s.kind()));
    chain.doFilter(req, res);
  }
}
