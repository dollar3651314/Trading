package app.quote.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * 路径守卫（第 3 批审查 🔴）：最先执行，路径里有 {@code ;}（矩阵参数）、{@code %}（任何编码，含编码的斜杠和字母）、反斜杠、{@code //}、
 * {@code .} / {@code ..} 段的请求一律 400。否则 {@code /internal;x/…}、{@code /%69nternal/…} 这类写法会被 Spring 路由到内部接口，
 * 却骗过按前缀判断的鉴权过滤器（内部 Key、对外签名）。本服务的正常路径都不含这些字符。
 * 鉴权过滤器另外按规范化以后的路径判断（{@link #path}），两层都挡。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PathGuardFilter extends OncePerRequestFilter {

  private static final Pattern BAD = Pattern.compile("[;%\\\\]|//|/\\.\\.?(/|$)");
  private static final UrlPathHelper HELPER = new UrlPathHelper();

  /** 规范化以后的路径（解码、去掉 ; 后面的内容、去掉上下文路径），鉴权过滤器用它判断 */
  public static String path(HttpServletRequest req) {
    return HELPER.getPathWithinApplication(req);
  }

  /** 原始路径是不是畸形 */
  public static boolean malformed(String rawUri) {
    return rawUri == null || BAD.matcher(rawUri).find();
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
    if (malformed(req.getRequestURI())) {
      res.setStatus(400);
      res.setContentType("application/json;charset=UTF-8");
      res.getWriter().write("{\"code\":\"BAD_PATH\",\"message\":\"路径不合法\"}");
      return;
    }
    chain.doFilter(req, res);
  }
}
