package app.trading.common.web;

import app.trading.common.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 方案 B 引导页的匿名计数：{@code POST /api/public/tg-gate-hit}，<b>不需要登录</b>，请求体只有 {@code {"platform":"IOS|ANDROID|OTHER"}}。
 * 不读 Cookie、不记 IP 和 UA（计数见 {@link TgGateCounter}）；同一 IP 每分钟最多 {@link #PER_MINUTE} 次（限流计数 1 分钟过期，和全站按 IP 限流同一套），回 204。
 */
@RestController
class TgGateController {

  static final int PER_MINUTE = 20;

  record Hit(String platform) {}

  private final TgGateCounter counter;
  private final RateLimiter limiter;

  TgGateController(TgGateCounter counter, RateLimiter limiter) {
    this.counter = counter;
    this.limiter = limiter;
  }

  @PostMapping("/api/public/tg-gate-hit")
  ResponseEntity<Void> hit(@RequestBody(required = false) Hit body, HttpServletRequest req) {
    limiter.check("tggate", req.getRemoteAddr(), PER_MINUTE, Duration.ofMinutes(1));
    counter.hit(body == null ? null : body.platform());
    return ResponseEntity.noContent().build();
  }
}
