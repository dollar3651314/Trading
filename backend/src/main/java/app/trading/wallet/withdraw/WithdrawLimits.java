package app.trading.wallet.withdraw;

import app.trading.common.config.ConfigFloors;
import app.trading.common.config.ConfigKey;
import app.trading.common.config.SystemConfig;
import app.trading.wallet.Chain;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 提现参数的当前值：后台系统配置优先（后台方案 G7），没有时用 application.yml（{@link WithdrawProperties}）。
 * 4c 审查 🟡：读的时候也按 TB-37 的下限 / 上限夹住（{@link ConfigFloors}：冷静期 ≥ 2 小时、白名单生效 ≥ 24 小时、每天累计 ≤ 30,000），
 * 有人绕过后台直接改库也不会低于下限；端到端测试（profile e2e）不夹
 */
@Component
class WithdrawLimits {

  private final SystemConfig config;
  private final WithdrawProperties defaults;
  private final boolean floors;

  WithdrawLimits(SystemConfig config, WithdrawProperties defaults, org.springframework.core.env.Environment env) {
    this.config = config;
    this.defaults = defaults;
    this.floors = !env.acceptsProfiles(org.springframework.core.env.Profiles.of(app.trading.common.e2e.E2eGuard.PROFILE));
  }

  private BigDecimal clamp(ConfigKey k, BigDecimal v) {
    return floors ? ConfigFloors.clamp(k, v) : v;
  }

  private java.time.Duration clamp(ConfigKey k, java.time.Duration v) {
    return floors ? ConfigFloors.clamp(k, v) : v;
  }

  WithdrawProperties current() {
    Map<Chain, BigDecimal> fees = new EnumMap<>(Chain.class);
    fees.put(Chain.ERC20, config.decimal(ConfigKey.WITHDRAW_FEE_ERC20, defaults.fee(Chain.ERC20)));
    fees.put(Chain.TRC20, config.decimal(ConfigKey.WITHDRAW_FEE_TRC20, defaults.fee(Chain.TRC20)));
    fees.put(Chain.BSC, config.decimal(ConfigKey.WITHDRAW_FEE_BSC, defaults.fee(Chain.BSC)));
    return new WithdrawProperties(
        config.decimal(ConfigKey.WITHDRAW_MIN_AMOUNT, defaults.minAmount()),
        config.decimal(ConfigKey.WITHDRAW_MAX_SINGLE, defaults.maxPerTx()),
        clamp(ConfigKey.WITHDRAW_DAILY_LIMIT, config.decimal(ConfigKey.WITHDRAW_DAILY_LIMIT, defaults.dailyLimit())),
        config.decimal(ConfigKey.WITHDRAW_LARGE_THRESHOLD, defaults.largeAmount()),
        config.hours(ConfigKey.WITHDRAW_LARGE_DELAY_HOURS, defaults.largeDelay()),
        clamp(ConfigKey.WITHDRAW_COOLING_HOURS, config.hours(ConfigKey.WITHDRAW_COOLING_HOURS, defaults.cooling())),
        clamp(ConfigKey.WHITELIST_EFFECTIVE_HOURS, config.hours(ConfigKey.WHITELIST_EFFECTIVE_HOURS, defaults.whitelistDelay())),
        config.integer(ConfigKey.WHITELIST_MAX_ADDRESSES, defaults.maxWhitelist()),
        fees);
  }
}
