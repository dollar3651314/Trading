package app.trading.account.auth;

import app.trading.common.trace.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 第一阶段只走 Telegram（TB-33，{@code trading.auth.phase1-telegram-only}，默认开）：注册、邮箱登录、发验证码、忘记密码一律 403 {@code TELEGRAM_ONLY}，
 * 请求体是什么都一样（不先做字段校验）。已登录客户的改密码、退出照常。
 */
@Component
class TelegramOnlyFilter extends OncePerRequestFilter {

  static final Set<String> CLOSED = Set.of("/api/auth/register", "/api/auth/login", "/api/auth/email-code", "/api/auth/password/reset");

  private final AuthProperties props;

  TelegramOnlyFilter(AuthProperties props) {
    this.props = props;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    return !props.phase1TelegramOnly() || !CLOSED.contains(req.getRequestURI());
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
    res.setStatus(HttpServletResponse.SC_FORBIDDEN);
    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
    res.setCharacterEncoding(StandardCharsets.UTF_8.name());
    res.getWriter().write("{\"code\":\"TELEGRAM_ONLY\",\"message\":\"现在只能从 Telegram 机器人进入\",\"fields\":[]" + TraceIdFilter.jsonField() + "}");
  }
}
