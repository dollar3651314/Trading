package app.trading.wallet.payment;

import app.trading.common.config.ConfigKey;
import app.trading.common.config.SystemConfig;
import app.trading.common.health.AlertCheck;
import app.trading.payment.api.PaymentApi;
import app.trading.wallet.api.HotWalletApi;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 付款余额（第 1 批，替换第 0 批的 NoHotWallet）：Trading 在当前钱包提供方的可用余额（USDT 总数，不分链；按链余额等 TG-9）。
 * 链写 {@value #ALL}：提现自动审批 R12 改成"提供方可用余额 − 已通过未付合计 ≥ 本笔"（TD-12）。余额由支付接口层每 30 秒查一次，这里只读查好的值；
 * 还没查到或最近一次查询失败时 balance 为空，R12 判"查不到"、转人工。
 * <p><b>余额告警</b>（生产方案 v1 07 页 R-5）：下限是系统配置 {@code payment.balance.minUsdt}（后台"系统配置"改，0 = 不告警）。
 * 余额低于下限时健康检查的 alerts 出现 {@value #ALERT} DOWN，告警邮件和恢复邮件由通知模块的 AlertMonitor 发（同一项 30 分钟内不重复）。
 * 下限是 0 或者还没查到余额时不检查（提供方接口不通另有 {@code wallet.topup} 告警）。
 */
@Component
class PaymentHotWallet implements HotWalletApi, AlertCheck {

  /** 不分链的总余额 */
  static final String ALL = "ALL";
  /** 健康检查 alerts 里的名字 */
  static final String ALERT = "wallet.balance";

  private final PaymentApi payments;
  private final SystemConfig config;

  PaymentHotWallet(PaymentApi payments, SystemConfig config) {
    this.payments = payments;
    this.config = config;
  }

  /** 告警下限；读不出来或负数按 0（不告警） */
  BigDecimal threshold() {
    BigDecimal t = config.decimal(ConfigKey.PAYMENT_BALANCE_MIN_USDT, BigDecimal.ZERO);
    return t.signum() > 0 ? t : BigDecimal.ZERO;
  }

  @Override
  public List<Balance> hotBalances() {
    PaymentApi.Balance b = payments.balance();
    BigDecimal t = threshold();
    if (b.available() == null) return List.of(new Balance(ALL, "USDT", false, b.provider(), null, t, false, null, "还没有查到钱包提供方的余额"));
    return List.of(new Balance(ALL, "USDT", false, b.provider(), b.available(), t, low(b.available(), t), b.checkedAt(), null));
  }

  private static boolean low(BigDecimal available, BigDecimal threshold) {
    return threshold.signum() > 0 && available.compareTo(threshold) < 0;
  }

  @Override
  public List<Item> evaluate() {
    BigDecimal t = threshold();
    PaymentApi.Balance b = payments.balance();
    if (t.signum() <= 0 || b.available() == null) return List.of();
    boolean ok = !low(b.available(), t);
    String detail = "Trading 在钱包提供方 " + b.provider() + " 的可用余额 " + b.available().stripTrailingZeros().toPlainString() + " USDT，告警下限 "
        + t.stripTrailingZeros().toPlainString() + " USDT（后台 → 系统配置 → payment.balance.minUsdt）";
    return List.of(new Item(ALERT, "钱包提供方可用余额低于告警下限", "Wallet provider balance below the alert threshold", ok, detail));
  }
}
