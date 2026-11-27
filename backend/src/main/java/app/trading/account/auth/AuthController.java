package app.trading.account.auth;

import app.trading.account.auth.AuthRequests.ChangePassword;
import app.trading.account.auth.AuthRequests.Login;
import app.trading.account.auth.AuthRequests.Register;
import app.trading.account.auth.AuthRequests.ResetPassword;
import app.trading.account.auth.AuthRequests.SendCode;
import app.trading.account.auth.AuthRequests.SessionView;
import app.trading.account.auth.Rows.UserRow;
import app.trading.common.web.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 客户端的认证接口。管理员登录是另一套（S16）。 */
@RestController
class AuthController {

  private final AuthService auth;
  private final SessionService sessions;
  private final AuthProperties props;
  private final TelegramAccounts telegram;

  AuthController(AuthService auth, SessionService sessions, AuthProperties props, TelegramAccounts telegram) {
    this.telegram = telegram;
    this.auth = auth;
    this.sessions = sessions;
    this.props = props;
  }

  /** 发验证码的结果：还要等多少秒才能重发，客户端按它倒计时（60 秒内重发是 429，错误响应体也带剩余的 cooldownSeconds） */
  record CodeSent(int cooldownSeconds) {}

  @PostMapping("/api/auth/email-code")
  CodeSent sendCode(@Valid @RequestBody SendCode r, HttpServletRequest req) {
    return new CodeSent(auth.sendCode(r.email(), r.purpose(), req.getRemoteAddr()));
  }

  @PostMapping("/api/auth/register")
  SessionView register(@Valid @RequestBody Register r, HttpServletRequest req, HttpServletResponse res) {
    long userId = auth.register(r);
    return startSession(auth.user(userId), req, res);
  }

  @PostMapping("/api/auth/login")
  SessionView login(@Valid @RequestBody Login r, HttpServletRequest req, HttpServletResponse res) {
    return startSession(auth.login(r.email(), r.password(), req.getRemoteAddr()), req, res);
  }

  @PostMapping("/api/auth/logout")
  ResponseEntity<Void> logout(HttpServletRequest req, HttpServletResponse res) {
    sessions.revoke(SessionFilter.token(req, props.cookieName()));
    res.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/api/auth/password/reset")
  ResponseEntity<Void> reset(@Valid @RequestBody ResetPassword r) {
    auth.resetPassword(r.email(), r.code(), r.password());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/api/auth/password/change")
  ResponseEntity<Void> change(@Valid @RequestBody ChangePassword r, HttpServletRequest req) {
    auth.changePassword(CurrentUser.id(), r.currentPassword(), r.newPassword(), SessionFilter.token(req, props.cookieName()));
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/api/me/session")
  SessionView me() {
    UserRow u = auth.user(CurrentUser.id());
    return view(u);
  }

  private SessionView view(UserRow u) {
    return new SessionView(String.valueOf(u.id()), u.email(), telegram.byUserId(u.id()).map(t -> t.username()).orElse(null));
  }

  private SessionView startSession(UserRow u, HttpServletRequest req, HttpServletResponse res) {
    String token = sessions.create(u.id(), req.getRemoteAddr(), req.getHeader(HttpHeaders.USER_AGENT));
    res.addHeader(HttpHeaders.SET_COOKIE, cookie(token, Duration.ofHours(props.sessionTtlHours())));
    return view(u);
  }

  private String cookie(String value, Duration maxAge) {
    return sessionCookie(props, value, maxAge);
  }

  /** HttpOnly：页面脚本读不到令牌；SameSite=Strict：其他网站发起的请求不带上它（防跨站请求伪造） */
  static String sessionCookie(AuthProperties props, String value, Duration maxAge) {
    return ResponseCookie.from(props.cookieName(), value)
        .httpOnly(true).secure(props.cookieSecure()).sameSite("Strict").path("/api").maxAge(maxAge)
        .build().toString();
  }
}
