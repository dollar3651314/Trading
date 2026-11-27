package app.trading.account.auth;

import app.trading.common.error.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 从机器人进入网页（TG 机器人第 2 批）和登录方式：
 * <ul>
 *   <li>{@code GET /api/tg/login}：login_url 按钮的落地页。验过后写会话 Cookie，<b>302 到不带参数的干净网址</b>；失败 302 到提示页 {@code /tg?r=原因}。
 *       不缓存、不带 Referer。<b>不记录网址参数</b>（Nginx 对这个路径用不记参数的日志格式）。</li>
 *   <li>{@code POST /api/tg/link}：一次性链接（退路）换会话，令牌在请求体里（网页从网址 # 后面读出来，GET 打开页面不消耗令牌）。</li>
 *   <li>{@code GET /api/auth/options}：网页客户端要不要显示注册、邮箱登录（第一阶段只走 Telegram 时不显示），机器人用户名。</li>
 * </ul>
 */
@RestController
class TelegramLoginController {

  private final TelegramWebLogin login;
  private final AuthProperties props;
  private final String botUsername;

  TelegramLoginController(TelegramWebLogin login, AuthProperties props, @Value("${trading.telegram.username:}") String botUsername) {
    this.login = login;
    this.props = props;
    String u = botUsername == null ? "" : botUsername.strip();
    this.botUsername = u.startsWith("@") ? u.substring(1) : u;
  }

  record Options(boolean telegramOnly, String botUsername, String loginMode) {}

  @GetMapping("/api/auth/options")
  Options options() {
    return new Options(props.phase1TelegramOnly(), botUsername.isEmpty() ? null : botUsername, login.linkMode() ? "link" : "login-url");
  }

  @GetMapping("/api/tg/login")
  ResponseEntity<Void> loginUrl(HttpServletRequest req) {
    Map<String, String> params = new LinkedHashMap<>();
    req.getParameterMap().forEach((k, v) -> {
      if (v != null && v.length > 0) params.put(k, v[0]);
    });
    TelegramWebLogin.Result r = login.loginUrl(params, req.getRemoteAddr(), req.getHeader(HttpHeaders.USER_AGENT));
    ResponseEntity.BodyBuilder b = ResponseEntity.status(HttpStatus.FOUND)
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header("Referrer-Policy", "no-referrer");
    if (r.outcome() == TelegramWebLogin.Outcome.OK) {
      b.header(HttpHeaders.SET_COOKIE, AuthController.sessionCookie(props, r.token(), Duration.ofHours(props.botSessionMaxHours())));
      b.header(HttpHeaders.LOCATION, r.next());
    } else {
      b.header(HttpHeaders.LOCATION, hint(r.outcome()));
    }
    return b.build();
  }

  record LinkRequest(String token) {}

  record LinkResult(String next) {}

  @PostMapping("/api/tg/link")
  ResponseEntity<LinkResult> link(@RequestBody(required = false) LinkRequest body, HttpServletRequest req) {
    TelegramWebLogin.Result r = login.link(body == null ? null : body.token(), req.getRemoteAddr(), req.getHeader(HttpHeaders.USER_AGENT));
    if (r.outcome() != TelegramWebLogin.Outcome.OK) {
      throw new BizException("TG_LOGIN_" + r.outcome().name(), "从机器人进入没有成功：" + r.outcome().name().toLowerCase(Locale.ROOT));
    }
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header(HttpHeaders.SET_COOKIE, AuthController.sessionCookie(props, r.token(), Duration.ofHours(props.botSessionMaxHours())))
        .body(new LinkResult(r.next()));
  }

  /** 提示页地址：/tg?r=原因（客户端 web/client/src/pages/tg/TgHint.tsx 按原因显示文案） */
  static String hint(TelegramWebLogin.Outcome o) {
    return "/tg?r=" + o.name().toLowerCase(Locale.ROOT);
  }
}
