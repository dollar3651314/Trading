package app.trading.payment.api;

import java.math.BigDecimal;

/**
 * 提供方通知（回调）交给钱包模块处理：钱包模块实现这个接口。在收到回调的同一个事务里调用，抛异常时回调返回失败、提供方会重试。
 * 去重已经在支付接口层做过（同一个事件 ID 只调用一次），实现里仍然要按自己的唯一键防重复入账。
 */
public interface PaymentListener {

  /**
   * 一笔已确认的到账。
   *
   * @param provider 提供方代码（TOPUP …）
   * @param ref 提供方的到账编号（入账去重用）
   * @param userId Trading 客户号
   * @param amount 客户转入的金额（入账这么多）
   * @param providerFee 提供方收 Trading 的手续费（平台成本）
   */
  record Deposit(String provider, String ref, long userId, PayNetwork network, String address, String txHash, BigDecimal amount, BigDecimal providerFee) {}

  void onDeposit(Deposit d);

  /** 到账被链上回滚（冲正）。TopUP 还没有这个通知（TG-11），先只给测试替身用（AC-W3） */
  void onDepositReversed(String provider, String ref);

  /** 一笔付款的状态变了（只会往前走，见 {@link PayoutState#rank}） */
  record Payout(long withdrawalId, String provider, String providerRef, PayoutState state, String txHash, String reason) {}

  void onPayout(Payout p);
}
