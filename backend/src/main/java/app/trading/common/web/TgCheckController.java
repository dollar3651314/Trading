package app.trading.common.web;

import app.trading.common.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Telegram 内置浏览器识别诊断（11/28 Amos 要求：网页只想在 Telegram 里用，先查清楚 iOS / 安卓上 Telegram 的内置浏览器认不认得出）：
 * {@code GET /api/public/tg-check}，<b>不需要登录</b>，网页 {@code /tg-check} 调它看"服务器这边看到的请求头"。
 * <ul>
 *   <li><b>只回这几个请求头的原样</b>：User-Agent、Sec-CH-UA、Sec-CH-UA-Mobile、Sec-CH-UA-Platform、X-Requested-With（没有的为 null）；
 *       不回 Cookie、IP、会话，也不读它们；</li>
 *   <li><b>什么都不存</b>：不写库、不写 Redis（限流计数除外，1 分钟过期）、不打日志；</li>
 *   <li>限流：同一 IP 每分钟最多 {@link #PER_MINUTE} 次（另外还有通用的按 IP 限流和 Nginx 的 /api/ 限流）；{@code Cache-Control: no-store}。</li>
 * </ul>
 */
@RestController
class TgCheckController {

  static final int PER_MINUTE = 30;
  static final String[] HEADERS = {"User-Agent", "Sec-CH-UA", "Sec-CH-UA-Mobile", "Sec-CH-UA-Platform", "X-Requested-With"};

  private final RateLimiter limiter;

  TgCheckController(RateLimiter limiter) {
    this.limiter = limiter;
  }

  @GetMapping("/api/public/tg-check")
  ResponseEntity<Map<String, String>> check(HttpServletRequest req) {
    limiter.check("tgcheck", req.getRemoteAddr(), PER_MINUTE, Duration.ofMinutes(1));
    Map<String, String> out = new LinkedHashMap<>();
    for (String h : HEADERS) {
      String v = req.getHeader(h);
      out.put(h, v == null ? null : v.length() > 1000 ? v.substring(0, 1000) : v);
    }
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
  }
}
