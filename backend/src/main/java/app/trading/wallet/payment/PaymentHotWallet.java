package app.trading.wallet.payment;

import app.trading.payment.api.PaymentApi;
import app.trading.wallet.api.HotWalletApi;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 付款余额（第 1 批，替换第 0 批的 NoHotWallet）：Trading 在当前钱包提供方的可用余额（USDT 总数，不分链；按链余额等 TG-9）。
 * 链写 {@value #ALL}：提现自动审批 R12 改成"提供方可用余额 − 已通过未付合计 ≥ 本笔"（TD-12）。余额由支付接口层每 30 秒查一次，这里只读查好的值；
 * 还没查到或最近一次查询失败时 balance 为空，R12 判"查不到"、转人工。
 */
@Component
class PaymentHotWallet implements HotWalletApi {

  /** 不分链的总余额 */
  static final String ALL = "ALL";

  private final PaymentApi payments;

  PaymentHotWallet(PaymentApi payments) {
    this.payments = payments;
  }

  @Override
  public List<Balance> hotBalances() {
    PaymentApi.Balance b = payments.balance();
    if (b.available() == null) return List.of(new Balance(ALL, "USDT", false, b.provider(), null, BigDecimal.ZERO, false, null, "还没有查到钱包提供方的余额"));
    return List.of(new Balance(ALL, "USDT", false, b.provider(), b.available(), BigDecimal.ZERO, false, b.checkedAt(), null));
  }
}
