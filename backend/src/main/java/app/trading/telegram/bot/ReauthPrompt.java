package app.trading.telegram.bot;

import app.trading.account.api.AccountApi;
import app.trading.account.api.SecurityEvent;
import app.trading.account.api.SecurityEventRecorded;
import app.trading.account.api.TelegramAccountApi;
import app.trading.account.api.UserStatus;
import app.trading.notification.api.TelegramSender;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 安全事件后主动发「重新授权」（TG 机器人验收反馈 1a）：以前只有客户给机器人发消息、点按钮时才提示要重新授权；网页把客户送回 Telegram 时，
 * 已经打开过的聊天不会自己冒出新消息，客户就卡住了。现在账户模块记了安全事件（{@link SecurityEventRecorded}），事务提交以后这里做和机器人
 * 收消息时一样的检查（没冻结、授权时间早于最近一次安全事件），要的话发一条带「重新授权」按钮的消息，<b>高优先级</b>。
 * 换绑执行时新 TG 的授权时间在同一个事务里更新到事件之后，所以不会发；冻结的不发（机器人只回"已冻结"）。
 */
@Component
class ReauthPrompt {

  private static final Logger log = LoggerFactory.getLogger(ReauthPrompt.class);

  private final TelegramSender sender;
  private final TelegramAccountApi accounts;
  private final AccountApi account;

  ReauthPrompt(TelegramSender sender, TelegramAccountApi accounts, AccountApi account) {
    this.sender = sender;
    this.accounts = accounts;
    this.account = account;
  }

  static String text(String type) {
    return "你的账户刚有安全变更（" + UpdateHandler.eventName(type) + "）。为了安全，网页已全部退出。确认是你本人后，请点下面的「重新授权」，再点「打开交易」。";
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  void on(SecurityEventRecorded e) {
    try {
      var acc = accounts.byUserId(e.userId());
      if (acc.isEmpty()) return;
      if (account.status(e.userId()) == UserStatus.FROZEN) return;
      Optional<SecurityEvent> ev = account.lastSecurityEventSince(e.userId(), acc.get().authorizedAt());
      if (ev.isEmpty()) return;
      sender.send(e.userId(), text(ev.get().type()), TelegramSender.Priority.HIGH, null,
          List.of(new TelegramSender.Action("重新授权", UpdateHandler.CB_REAUTH)), null);
    } catch (RuntimeException ex) {
      log.warn("给客户 {} 发「重新授权」提示失败：{}", e.userId(), ex.toString());
    }
  }
}
