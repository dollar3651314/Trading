package app.trading.telegram.bot;

import static org.assertj.core.api.Assertions.assertThat;

import app.trading.common.health.AlertCheck;
import app.trading.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 机器人告警：409 立即告警（令牌可能泄露）、连续失败 10 分钟才告警、成功就恢复、没配令牌不检查、详情里没有令牌。 */
class TelegramHealthTest {

  final MutableClock clock = new MutableClock();
  final TelegramProperties props = TelegramOutboxTest.props(Duration.ofSeconds(1), 10);

  AlertCheck.Item item(TelegramHealth h) {
    return h.evaluate().getFirst();
  }

  @Test
  void 没配令牌不检查() {
    TelegramProperties none = new TelegramProperties("", "x", "https://api.telegram.org", null, Duration.ofSeconds(50), Duration.ofSeconds(1), 25, 10,
        Duration.ofMinutes(10), 20);
    assertThat(new TelegramHealth(none, clock).evaluate()).isEmpty();
  }

  @Test
  void 收到409立即告警_10分钟内没再出现才恢复() {
    TelegramHealth h = new TelegramHealth(props, clock);
    assertThat(item(h).ok()).isTrue();
    h.conflict();
    assertThat(item(h).ok()).isFalse();
    assertThat(item(h).name()).isEqualTo("telegram");
    assertThat(item(h).detail()).contains("409").contains("令牌可能泄露");
    h.ok();
    clock.advance(Duration.ofMinutes(9));
    assertThat(item(h).ok()).as("刚出现过 409，成功一次也不算恢复").isFalse();
    clock.advance(Duration.ofMinutes(2));
    assertThat(item(h).ok()).isTrue();
  }

  @Test
  void 连续失败10分钟才告警_成功一次就恢复_详情里没有令牌() {
    TelegramHealth h = new TelegramHealth(props, clock);
    h.failed("connect timed out bot" + TelegramOutboxTest.TOKEN);
    clock.advance(Duration.ofMinutes(9));
    h.failed("again");
    assertThat(item(h).ok()).isTrue();
    clock.advance(Duration.ofMinutes(1));
    h.failed("timeout https://api.telegram.org/bot" + TelegramOutboxTest.TOKEN + "/getUpdates");
    assertThat(item(h).ok()).isFalse();
    assertThat(item(h).detail()).contains("10 分钟").doesNotContain(TelegramOutboxTest.TOKEN);
    h.ok();
    assertThat(item(h).ok()).isTrue();
  }

  @Test
  void 令牌错或用户名对不上一直告警() {
    TelegramHealth h = new TelegramHealth(props, clock);
    h.fatal("机器人令牌属于 @Other，和配置的不一致");
    h.ok();
    assertThat(item(h).ok()).isFalse();
  }
}
