package app.trading.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/** 后台"钱包提供方"页（需求 02 第五节、原型 P7）。 */
public interface PaymentAdminApi {

  /**
   * 一家提供方。
   *
   * @param connected 已接入（有实现、配置齐了）；Cobo、Fireblocks 是 false（"未接入"）
   * @param current 现在新地址用它
   * @param up 接口通不通（只对已接入的有意义）
   * @param lastOkAt 最后一次调用成功的时间
   * @param lastCallbackAt 最近一次收到它的回调
   * @param available 可用余额（查不到为空）
   */
  record Provider(String code, String name, boolean connected, boolean current, boolean up, Instant lastOkAt, Instant lastCallbackAt, BigDecimal available,
      Instant balanceAt, String error) {}

  List<Provider> providers();

  /** 网络开关：全平台设置，关掉的客户看不到；已发地址照常入账 */
  record NetworkSwitch(PayNetwork network, boolean open) {}

  List<NetworkSwitch> networks();

  /** 打开或关闭某条网络；原因必填，写审计 */
  void setNetwork(PayNetwork network, boolean open, long adminId, String reason);

  /** 切换当前提供方：新地址用新的；已发地址继续入账、进行中的付款由原提供方付完。原因必填，写审计 */
  void switchProvider(String code, long adminId, String reason);

  record Audit(long id, long adminId, String action, String detail, String reason, LocalDateTime createdAt) {}

  List<Audit> audits(int limit);

  /** 马上检查一次当前提供方的接口（查一次余额） */
  Provider check();
}
