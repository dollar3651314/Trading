package app.trading.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 支付接口层对钱包模块的 7 个动作（需求 02 第一节）。到账、付款状态由提供方回调，经 {@link PaymentListener} 通知钱包模块。
 * <p>调用提供方的方法都会访问网络，<b>不能在数据库事务里调用</b>（调用方负责）。
 */
public interface PaymentApi {

  /** 提供方连不上、超时、返回 5xx：暂时的，可以重试 */
  class Unavailable extends RuntimeException {
    public Unavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** 1. 开户：在当前提供方建这个客户（外部编号 = Trading 客户号），重复调用返回同一个 */
  void openAccount(long userId);

  /**
   * 2. 取地址：已有就直接给（不访问网络），没有就向当前提供方要（先开户）。同一客户同一条链永远是同一个地址。
   * 这条链被后台关掉时抛 {@code BizException("NETWORK_CLOSED")}；提供方不通时抛 {@link Unavailable}。
   */
  String address(long userId, PayNetwork network);

  /** 已经生成过的地址（不访问网络）；没有时为空 */
  Optional<String> existingAddress(long userId, PayNetwork network);

  /** 后台开着的充值网络（关掉的客户看不到；已发地址的到账照常入账） */
  List<PayNetwork> openNetworks();

  /** 4. 代付的结果 */
  sealed interface Submit permits Accepted, Retry, Refused {}

  /** 提供方收下了（state 一般是 PENDING，也可能已经更靠后） */
  record Accepted(String providerRef, PayoutState state) implements Submit {}

  /** 暂时交不了（提供方不通、超时、余额不够、限流），留着下次用同一个防重键再提；不退款 */
  record Retry(String reason) implements Submit {}

  /** 提供方明确拒收（例如地址不合法）：确定没付，可以退款 */
  record Refused(String reason) implements Submit {}

  /**
   * 4. 代付：把一笔已审核的提现交给提供方。同一笔重复调用只会用同一个防重键（Trading 提现单号），提供方只付一次。
   * 已经交过（有付款编号）时不再提交，直接返回 {@link Accepted}。
   *
   * @param amount 要付到客户地址的金额（提现金额 − 手续费）
   */
  Submit payout(long withdrawalId, long withdrawalNo, long userId, PayNetwork network, String address, BigDecimal amount);

  /**
   * 5. 查付款：先看回调记下的状态，太久没更新时向提供方查一次（回调丢了靠它补）；查不到或出错记 {@link PayoutState#UNKNOWN}，不当失败。
   * 没交过提供方时为空。
   */
  Optional<PayoutState> payoutState(long withdrawalId);

  /** 付款记录（后台出金审核页、客户端显示付款编号和状态） */
  record PayoutInfo(long withdrawalId, String provider, String providerRef, PayoutState state, String txHash, String reason, Instant submittedAt,
      Instant updatedAt) {}

  Optional<PayoutInfo> payoutInfo(long withdrawalId);

  List<PayoutInfo> payoutInfos(List<Long> withdrawalIds);

  /** 后台"重新查询"：马上向提供方查一次，返回查到的状态（会照常通知钱包模块） */
  PayoutState refreshPayout(long withdrawalId);

  /** 6. 查余额：Trading 在当前提供方的可用余额（USDT 总数，不分链：TG-9）。checkedAt 是查到的时间 */
  record Balance(String provider, BigDecimal available, BigDecimal frozen, Instant checkedAt) {}

  Balance balance();

  /** 7. 拉流水（TG-2 前的降级：按单号逐笔查）：提供方那边这个客户的已入账到账 */
  record ProviderDeposit(String ref, String txHash, PayNetwork network, String address, BigDecimal amount, Instant time) {}

  List<ProviderDeposit> depositsOf(long userId, String provider);

  /** 提供方那边一笔付款的记录（对账用） */
  record ProviderPayout(String providerRef, PayoutState state, BigDecimal amount, PayNetwork network, String address, String txHash) {}

  Optional<ProviderPayout> lookupPayout(String provider, String providerRef);
}
