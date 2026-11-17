package app.trading.account.auth;

import jakarta.servlet.http.Cookie;
import org.springframework.context.ApplicationContext;

/** 其他包的测试直接给某个客户开一个登录会话（不走注册、发验证码）。只在测试里用。 */
public final class SessionTestHooks {

  private SessionTestHooks() {}

  public static Cookie cookie(ApplicationContext ctx, long userId) {
    String token = ctx.getBean(SessionService.class).create(userId, "127.0.0.1", "test");
    return new Cookie(ctx.getBean(AuthProperties.class).cookieName(), token);
  }
}
