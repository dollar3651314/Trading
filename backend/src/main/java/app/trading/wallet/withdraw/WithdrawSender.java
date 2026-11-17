package app.trading.wallet.withdraw;

import java.math.BigDecimal;

/**
 * 把一笔已审核的提现交出去付款。第 1 批的真实实现是支付接口层（{@code PaymentWithdrawSender}：交给 TopUP，由 Amos 在 TopUP 再审一次后放款）；
 * 测试用替身。结果决定提现单下一步：
 * <ul>
 *   <li>{@link Submitted}：已交给钱包提供方（拿到付款编号），等提供方放款；客户看到"等待放款审核"</li>
 *   <li>{@link Sent}：已广播，等链上确认（自带钱包时代的结果，测试替身仍在用）</li>
 *   <li>{@link Wait}：暂时不能发（例如热钱包余额不够，提现方案 W4），留在"已审核待上链"，下次再试，不退款</li>
 *   <li>{@link Rejected}：确定没有上链（例如节点直接拒绝），失败退款（AC-20）</li>
 * </ul>
 */
public interface WithdrawSender {

  /** 要发的一笔：付 amount − fee 到 address。publicNo 是提现对外编号（交给提供方的防重键、商户单号） */
  record Order(long withdrawalId, long publicNo, long userId, String network, String address, BigDecimal sendAmount) {

    public Order(long withdrawalId, String network, String address, BigDecimal sendAmount) {
      this(withdrawalId, withdrawalId, 0, network, address, sendAmount);
    }
  }

  sealed interface Outcome permits Submitted, Sent, Wait, Rejected {}

  /** 已交给钱包提供方，providerRef 是提供方的付款编号（不是链上交易哈希） */
  record Submitted(String providerRef) implements Outcome {}

  record Sent(String txHash) implements Outcome {}

  record Wait(String reason) implements Outcome {}

  record Rejected(String reason) implements Outcome {}

  Outcome send(Order order);

  /** 上链中的提现查一次链上结果 */
  sealed interface Check permits Confirmed, Pending, Failed {}

  /** 链上成功并且确认数够了；txHash 是最终上链的那一笔（加价重发时可能不是第一笔） */
  record Confirmed(String txHash) implements Check {}

  record Pending() implements Check {}

  /** 确定转账没有发生（链上执行失败、TRON 交易全部过期）：可以退款 */
  record Failed(String reason) implements Check {}

  /** 查链上结果；S13b 的真实实现会顺带处理超时（加价重发、过期重建） */
  default Check check(long withdrawalId) {
    return new Pending();
  }
}
