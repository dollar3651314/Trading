package app.trading.account.recovery;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 找回申请的表（第 4 批 4b，迁移 V39）。只有 account 模块使用。 */
@Mapper
interface RecoveryMapper {

  /** 一份申请 */
  class Row {
    Long id;
    String kind;
    long userId;
    Long applicantTgId;
    String applicantTgUsername;
    String status;
    boolean disputed;
    LocalDateTime disputedAt;
    boolean disputePinOk;
    boolean disputeFrozen;
    LocalDateTime objectionEndsAt;
    String proofNote;
    Long approvedBy;
    LocalDateTime approvedAt;
    LocalDateTime executeAt;
    String revokeRequestedBy;
    LocalDateTime revokeRequestedAt;
    String closedBy;
    String closeReason;
    LocalDateTime closedAt;
    LocalDateTime createdAt;
  }

  /** 核对申请人填的信息用：资料里的姓名、最近一份 KYC 的状态和证件号 */
  record Identity(String firstName, String middleName, String lastName, String kycStatus, String docNumber) {}

  Identity identity(@Param("userId") long userId);

  int insert(Row r);

  Row byId(@Param("id") long id);

  Row lock(@Param("id") long id);

  List<Row> list(@Param("status") String status);

  List<Row> ofUser(@Param("userId") long userId);

  /** 这个客户进行中的申请数（没结束的） */
  int openCount(@Param("userId") long userId);

  /** 这个客户从 since 起提交过的申请数（同一种） */
  int countSince(@Param("userId") long userId, @Param("kind") String kind, @Param("since") LocalDateTime since);

  int insertAttempt(@Param("tgId") long tgId, @Param("at") LocalDateTime at);

  int countAttemptsSince(@Param("tgId") long tgId, @Param("since") LocalDateTime since);

  int insertBan(@Param("kind") String kind, @Param("value") String value, @Param("until") LocalDateTime until, @Param("recoveryId") long recoveryId,
      @Param("at") LocalDateTime at);

  int banned(@Param("kind") String kind, @Param("value") String value, @Param("now") LocalDateTime now);

  int insertEvent(@Param("recoveryId") long recoveryId, @Param("action") String action, @Param("actor") String actor, @Param("note") String note,
      @Param("at") LocalDateTime at);

  List<app.trading.account.api.RecoveryApi.Event> events(@Param("recoveryId") long recoveryId);

  /** 状态 from → to（带条件更新，防并发） */
  int transit(@Param("id") long id, @Param("from") String from, @Param("to") String to);

  int markDisputed(@Param("id") long id, @Param("at") LocalDateTime at);

  int markDisputePinOk(@Param("id") long id);

  int markDisputeFrozen(@Param("id") long id);

  int approve(@Param("id") long id, @Param("adminId") long adminId, @Param("note") String note, @Param("at") LocalDateTime at,
      @Param("executeAt") LocalDateTime executeAt);

  int requestRevoke(@Param("id") long id, @Param("by") String by, @Param("at") LocalDateTime at);

  int clearRevoke(@Param("id") long id);

  int close(@Param("id") long id, @Param("from") String from, @Param("to") String to, @Param("by") String by, @Param("reason") String reason,
      @Param("at") LocalDateTime at);

  /** 反对期到了、没人反对的（REBIND） */
  List<Long> objectionDue(@Param("now") LocalDateTime now);

  /** 点了"不是我"、过了 since 还没输对提现密码、还没冻结的 */
  List<Long> disputeUnfrozen(@Param("before") LocalDateTime before);

  /** 到执行时间、没有待决定的撤销请求的 */
  List<Long> executeDue(@Param("now") LocalDateTime now);
}
