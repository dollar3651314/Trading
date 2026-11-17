package app.trading.payment.core;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 支付接口层的表（pay_*）和它在 common_config 里的几项设置。SQL 见 resources/mapper/payment/PaymentMapper.xml。 */
@Mapper
interface PaymentMapper {

  // ---------- 设置 ----------
  String config(@Param("key") String key);

  int saveConfig(@Param("key") String key, @Param("value") String value);

  // ---------- 地址 ----------
  record AddressRow(long userId, String network, String provider, String customerId, String address) {}

  AddressRow address(@Param("userId") long userId, @Param("network") String network);

  AddressRow addressByValue(@Param("network") String network, @Param("address") String address);

  int insertAddress(@Param("userId") long userId, @Param("network") String network, @Param("provider") String provider, @Param("customerId") String customerId,
      @Param("address") String address);

  // ---------- 付款 ----------
  class PayoutRow {
    Long id;
    long withdrawalId;
    long userId;
    String provider;
    String idemKey;
    String merchantRef;
    String network;
    String address;
    BigDecimal amount;
    String providerRef;
    String status;
    String txHash;
    String reason;
    LocalDateTime firstSubmitAt;
    LocalDateTime lastSubmitAt;
    int submitCount;
    LocalDateTime lastQueryAt;
    LocalDateTime updatedAt;
  }

  PayoutRow payout(@Param("withdrawalId") long withdrawalId);

  PayoutRow lockPayout(@Param("withdrawalId") long withdrawalId);

  List<PayoutRow> payouts(@Param("ids") List<Long> withdrawalIds);

  PayoutRow payoutByRef(@Param("provider") String provider, @Param("ref") String ref);

  PayoutRow payoutByMerchantRef(@Param("provider") String provider, @Param("merchantRef") String merchantRef);

  int insertPayout(PayoutRow row);

  int submitted(@Param("id") long id, @Param("at") LocalDateTime at);

  /** at：这次变化的时间（业务时钟，判断"多久没更新"用） */
  int savePayout(@Param("id") long id, @Param("providerRef") String providerRef, @Param("status") String status, @Param("txHash") String txHash,
      @Param("reason") String reason, @Param("at") LocalDateTime at);

  int queried(@Param("id") long id, @Param("at") LocalDateTime at);

  // ---------- 回调 ----------
  int insertCallback(@Param("provider") String provider, @Param("eventId") String eventId, @Param("type") String type, @Param("ref") String ref);

  LocalDateTime lastCallback(@Param("provider") String provider);

  // ---------- 审计 ----------
  int insertAudit(@Param("adminId") long adminId, @Param("action") String action, @Param("detail") String detail, @Param("reason") String reason);

  record AuditRow(long id, long adminId, String action, String detail, String reason, LocalDateTime createdAt) {}

  List<AuditRow> audits(@Param("limit") int limit);
}
