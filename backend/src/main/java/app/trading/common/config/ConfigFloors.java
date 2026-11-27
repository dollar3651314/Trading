package app.trading.common.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/**
 * TB-37 资金配置的下限和上限（第 4 批 4c）：白名单生效 ≥ 24 小时、冷静期 ≥ 2 小时、每天提现累计 ≤ 30,000、TG 每天额度 ≤ 2,000。
 * 两处都用：后台保存时拒绝越界的值（{@code admin.system.SystemService}），读的时候也按 max(配置, 下限) / min(配置, 上限) 取
 * （4c 审查 🟡：防止有人绕过后台直接改库）。端到端测试（profile e2e，E2eGuard 保证只在本机和 CI）不受限制：主流程要把冷静期、生效时间设成 0。
 */
public final class ConfigFloors {

  public static final Map<ConfigKey, BigDecimal> FLOORS = Map.of(ConfigKey.WHITELIST_EFFECTIVE_HOURS, BigDecimal.valueOf(24),
      ConfigKey.WITHDRAW_COOLING_HOURS, BigDecimal.valueOf(2));
  public static final Map<ConfigKey, BigDecimal> CEILINGS = Map.of(ConfigKey.WITHDRAW_DAILY_LIMIT, BigDecimal.valueOf(30000),
      ConfigKey.TELEGRAM_WITHDRAW_DAILY_LIMIT, BigDecimal.valueOf(2000), ConfigKey.TELEGRAM_WITHDRAW_DAILY_LIMIT_EMAIL, BigDecimal.valueOf(2000));

  private ConfigFloors() {}

  /** 按下限、上限夹住（没有限制的原样返回） */
  public static BigDecimal clamp(ConfigKey k, BigDecimal v) {
    if (v == null) return null;
    BigDecimal floor = FLOORS.get(k);
    if (floor != null && v.compareTo(floor) < 0) return floor;
    BigDecimal ceiling = CEILINGS.get(k);
    if (ceiling != null && v.compareTo(ceiling) > 0) return ceiling;
    return v;
  }

  /** 小时数的下限 */
  public static Duration clamp(ConfigKey k, Duration v) {
    BigDecimal floor = FLOORS.get(k);
    if (v == null || floor == null) return v;
    Duration f = Duration.ofHours(floor.longValueExact());
    return v.compareTo(f) < 0 ? f : v;
  }
}
