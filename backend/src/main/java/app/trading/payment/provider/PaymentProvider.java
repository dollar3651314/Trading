package app.trading.payment.provider;

import app.trading.payment.api.PayNetwork;
import app.trading.payment.api.PayoutState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 一家钱包提供方的适配（只有 payment 模块里用）。所有方法都访问网络：连不上、超时、5xx 抛 {@link app.trading.payment.api.PaymentApi.Unavailable}，
 * 明确的业务错误抛 {@link ProviderError}。
 */
public interface PaymentProvider {

  /** 代码：TOPUP / COBO / FIREBLOCKS / 测试替身自己定 */
  String code();

  /** 显示名 */
  String name();

  /** 已接入：有实现并且配置齐了 */
  boolean connected();

  /** 提供方返回的明确错误（不是网络问题），code 是提供方的错误码 */
  class ProviderError extends RuntimeException {
    public final int status;
    public final String code;

    public ProviderError(int status, String code) {
      super(status + " " + code);
      this.status = status;
      this.code = code;
    }
  }

  /**
   * 商户单号已经被另一笔付款用过（TopUP v1.1 起商户单号永久唯一，409 duplicate_merchant_ref，带原来那笔的付款编号）。
   * 一般是 Trading 自己早先那次提交（返回丢了、防重键又过了 24 小时），由调用方核对后认领
   */
  class DuplicateRef extends ProviderError {
    public final String existingRef;

    public DuplicateRef(int status, String existingRef) {
      super(status, "duplicate_merchant_ref");
      this.existingRef = existingRef;
    }
  }

  void ensureCustomer(String customerId);

  /** 这个客户在这条链的地址：没有就生成。返回地址 */
  String address(String customerId, PayNetwork network);

  /** 提交代付（同一个防重键重复调用只付一次）。返回付款编号和状态 */
  record Submitted(String ref, PayoutState state) {}

  Submitted submitPayout(String idemKey, String merchantRef, String customerId, PayNetwork network, String address, BigDecimal amount);

  /** 一笔付款现在的样子；提供方说没有这笔时为空 */
  record PayoutView(String ref, String merchantRef, PayoutState state, BigDecimal amount, PayNetwork network, String address, String txHash, String reason) {}

  Optional<PayoutView> payout(String ref);

  record BalanceView(BigDecimal available, BigDecimal frozen) {}

  BalanceView balance();

  /** 这个客户的已入账到账（按单号逐笔核对用；TG-2 之前没有按时间段的接口） */
  record DepositView(String ref, String customerId, PayNetwork network, String address, String txHash, BigDecimal amount, BigDecimal fee, Instant time) {}

  List<DepositView> deposits(String customerId);

  /** 回调里的一个事件（已验签） */
  sealed interface Event permits DepositEvent, PayoutEvent, ReversalEvent {
    String eventId();
  }

  record DepositEvent(String eventId, String customerId, PayNetwork network, String address, String txHash, BigDecimal amount, BigDecimal fee) implements Event {}

  /** amount、network、address：回调里这笔付款的金额、链、收款地址（只按商户单号对上时核对用，见 PaymentService.payoutEvent）；没有时为空 */
  record PayoutEvent(String eventId, String ref, String merchantRef, PayoutState state, String txHash, String reason, BigDecimal amount, PayNetwork network,
      String address) implements Event {}

  /** 到账冲正（TopUP 还没有，TG-11；测试替身用） */
  record ReversalEvent(String eventId, String depositRef) implements Event {}

  /** 验签不通过、时间戳过期 */
  class BadSignature extends RuntimeException {
    public BadSignature(String message) {
      super(message);
    }
  }

  /**
   * 验签并解析回调。headers 的名字是小写。不认识的事件类型返回空（照样回 200，不让提供方一直重试）。
   *
   * @throws BadSignature 签名不对、缺头、时间戳超过 5 分钟
   */
  Optional<Event> parseCallback(Map<String, String> headers, String body);
}
