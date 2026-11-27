package app.trading.telegram.bot;

import app.trading.account.api.AccountApi;
import app.trading.account.api.TelegramAccountApi;
import app.trading.common.config.SystemConfig;
import app.trading.common.ratelimit.RateLimiter;
import app.trading.notification.api.TelegramPrefsApi;
import app.trading.notification.api.TelegramSender;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

/**
 * 机器人的组装。没配令牌时只有 {@link TelegramHealth}（不检查）和 {@link TelegramSender}（返回 BOT_OFF），不建 HTTP 客户端和线程。
 */
@Configuration
@EnableConfigurationProperties(TelegramProperties.class)
class TelegramSetup {

  @Bean
  TelegramHealth telegramHealth(TelegramProperties props, Clock clock) {
    return new TelegramHealth(props, clock);
  }

  /** 配了令牌才有 */
  @Bean
  TelegramBotHolder telegramBotHolder(TelegramProperties props, TelegramHealth health, TelegramAccountApi accounts, AccountApi account,
      ObjectProvider<TelegramPrefsApi> prefs, SystemConfig config, RateLimiter limiter, app.trading.account.api.RecoveryApi recovery,
      app.trading.account.api.WithdrawPinApi pins) {
    if (!props.configured()) return new TelegramBotHolder(null, null);
    TelegramBot bot = new TelegramBot(props, health);
    TelegramOutbox outbox = new TelegramOutbox(bot, props);
    // 通知模块（TelegramPrefsApi）按需取：通知模块也按需取本模块的 TelegramSender，避免构造时互相依赖
    TelegramPrefsApi lazyPrefs = new TelegramPrefsApi() {
      @Override
      public java.util.Map<app.trading.notification.api.TelegramCategory, Boolean> telegramPrefs(long userId) {
        return prefs.getObject().telegramPrefs(userId);
      }

      @Override
      public void setTelegramPref(long userId, app.trading.notification.api.TelegramCategory category, boolean enabled) {
        prefs.getObject().setTelegramPref(userId, category, enabled);
      }
    };
    bot.wire(outbox, new UpdateHandler(accounts, account, lazyPrefs, config, limiter, props, outbox, bot, recovery, pins));
    return new TelegramBotHolder(bot, outbox);
  }

  @Bean
  TelegramSender telegramSender(TelegramProperties props, TelegramAccountApi accounts, SystemConfig config, TelegramBotHolder holder) {
    return new TelegramSenderImpl(props, accounts, config, holder.outbox());
  }

  /** 机器人和发送队列（没配令牌时都为空） */
  record TelegramBotHolder(TelegramBot bot, TelegramOutbox outbox) {

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
      if (bot != null) bot.start();
    }

    @EventListener(ContextClosedEvent.class)
    public void stop() {
      if (bot != null) bot.stop();
    }
  }
}
