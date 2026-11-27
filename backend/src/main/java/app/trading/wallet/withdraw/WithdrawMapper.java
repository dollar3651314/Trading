package app.trading.wallet.withdraw;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 白名单、提现单、提现事件的 SQL，见 resources/mapper/wallet/WithdrawMapper.xml。 */
@Mapper
interface WithdrawMapper {

  long completedCount(@Param("userId") long userId);

  int markFirstChecked(@Param("id") long id, @Param("adminId") long adminId);

  java.util.List<WhitelistRow> whitelistToNotifySoon(@Param("now") java.time.LocalDateTime now, @Param("soon") java.time.LocalDateTime soon);

  java.util.List<WhitelistRow> whitelistToNotifyActive(@Param("now") java.time.LocalDateTime now, @Param("since") java.time.LocalDateTime since);

  int markWhitelistSoon(@Param("id") long id, @Param("now") java.time.LocalDateTime now);

  int markWhitelistActive(@Param("id") long id, @Param("now") java.time.LocalDateTime now);

  // ---------- 白名单 ----------
  List<WhitelistRow> whitelist(@Param("userId") long userId);

  WhitelistRow whitelistById(@Param("userId") long userId, @Param("id") long id);

  int countWhitelist(@Param("userId") long userId);

  int insertWhitelist(WhitelistRow row);

  int deleteWhitelist(@Param("userId") long userId, @Param("id") long id);

  /** 第 4 批 4b：找回申请执行时清空这个客户的白名单 */
  int deleteAllWhitelist(@Param("userId") long userId);

  // ---------- 提现单 ----------
  int insertWithdrawal(WithdrawalRow row);

  WithdrawalRow lock(@Param("id") long id);

  WithdrawalRow byId(@Param("id") long id);

  /** 客户自己的提现：对外编号 → 内部 id；没有时为 null */
  Long idByNo(@Param("userId") long userId, @Param("no") long no);

  /** 对外编号 → 内部 id（不限客户：后台、端到端测试替身用）；没有时为 null */
  Long idByPublicNo(@Param("no") long no);

  List<WithdrawalRow> byUser(@Param("userId") long userId, @Param("offset") int offset, @Param("limit") int limit);

  long countByUser(@Param("userId") long userId);

  /** 当天（from 起）还算数的提现合计：撤销、驳回、失败退回的不算 */
  BigDecimal usedSince(@Param("userId") long userId, @Param("from") LocalDateTime from);

  /** 状态从 from 改到 to；返回 1 表示改成功（状态在条件里，防止并发重复处理） */
  int transit(@Param("id") long id, @Param("from") String from, @Param("to") String to);

  /** 人工审核（通过或驳回）：同时记 review_mode = MANUAL（v2 自动审批，驳回也记，方便统计） */
  int setReview(@Param("id") long id, @Param("reviewerId") Long reviewerId, @Param("at") LocalDateTime at, @Param("reason") String reason,
      @Param("delayEndsAt") LocalDateTime delayEndsAt);

  int setTx(@Param("id") long id, @Param("txHash") String txHash);

  int setCompleted(@Param("id") long id, @Param("at") LocalDateTime at);

  List<Long> due(@Param("status") String status, @Param("timeColumn") String timeColumn, @Param("now") LocalDateTime now);

  List<Long> withStatus(@Param("status") String status);

  /** 第 4 批 4b（审查 🟡1）：交钱包提供方前认领（待放款、没暂停才成功，send_attempts + 1）；返回 1 才能交出去 */
  int claimSend(@Param("id") long id);

  /** 冻结时暂停已审核待放款的（之后解除冻结也不自动继续，要后台逐笔选"继续放款"或"取消并退回"） */
  int hold(@Param("id") long id, @Param("reason") String reason, @Param("now") LocalDateTime now);

  /** 后台选"继续放款"：去掉暂停 */
  int release(@Param("id") long id);

  /** 这个客户暂停中的已审核待放款提现 */
  List<WithdrawalRow> heldOf(@Param("userId") long userId);

  /** 第 4 批审查 🔴4a-1：这个客户在这些状态里的提现（冻结时处理在途的） */
  List<Long> idsOfUserIn(@Param("userId") long userId, @Param("statuses") List<String> statuses);

  /** 一笔提现的一次状态变化（客户端资金记录里每一步的时间） */
  record EventRow(long withdrawalId, String toStatus, LocalDateTime at) {}

  List<EventRow> eventsOf(@Param("ids") List<Long> withdrawalIds);

  int insertEvent(@Param("withdrawalId") long withdrawalId, @Param("from") String from, @Param("to") String to, @Param("actor") String actor,
      @Param("note") String note);
}
