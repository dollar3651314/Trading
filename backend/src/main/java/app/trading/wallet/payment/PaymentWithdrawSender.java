package app.trading.wallet.payment;

import app.trading.payment.api.PayNetwork;
import app.trading.payment.api.PaymentApi;
import app.trading.payment.api.PayoutState;
import app.trading.wallet.Chain;
import app.trading.wallet.withdraw.WithdrawSender;
import app.trading.wallet.withdraw.WithdrawSenders;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 提现付款交给支付接口层（第 1 批）：审核通过（大额延迟期结束）后由提现定时任务调用（每 30 秒；人工通过时立即调用），1 分钟内交给 TopUP；
 * TopUP 收下后提现单进入"放款中"（客户看到"等待放款审核"，要等 Amos 在 TopUP 再审一次）。
 * <ul>
 *   <li>提供方不通、超时、余额不够：留在"已审核待放款"，下次用同一个防重键重提（不退款）</li>
 *   <li>提供方明确拒收（地址不合法）：失败退回</li>
 *   <li>查结果：成功 → 已完成；失败 / 驳回 / 取消 → 退回；未知 → 继续等（不退款）</li>
 * </ul>
 * 启动时给三条链都注册（修复第 0 批"没有发送器，提现一直停在待上链"）。
 */
@Component
class PaymentWithdrawSender implements WithdrawSender {

  private final PaymentApi payments;
  private final WithdrawSenders senders;

  PaymentWithdrawSender(PaymentApi payments, WithdrawSenders senders) {
    this.payments = payments;
    this.senders = senders;
  }

  @EventListener(ApplicationReadyEvent.class)
  void register() {
    for (Chain c : Chain.values()) senders.register(c, this);
  }

  @Override
  public Outcome send(Order o) {
    return switch (payments.payout(o.withdrawalId(), o.publicNo(), o.userId(), PayNetwork.valueOf(o.network()), o.address(), o.sendAmount())) {
      case PaymentApi.Accepted a -> new Submitted(a.providerRef());
      case PaymentApi.Retry r -> new Wait(r.reason());
      case PaymentApi.Refused r -> new Rejected(r.reason());
    };
  }

  @Override
  public Check check(long withdrawalId) {
    PayoutState st = payments.payoutState(withdrawalId).orElse(null);
    if (st == null) return new Pending();
    if (st == PayoutState.COMPLETED) return new Confirmed(payments.payoutInfo(withdrawalId).map(PaymentApi.PayoutInfo::txHash).orElse(null));
    if (st.notPaid()) return new Failed("钱包提供方" + switch (st) {
      case REJECTED -> "驳回放款";
      case CANCELLED -> "取消放款";
      default -> "放款失败";
    });
    return new Pending();
  }
}
