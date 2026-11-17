package app.trading.payment.api;

/**
 * 提供方付款的状态（Trading 的口径）。客户只看到"等待放款审核 / 放款中 / 已完成 / 已退回"，{@link #UNKNOWN} 客户也看"放款中"。
 * <ul>
 *   <li>{@link #SUBMITTING}：Trading 正在交给提供方，还没拿到付款编号（超时、提供方不通时停在这里，下次用同一个防重键重提）</li>
 *   <li>{@link #PENDING}：提供方收下了，等人工放款审核（TopUP 由 Amos 审核签名，两道审 TD-1 B）</li>
 *   <li>{@link #APPROVED}、{@link #BROADCAST}：放款中</li>
 *   <li>{@link #COMPLETED}：成功，有链上交易哈希</li>
 *   <li>{@link #FAILED}、{@link #REJECTED}、{@link #CANCELLED}：确定没付，Trading 退回客户冻结</li>
 *   <li>{@link #UNKNOWN}：查不清（查询出错、超过防重有效期还没拿到结果）。<b>不能当失败退款</b>（C56），后台标红等人查</li>
 * </ul>
 */
public enum PayoutState {
  SUBMITTING(0), PENDING(1), APPROVED(2), BROADCAST(3), UNKNOWN(3), COMPLETED(9), FAILED(9), REJECTED(9), CANCELLED(9);

  /** 先后顺序：乱序到达的回调只往前走，不往回退（先到"已完成"再到"已批准"时后者忽略） */
  public final int rank;

  PayoutState(int rank) {
    this.rank = rank;
  }

  public boolean terminal() {
    return rank == 9;
  }

  /** 确定没付（可以退款） */
  public boolean notPaid() {
    return this == FAILED || this == REJECTED || this == CANCELLED;
  }
}
