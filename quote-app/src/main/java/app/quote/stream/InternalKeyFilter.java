package app.quote.stream;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * /internal/** 的接口（含 WebSocket 握手）都要内部 Key；不对返回 401，不说明原因。
 * 按规范化以后的路径判断（第 3 批审查：{@code /internal;x/…}、{@code /%69nternal/…} 不能绕过）；畸形路径先被 {@code PathGuardFilter} 拒成 400。
 */
@Component
class InternalKeyFilter extends OncePerRequestFilter {

  private final InternalKey key;

  InternalKeyFilter(InternalKey key) {
    this.key = key;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    String p = app.quote.web.PathGuardFilter.path(req);
    return !p.startsWith("/internal/") && !p.equals("/internal") && !app.quote.web.PathGuardFilter.malformed(req.getRequestURI());
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
    if (!key.matches(req.getHeader(InternalKey.HEADER))) {
      res.setStatus(401);
      res.setContentType("application/json");
      res.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"需要内部 Key\"}");
      return;
    }
    chain.doFilter(req, res);
  }
}
