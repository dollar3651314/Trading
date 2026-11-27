package app.trading.account.auth;

import app.trading.account.api.SecurityEvent;
import app.trading.account.api.UserStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 给 account 模块其他包（例如 {@code AccountFacade}）用的登录相关操作，auth 包的内部类不对外。 */
@Component
public class AuthAccess {

  private final AuthService auth;

  AuthAccess(AuthService auth) {
    this.auth = auth;
  }

  public void freeze(long userId) {
    auth.freeze(userId);
  }

  public void unfreeze(long userId) {
    auth.unfreeze(userId);
  }

  public UserStatus status(long userId) {
    return auth.status(userId);
  }

  public Optional<String> email(long userId) {
    return Optional.ofNullable(auth.user(userId).email());
  }

  public LocalDateTime registeredAt(long userId) {
    return auth.registeredAt(userId);
  }

  public Optional<SecurityEvent> lastSecurityEventSince(long userId, LocalDateTime since) {
    return auth.lastSecurityEventSince(userId, since);
  }

  public java.util.List<SecurityEvent> securityEventsSince(long userId, LocalDateTime since) {
    return auth.securityEventsSince(userId, since);
  }

  /**
   * 记一条安全事件，并作废这个账户的<b>全部</b>网页会话（包括从机器人一键登录的 BOT 会话；第 2 批备忘）。
   * KYC 等 account 模块其他包用（第 4 批：TG 客户第一次提交 KYC）
   */
  public void securityEvent(long userId, String type) {
    auth.securityEventAndLogout(userId, type);
  }
}
