package app.trading.wallet.payment;

import app.trading.common.config.ConfigKey;
import app.trading.common.config.SystemConfig;
import app.trading.common.health.AlertCheck;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 暂停把提现交给钱包提供方（系统配置 {@code payment.payout.paused}，生产方案 v1 05 页第 3 节）：服务器上恢复数据
 * （{@code sudo trading restore … --confirm --production}）后自动打开——备份之后已经交给 TopUP 的提现在库里变回"已审核待放款"，
 * 再交一次就会重复付款。打开时 {@link PaymentWithdrawSender} 不交付款（提现停在"已审核待放款"、不退款），健康检查 alerts 出现
 * {@value #ALERT} DOWN（告警邮件、{@code trading status} 都看得到）；人工在 TopUP 后台核对后 {@code sudo trading start-payout --confirm} 关掉。
 */
@Component
class PayoutPause implements AlertCheck {

  static final String ALERT = "payout.paused";
  static final String REASON = "恢复数据后暂停交钱包提供方付款，人工核对后在服务器上 sudo trading start-payout --confirm 再开";

  private final SystemConfig config;

  PayoutPause(SystemConfig config) {
    this.config = config;
  }

  boolean paused() {
    return "true".equals(config.text(ConfigKey.PAYMENT_PAYOUT_PAUSED, "false").strip());
  }

  @Override
  public List<Item> evaluate() {
    if (!paused()) return List.of();
    return List.of(new Item(ALERT, "提现暂停交给钱包提供方（恢复数据后）", "Payouts to the wallet provider paused (after a restore)", false, REASON));
  }
}
