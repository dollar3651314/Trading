package app.trading.wallet.freeze;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 网页"申请冻结提现"的提交记录（第 4 批 4c，迁移 V40 的 wallet_freeze_request）和核对用的最近一笔充值。 */
@Mapper
interface FreezeRequestMapper {

  int insert(@Param("target") Long target, @Param("ip") String ip, @Param("fp") String fingerprint, @Param("result") String result,
      @Param("at") LocalDateTime at);

  /** 这个 IP 从 since 起的提交数（result 为空时是全部） */
  int countByIp(@Param("ip") String ip, @Param("result") String result, @Param("since") LocalDateTime since);

  int countByFingerprint(@Param("fp") String fingerprint, @Param("result") String result, @Param("since") LocalDateTime since);

  int countByTarget(@Param("target") long target, @Param("result") String result, @Param("since") LocalDateTime since);

  /** 全平台从 since 起成功冻结的数量 */
  int countFrozenSince(@Param("since") LocalDateTime since);

  /** 最近一笔已到账的充值（没做 KYC 的客户核对用）：金额和到账时间 */
  record LastDeposit(BigDecimal amount, LocalDateTime creditedAt) {}

  LastDeposit lastDeposit(@Param("userId") long userId);
}
