package app.trading.wallet.withdraw;

import app.trading.account.api.AccountApi;
import app.trading.account.api.SecurityEvent;
import app.trading.account.api.TelegramAccountApi;
import app.trading.account.api.WithdrawPinApi;
import app.trading.common.config.ConfigKey;
import app.trading.common.config.SystemConfig;
import app.trading.common.error.BizException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * TG 客户的提现规则（TG 机器人第 2 批审查 🔴B2-1 的闸门 + 第 4 批的规则；需求文档 TG机器人-03 页第 0、3、4 节，决定清单 TB-1、TB-3、TB-5、TB-20、TB-21、TB-22）。
 * "TG 客户" = 有 TG 账户的客户（第一阶段只走 Telegram，所有客户都是）；不管从机器人进入的会话还是网页会话，规则都一样：
 * <ul>
 *   <li>总开关 {@code telegram.withdraw.enabled} 不是 true 时，加白名单、提交提现一律拒绝（TB-1）；</li>
 *   <li>加白名单、提现都要<b>提现密码</b>（随机键盘）；冻结提现时都不行；</li>
 *   <li>提现：开户满 24 小时；<b>24 小时内有任何安全事件、48 小时内重设过提现密码都不能提</b>（统一拦在提交这一步）；</li>
 *   <li>每天额度（网页和机器人合并算，在锁账户的同一个事务里检查）：没邮箱 500、有邮箱 2,000（后台可改，上限 2,000）；</li>
 *   <li>一律人工审核（不走自动审批）；没邮箱的首笔标"人工核实"。</li>
 * </ul>
 */
@Component
class TelegramWithdrawGate {

  static final String CODE = "TG_WITHDRAW_DISABLED";
  static final Duration HOLD = Duration.ofHours(24);
  static final Duration RESET_HOLD = Duration.ofHours(48);
  static final Duration MIN_AGE = Duration.ofHours(24);
  static final BigDecimal DEFAULT_QUOTA = new BigDecimal("500");
  static final BigDecimal DEFAULT_QUOTA_EMAIL = new BigDecimal("2000");
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

  /** 这个 TG 客户的规则：有没有邮箱、今天的额度 */
  record TgContext(boolean hasEmail, BigDecimal dailyQuota) {}

  private final TelegramAccountApi telegram;
  private final AccountApi account;
  private final WithdrawPinApi pins;
  private final SystemConfig config;

  TelegramWithdrawGate(TelegramAccountApi telegram, AccountApi account, WithdrawPinApi pins, SystemConfig config) {
    this.telegram = telegram;
    this.account = account;
    this.pins = pins;
    this.config = config;
  }

  boolean isTg(long userId) {
    return telegram.byUserId(userId).isPresent();
  }

  /** TB-1 总开关：TG 客户在开关打开之前一律不能加白名单、提现。读不到配置按关处理 */
  void check(long userId) {
    if ("true".equalsIgnoreCase(config.text(ConfigKey.TELEGRAM_WITHDRAW_ENABLED, "false").strip())) return;
    if (isTg(userId)) throw new BizException(CODE, "Telegram 账户提现暂未开放");
  }

  TgContext context(long userId) {
    boolean email = account.email(userId).isPresent();
    BigDecimal q = config.decimal(email ? ConfigKey.TELEGRAM_WITHDRAW_DAILY_LIMIT_EMAIL : ConfigKey.TELEGRAM_WITHDRAW_DAILY_LIMIT,
        email ? DEFAULT_QUOTA_EMAIL : DEFAULT_QUOTA);
    return new TgContext(email, q);
  }

  /** 提现密码（冻结提现、没设、锁定、输错都在这里抛） */
  void verifyPin(long userId, WithdrawPinApi.PinInput pin) {
    pins.verify(userId, pin);
  }

  /**
   * 现在不能提现时抛 {@code WITHDRAW_HOLD}（写明到什么时候）：开户不满 24 小时、24 小时内有安全事件、48 小时内重设过提现密码。
   * 返回最早能提的时间（现在就能提时为空），给提现页显示
   */
  LocalDateTime holdUntil(long userId, LocalDateTime now) {
    LocalDateTime until = null;
    LocalDateTime age = account.registeredAt(userId).plus(MIN_AGE);
    if (age.isAfter(now)) until = age;
    List<SecurityEvent> events = account.securityEventsSince(userId, now.minus(RESET_HOLD));
    for (SecurityEvent e : events) {
      // 重设提现密码、人工换绑（第 4 批 4b）48 小时，其他安全事件 24 小时
      LocalDateTime end = e.at().plus(SecurityEvent.PIN_RESET.equals(e.type()) || SecurityEvent.REBIND.equals(e.type()) ? RESET_HOLD : HOLD);
      if (end.isAfter(now) && (until == null || end.isAfter(until))) until = end;
    }
    return until;
  }

  void checkHold(long userId, LocalDateTime now) {
    LocalDateTime until = holdUntil(userId, now);
    if (until != null) throw new BizException("WITHDRAW_HOLD", "为了安全，" + until.format(TIME) + " 以后才能提现（开户满 24 小时、安全变更后 24 小时、重设提现密码后 48 小时）");
  }
}
