package app.quote.support;

import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试用固定时钟：2026-10-14（周三）10:00 UTC，外汇、指数、黄金、加密在交易时段，美股休市。
 * 和 trading-app 测试用的是同一个时间（照搬 trading-app 的 support 包）。需要过时间的测试把它当 {@link MutableClock} 往前拨，测完复原。
 */
@TestConfiguration
public class FixedClockConfig {

  public static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");

  @Bean
  @Primary
  Clock fixedClock() {
    return new MutableClock();
  }
}
