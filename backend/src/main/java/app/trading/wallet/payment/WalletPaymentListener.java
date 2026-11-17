package app.trading.wallet.payment;

import app.trading.payment.api.PaymentListener;
import app.trading.wallet.deposit.DepositService;
import app.trading.wallet.withdraw.WithdrawService;
import org.springframework.stereotype.Component;

/** 支付接口层的通知（已验签、已按事件 ID 去重）交给充值、提现的业务规则。在收到回调的事务里执行。 */
@Component
class WalletPaymentListener implements PaymentListener {

  private final DepositService deposits;
  private final WithdrawService withdraws;

  WalletPaymentListener(DepositService deposits, WithdrawService withdraws) {
    this.deposits = deposits;
    this.withdraws = withdraws;
  }

  @Override
  public void onDeposit(Deposit d) {
    deposits.credit(d);
  }

  @Override
  public void onDepositReversed(String provider, String ref) {
    deposits.reverseByProviderRef(provider, ref);
  }

  @Override
  public void onPayout(Payout p) {
    withdraws.payoutUpdate(p.withdrawalId(), p.providerRef(), p.state().name(), p.txHash(), p.reason());
  }
}
