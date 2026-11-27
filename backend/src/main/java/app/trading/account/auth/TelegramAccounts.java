package app.trading.account.auth;

import app.trading.account.api.TelegramAccountApi;
import app.trading.notification.api.NotifyApi;
import app.trading.trading.api.LedgerApi;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link TelegramAccountApi} 的实现：机器人授权开户（TG 机器人第 1 批）。 */
@Service
class TelegramAccounts implements TelegramAccountApi {

  private final AuthMapper mapper;
  private final LedgerApi ledger;
  private final NotifyApi notify;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final org.springframework.beans.factory.ObjectProvider<TelegramWebLogin> webLogin;
  private final SessionService sessions;

  TelegramAccounts(AuthMapper mapper, LedgerApi ledger, NotifyApi notify, Clock clock, PlatformTransactionManager txm,
      org.springframework.beans.factory.ObjectProvider<TelegramWebLogin> webLogin, SessionService sessions) {
    this.webLogin = webLogin;
    this.sessions = sessions;
    this.mapper = mapper;
    this.ledger = ledger;
    this.notify = notify;
    this.clock = clock;
    this.tx = new TransactionTemplate(txm);
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
  }

  @Override
  public Optional<TgAccount> byTelegramId(long telegramId) {
    return Optional.ofNullable(mapper.tgByTelegramId(telegramId));
  }

  @Override
  public Optional<TgAccount> byUserId(long userId) {
    return Optional.ofNullable(mapper.tgByUserId(userId));
  }

  @Override
  public TgAccount openAccount(long telegramId, String username) {
    Optional<TgAccount> had = byTelegramId(telegramId);
    if (had.isPresent()) return had.get();
    try {
      return tx.execute(s -> {
        AuthMapper.UserInsert u = new AuthMapper.UserInsert(null, null);
        mapper.insertUser(u);
        LocalDateTime now = now();
        mapper.insertTelegram(u.id, telegramId, cut(username), "SIGNUP", now);
        ledger.openAccount(u.id);
        // 欢迎站内信和网页注册一样（模板 WELCOME 只发站内信）
        notify.toUser(u.id, "WELCOME", Map.of());
        return new TgAccount(u.id, telegramId, cut(username), now);
      });
    } catch (DuplicateKeyException e) {
      // 同一个 TG 编号同时点了两次：唯一键兜底，整个事务回滚（客户、交易账户都不留），返回先开好的那个
      return byTelegramId(telegramId).orElseThrow(() -> e);
    }
  }

  @Override
  public void rebind(long userId, long newTelegramId, String username, LocalDateTime at) {
    if (mapper.rebindTelegram(userId, newTelegramId, cut(username), at) != 1) throw new IllegalStateException("客户 " + userId + " 没有 TG 账户");
  }

  @Override
  public void unlink(long userId) {
    mapper.deleteTelegram(userId);
  }

  @Override
  public void link(long userId, long telegramId, String username, LocalDateTime at) {
    mapper.insertTelegram(userId, telegramId, cut(username), "ADMIN", at);
  }

  @Override
  public void reauthorize(long userId) {
    mapper.updateTgAuthorizedAt(userId, now());
  }

  @Override
  public void updateUsername(long userId, String username) {
    mapper.updateTgUsername(userId, cut(username));
  }

  @Override
  public String createLoginLink(long userId, String next) {
    return webLogin.getObject().createLink(userId, next);
  }

  @Override
  public void logoutAllWeb(long userId) {
    sessions.revokeAll(userId, null);
  }

  private static String cut(String username) {
    if (username == null || username.isBlank()) return null;
    return username.length() > 64 ? username.substring(0, 64) : username;
  }
}
