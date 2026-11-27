package app.trading.wallet.withdraw;

import app.trading.account.api.TelegramAccountApi;
import app.trading.common.config.ConfigKey;
import app.trading.common.config.SystemConfig;
import app.trading.common.error.BizException;
import org.springframework.stereotype.Component;

/**
 * TG 客户的提现闸门（TB-1"TG 提现默认关"，TG 机器人第 2 批审查 🔴B2-1）：系统配置 {@code telegram.withdraw.enabled} 不是 true 时，
 * 有 TG 账户的客户——不管是从机器人一键登录的会话还是网页会话——加白名单、提交提现一律拒绝（{@code TG_WITHDRAW_DISABLED}"Telegram 账户提现暂未开放"）。
 * 第 4 批的提现密码、TG 额度、人工审核做完验收后，由 Amos 在后台打开。读不到配置时按关处理。
 */
@Component
class TelegramWithdrawGate {

  static final String CODE = "TG_WITHDRAW_DISABLED";

  private final TelegramAccountApi telegram;
  private final SystemConfig config;

  TelegramWithdrawGate(TelegramAccountApi telegram, SystemConfig config) {
    this.telegram = telegram;
    this.config = config;
  }

  void check(long userId) {
    if ("true".equalsIgnoreCase(config.text(ConfigKey.TELEGRAM_WITHDRAW_ENABLED, "false").strip())) return;
    if (telegram.byUserId(userId).isPresent()) throw new BizException(CODE, "Telegram 账户提现暂未开放");
  }
}
