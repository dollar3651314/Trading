package app.trading.telegram.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import app.trading.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 机器人启动和告警（假 Telegram，不启动 Spring）：令牌和用户名对不上不启动；轮询收到 409 立即告警；令牌错（401）告警；
 * 整个过程（含失败）日志里都不出现令牌。
 */
@ExtendWith(OutputCaptureExtension.class)
class TelegramBotStartTest {

  static final String TOKEN = "987654:START-test-token-not-real";

  static TelegramProperties props(String apiUrl, String username) {
    return new TelegramProperties(TOKEN, username, apiUrl, null, Duration.ofSeconds(1), Duration.ofMillis(10), 25, 100, Duration.ofMinutes(10), 20, "login-url");
  }

  record Started(TelegramBot bot, TelegramHealth health) {}

  static Started start(TelegramProperties p) {
    TelegramHealth health = new TelegramHealth(p, new MutableClock());
    TelegramBot bot = new TelegramBot(p, health);
    bot.wire(new TelegramOutbox(bot, p), mock(UpdateHandler.class));
    bot.start();
    return new Started(bot, health);
  }

  static void await(java.util.function.BooleanSupplier c) throws InterruptedException {
    long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!c.getAsBoolean() && System.nanoTime() < end) Thread.sleep(20);
  }

  @Test
  void 令牌属于别的机器人_不启动_告警(CapturedOutput out) throws Exception {
    try (FakeTelegram tg = new FakeTelegram(TOKEN, "TradingAssistBot")) {
      Started s = start(props(tg.url(), "TradingAssistTestBot"));
      await(() -> !s.health().evaluate().getFirst().ok());
      try {
        assertThat(s.health().evaluate().getFirst().detail()).contains("@TradingAssistBot").contains("TradingAssistTestBot");
        Thread.sleep(300);
        assertThat(tg.calls("getUpdates")).as("没有开始轮询").isEmpty();
        assertThat(s.bot().polling()).isFalse();
      } finally {
        s.bot().stop();
      }
    }
    assertThat(out.getAll()).doesNotContain(TOKEN);
  }

  @Test
  void 轮询收到409立即告警_令牌可能泄露(CapturedOutput out) throws Exception {
    try (FakeTelegram tg = new FakeTelegram(TOKEN, "TradingAssistTestBot")) {
      tg.failNext("getUpdates", 409, 3);
      Started s = start(props(tg.url(), "@TradingAssistTestBot"));
      try {
        await(() -> !tg.calls("getUpdates").isEmpty() && !s.health().evaluate().getFirst().ok());
        assertThat(s.bot().polling()).isTrue();
        assertThat(s.health().evaluate().getFirst().ok()).isFalse();
        assertThat(s.health().evaluate().getFirst().detail()).contains("409").contains("令牌可能泄露");
      } finally {
        s.bot().stop();
      }
    }
    assertThat(out.getAll()).doesNotContain(TOKEN);
  }

  @Test
  void 令牌不对_401_告警_不启动(CapturedOutput out) throws Exception {
    try (FakeTelegram tg = new FakeTelegram("111:other-token", "TradingAssistTestBot")) {
      Started s = start(props(tg.url(), "TradingAssistTestBot"));
      try {
        await(() -> !s.health().evaluate().getFirst().ok());
        assertThat(s.health().evaluate().getFirst().detail()).contains("401");
        assertThat(s.bot().polling()).isFalse();
      } finally {
        s.bot().stop();
      }
    }
    assertThat(out.getAll()).doesNotContain(TOKEN);
  }

  @Test
  void 连不上Telegram_日志里没有令牌(CapturedOutput out) throws Exception {
    // 端口 1：连接被拒绝；1 分钟后才重试，这里只看第一次
    Started s = start(props("http://127.0.0.1:1", "TradingAssistTestBot"));
    try {
      await(() -> out.getAll().contains("连不上 Telegram"));
      assertThat(out.getAll()).contains("连不上 Telegram").doesNotContain(TOKEN);
      assertThat(s.health().evaluate().getFirst().ok()).as("10 分钟内不告警").isTrue();
    } finally {
      s.bot().stop();
    }
  }
}
