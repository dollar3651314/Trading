package app.trading.wallet.withdraw;

import static org.assertj.core.api.Assertions.assertThat;

import app.trading.common.config.ConfigKey;
import app.trading.common.config.SystemConfig;
import app.trading.support.FixedClockConfig;
import app.trading.support.PinTestSupport;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 4c 审查 🟡：资金配置读的时候也按 TB-37 下限 / 上限夹住——有人绕过后台直接把库里的冷静期、白名单生效改成 0、每天累计改大，
 * 提现照样按冷静期 ≥ 2 小时、白名单生效 ≥ 24 小时、每天累计 ≤ 30,000、TG 每天额度 ≤ 2,000 算。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class WithdrawLimitsFloorTest {

  @Autowired WithdrawLimits limits;
  @Autowired TelegramWithdrawGate gate;
  @Autowired SystemConfig config;
  @Autowired JdbcTemplate jdbc;

  @AfterEach
  void restore() {
    config.setBySystem(ConfigKey.WITHDRAW_COOLING_HOURS, "2", LocalDateTime.now());
    config.setBySystem(ConfigKey.WHITELIST_EFFECTIVE_HOURS, "24", LocalDateTime.now());
    config.setBySystem(ConfigKey.WITHDRAW_DAILY_LIMIT, "30000", LocalDateTime.now());
    config.setBySystem(ConfigKey.TELEGRAM_WITHDRAW_DAILY_LIMIT, "500", LocalDateTime.now());
  }

  @Test
  void 绕过后台改库_读的时候照样按下限上限() {
    config.setBySystem(ConfigKey.WITHDRAW_COOLING_HOURS, "0", LocalDateTime.now());
    config.setBySystem(ConfigKey.WHITELIST_EFFECTIVE_HOURS, "1", LocalDateTime.now());
    config.setBySystem(ConfigKey.WITHDRAW_DAILY_LIMIT, "999999", LocalDateTime.now());
    config.setBySystem(ConfigKey.TELEGRAM_WITHDRAW_DAILY_LIMIT, "50000", LocalDateTime.now());
    WithdrawProperties p = limits.current();
    assertThat(p.cooling()).isEqualTo(Duration.ofHours(2));
    assertThat(p.whitelistDelay()).isEqualTo(Duration.ofHours(24));
    assertThat(p.dailyLimit()).isEqualByComparingTo("30000");
    long uid = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 + FLOOR(RAND() * 1000) FROM account_user", Long.class);
    jdbc.update("INSERT INTO account_user (id, email, status) VALUES (?, NULL, 'ACTIVE')", uid);
    PinTestSupport.makeTg(jdbc, uid);
    assertThat(gate.context(uid).dailyQuota()).isEqualByComparingTo("2000");
    // 在范围内的值照用
    config.setBySystem(ConfigKey.WITHDRAW_COOLING_HOURS, "5", LocalDateTime.now());
    assertThat(limits.current().cooling()).isEqualTo(Duration.ofHours(5));
  }
}
