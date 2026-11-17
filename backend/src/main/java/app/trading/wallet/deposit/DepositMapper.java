package app.trading.wallet.deposit;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 充值记录的 SQL，见 resources/mapper/wallet/DepositMapper.xml。 */
@Mapper
interface DepositMapper {

  /** publicNo：对外编号（雪花 ID，V22 之前的旧数据等于 id）；provider、providerRef：哪个提供方的哪笔到账（第 1 批起） */
  record DepositRow(long id, long publicNo, String chain, String provider, String providerRef, String txHash, int logIndex, long userId, BigDecimal amount,
      String status, int confirmations, java.time.LocalDateTime createdAt) {

    /** 账本流水的业务号：提供方的到账用 提供方:到账编号；自带钱包时代的旧数据用 链:交易:序号 */
    String ledgerKey() {
      return providerRef != null ? DepositService.bizId(provider, providerRef) : chain + ":" + txHash + ":" + logIndex;
    }
  }

  /** 已确认的到账直接记成已入账；同一个 提供方 + 到账编号 已经有了返回 0（不重复入账） */
  int insertCredited(@Param("publicNo") long publicNo, @Param("chain") String chain, @Param("provider") String provider, @Param("providerRef") String providerRef,
      @Param("txHash") String txHash, @Param("to") String to, @Param("userId") long userId, @Param("amount") BigDecimal amount,
      @Param("providerFee") BigDecimal providerFee);

  DepositRow byProviderRef(@Param("provider") String provider, @Param("ref") String ref);

  /** 已入账 → 已冲正；返回 1 表示这次才改（防重复冲正） */
  int markReversed(@Param("id") long id);

  int insertSuspense(@Param("userId") long userId, @Param("depositId") long depositId, @Param("amount") BigDecimal amount, @Param("note") String note);

  List<DepositRow> byUser(@Param("userId") long userId, @Param("limit") int limit);
}
