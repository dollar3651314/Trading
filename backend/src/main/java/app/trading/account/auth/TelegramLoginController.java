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
 *   <li>{@code GET /api/auth/options}：网页客户端要不要显示注册、邮箱登录（第一阶段只走 Telegram 时不显示），机器人用户名；
 *       第 5 批：应急开关开着、登录机器人配好时 {@code emergencyLogin=true} 和登录机器人用户名（两种模式都显示「用 Telegram 登录」）。</li>
 *   <li>{@code POST /api/tg/emergency-login}（第 5 批）：应急开关下用 Telegram 登录，请求体是登录小部件给的身份字段；成功写会话 Cookie，
 *       返回什么时候能提现。失败 {@code TG_EMERGENCY_<原因>}。</li>
 * </ul>
 */
@RestController
class TelegramLoginController {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TelegramLoginController.class);

  private final TelegramWebLogin login;
  private final EmergencyLogin emergency;
  private final AuthProperties props;
  private final String botUsername;

  TelegramLoginController(TelegramWebLogin login, EmergencyLogin emergency, AuthProperties props, @Value("${trading.telegram.username:}") String botUsername) {
    this.login = login;
    this.emergency = emergency;
    this.props = props;
    String u = botUsername == null ? "" : botUsername.strip();
    this.botUsername = u.startsWith("@") ? u.substring(1) : u;
  }

  record Options(boolean telegramOnly, String botUsername, String loginMode, boolean emergencyLogin, String loginBotUsername) {}

  @GetMapping("/api/auth/options")
  Options options() {
    boolean em = emergency.available();
    return new Options(props.phase1TelegramOnly(), botUsername.isEmpty() ? null : botUsername, login.linkMode() ? "link" : "login-url", em,
        em ? emergency.botUsername() : null);
  }

  record EmergencyResult(String withdrawAt) {}

  /** 应急开关下用 Telegram 登录（第 5 批）。请求体：登录小部件给的身份字段（数字、文字都按原样转成文字参与验签；不认嵌套） */
  @PostMapping("/api/tg/emergency-login")
  ResponseEntity<EmergencyResult> emergencyLogin(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
    Map<String, String> params = new LinkedHashMap<>();
    if (body != null) {
      for (Map.Entry<String, Object> e : body.entrySet()) {
        Object v = e.getValue();
        if (v == null) continue;
        if (!(v instanceof String || v instanceof Number || v instanceof Boolean)) throw emergencyFailed(EmergencyLogin.Outcome.INVALID);
        params.put(e.getKey(), String.valueOf(v));
      }
    }
    EmergencyLogin.Result r = emergency.login(params, req.getRemoteAddr(), req.getHeader(HttpHeaders.USER_AGENT));
    if (r.outcome() != EmergencyLogin.Outcome.OK) throw emergencyFailed(r.outcome());
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header(HttpHeaders.SET_COOKIE, AuthController.sessionCookie(props, r.token(), Duration.ofHours(props.sessionTtlHours())))
        .body(new EmergencyResult(r.withdrawAt().toInstant(java.time.ZoneOffset.UTC).toString()));
  }

  private static BizException emergencyFailed(EmergencyLogin.Outcome o) {
    return new BizException("TG_EMERGENCY_" + o.name(), "用 Telegram 登录没有成功：" + o.name().toLowerCase(Locale.ROOT));
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
      b.header(HttpHeaders.SET_COOKIE, AuthController.sessionCookie(props, r.token(), props.botSessionTtl()));
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
    TelegramWebLogin.Result r;
    try {
      r = login.link(body == null ? null : body.token(), req.getRemoteAddr(), req.getHeader(HttpHeaders.USER_AGENT), SessionFilter.token(req, props.cookieName()));
    } catch (org.springframework.dao.PessimisticLockingFailureException e) {
      log.warn("一次性链接换会话撞上锁，按过期回答：{}", e.getClass().getSimpleName()); // 只记异常类型，不记令牌
      // #51 审查：同组令牌同时用撞上锁（死锁、等锁超时）——按"链接过期"回答，不回 500（整组已经或马上作废，客户回机器人拿新的）
      r = TelegramWebLogin.Result.fail(TelegramWebLogin.Outcome.EXPIRED);
    }
    if (r.outcome() != TelegramWebLogin.Outcome.OK) {
      throw new BizException("TG_LOGIN_" + r.outcome().name(), "从机器人进入没有成功：" + r.outcome().name().toLowerCase(Locale.ROOT));
    }
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header(HttpHeaders.SET_COOKIE, AuthController.sessionCookie(props, r.token(), props.botSessionTtl()))
        .body(new LinkResult(r.next()));
  }

  /** 提示页地址：/tg?r=原因（客户端 web/client/src/pages/tg/TgHint.tsx 按原因显示文案） */
  static String hint(TelegramWebLogin.Outcome o) {
    return "/tg?r=" + o.name().toLowerCase(Locale.ROOT);
  }
}
