package app.trading.account.auth;

import app.trading.account.auth.Rows.EmailCodeRow;
import app.trading.account.auth.Rows.Profile;
import app.trading.account.auth.Rows.SessionRow;
import app.trading.account.auth.Rows.UserRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 账户模块的 SQL，见 resources/mapper/account/AuthMapper.xml。 */
@Mapper
interface AuthMapper {

  UserRow findUserByEmail(@Param("email") String email);

  UserRow findUserById(@Param("id") long id);

  int insertUser(UserInsert user);

  int insertProfile(Profile profile);

  int updatePassword(@Param("id") long id, @Param("passwordHash") String passwordHash);

  int updateStatus(@Param("id") long id, @Param("status") String status);

  int touchLogin(@Param("id") long id);

  // ---------- 从机器人进入网页（TG 机器人第 2 批） ----------
  /** 一次性链接 */
  record LinkToken(long userId, String nextPath) {}

  int insertLoginHash(@Param("hash") String hash, @Param("at") java.time.LocalDateTime at);

  int deleteLoginHashesBefore(@Param("before") java.time.LocalDateTime before);

  int insertLinkToken(@Param("tokenHash") String tokenHash, @Param("userId") long userId, @Param("next") String next,
      @Param("expiresAt") java.time.LocalDateTime expiresAt, @Param("now") java.time.LocalDateTime now);

  /** 原子地标成已用：没用过、没过期才改，返回影响行数 */
  int useLinkToken(@Param("tokenHash") String tokenHash, @Param("now") java.time.LocalDateTime now);

  LinkToken linkToken(@Param("tokenHash") String tokenHash);

  int deleteLinkTokensBefore(@Param("before") java.time.LocalDateTime before);

  // ---------- Telegram 账户（TG 机器人第 1 批） ----------
  app.trading.account.api.TelegramAccountApi.TgAccount tgByTelegramId(@Param("telegramId") long telegramId);

  app.trading.account.api.TelegramAccountApi.TgAccount tgByUserId(@Param("userId") long userId);

  int insertTelegram(@Param("userId") long userId, @Param("telegramId") long telegramId, @Param("username") String username, @Param("source") String source,
      @Param("at") java.time.LocalDateTime at);

  int updateTgAuthorizedAt(@Param("userId") long userId, @Param("at") java.time.LocalDateTime at);

  int updateTgUsername(@Param("userId") long userId, @Param("username") String username);

  // ---------- 账号安全事件（v2 提现自动审批 R6） ----------
  int insertSecurityEvent(@Param("userId") long userId, @Param("type") String type, @Param("at") java.time.LocalDateTime at);

  /** (since, 现在] 里最近的一条；没有时为 null */
  app.trading.account.api.SecurityEvent lastSecurityEventSince(@Param("userId") long userId, @Param("since") java.time.LocalDateTime since);

  /** (since, 现在] 里的全部安全事件，最新的在前 */
  java.util.List<app.trading.account.api.SecurityEvent> securityEventsSince(@Param("userId") long userId, @Param("since") java.time.LocalDateTime since);

  // ---------- 提现密码（TG 机器人第 4 批） ----------
  record PinRow(long userId, String pinHash, int failedCount, java.time.LocalDateTime lockedUntil) {}

  PinRow lockPin(@Param("userId") long userId);

  PinRow pin(@Param("userId") long userId);

  int insertPin(@Param("userId") long userId, @Param("hash") String hash, @Param("now") java.time.LocalDateTime now);

  int updatePinHash(@Param("userId") long userId, @Param("hash") String hash, @Param("now") java.time.LocalDateTime now);

  int updatePinFailures(@Param("userId") long userId, @Param("failed") int failed, @Param("lockedUntil") java.time.LocalDateTime lockedUntil,
      @Param("now") java.time.LocalDateTime now);

  int insertPinLock(@Param("userId") long userId, @Param("at") java.time.LocalDateTime at);

  int countPinLocksSince(@Param("userId") long userId, @Param("since") java.time.LocalDateTime since);

  record WithdrawFreezeRow(long userId, String source, String reason, java.time.LocalDateTime createdAt, java.time.LocalDateTime releaseAt) {}

  /** 第 4 批 4c：BOT_SELF 自助解冻排上时间 */
  int setFreezeRelease(@Param("userId") long userId, @Param("source") String source, @Param("at") java.time.LocalDateTime at);

  /** 到时间该自助解冻的客户 */
  java.util.List<Long> dueSelfUnfreezes(@Param("now") java.time.LocalDateTime now);

  int deleteDueSelfUnfreeze(@Param("userId") long userId, @Param("now") java.time.LocalDateTime now);

  /** 第 4 批 4c：后台解除 Telegram 关联 */
  int deleteTelegram(@Param("userId") long userId);

  /** 4b 审查 🔴R2：这个客户全部来源的提现冻结（一个来源一行；空 = 没冻结），最早的在前 */
  java.util.List<WithdrawFreezeRow> withdrawFreezes(@Param("userId") long userId);

  /** 加一个来源的冻结（同一来源已有时不变，返回 0） */
  int insertWithdrawFreeze(@Param("userId") long userId, @Param("source") String source, @Param("reason") String reason, @Param("at") java.time.LocalDateTime at);

  /** 解除全部来源的冻结 */
  int deleteWithdrawFreeze(@Param("userId") long userId);

  /** 第 4 批 4b：只解除指定来源的冻结 */
  int deleteWithdrawFreezeOf(@Param("userId") long userId, @Param("source") String source);

  /** 第 4 批 4b：清掉提现密码（换绑、人工重设执行时） */
  int deletePin(@Param("userId") long userId);

  /** 第 4 批 4b：换绑——这个客户的 TG 账户改成新的 TG 用户编号，授权时间改成 at */
  int rebindTelegram(@Param("userId") long userId, @Param("telegramId") long telegramId, @Param("username") String username, @Param("at") java.time.LocalDateTime at);

  /** 注册时间；用户不存在时为 null */
  java.time.LocalDateTime registeredAt(@Param("id") long id);

  int insertEmailCode(@Param("email") String email, @Param("purpose") String purpose, @Param("codeHash") String codeHash, @Param("ttlMinutes") int ttlMinutes);

  /** 发验证码时用，不加锁（见 XML 注释，压测 LT-1） */
  EmailCodeRow latestEmailCodeForSend(@Param("email") String email, @Param("purpose") String purpose);

  EmailCodeRow latestEmailCode(@Param("email") String email, @Param("purpose") String purpose);

  /** 最近 hours 小时内给这个邮箱发过几次这种用途的验证码 */
  int countEmailCodesSince(@Param("email") String email, @Param("purpose") String purpose, @Param("hours") int hours);

  int incrementCodeAttempts(@Param("id") long id);

  int consumeCode(@Param("id") long id);

  int insertSession(@Param("userId") long userId, @Param("tokenHash") String tokenHash, @Param("ip") String ip, @Param("userAgent") String userAgent, @Param("ttlHours") int ttlHours);

  SessionRow findActiveSession(@Param("tokenHash") String tokenHash);

  int extendSession(@Param("id") long id, @Param("ttlHours") int ttlHours, @Param("botIdleMinutes") int botIdleMinutes);

  int insertBotSession(@Param("userId") long userId, @Param("tokenHash") String tokenHash, @Param("ip") String ip, @Param("userAgent") String userAgent,
      @Param("idleMinutes") int idleMinutes, @Param("maxHours") int maxHours);

  int revokeSession(@Param("tokenHash") String tokenHash);

  int revokeUserSessions(@Param("userId") long userId, @Param("exceptTokenHash") String exceptTokenHash);

  // ---------- 第 5 批：应急开关（TB-34） ----------

  /** 应急开关下用 Telegram 登录的网页会话（和网页会话一样 ttlHours 小时、用着自动续期；开关关上就作废） */
  int insertEmergencySession(@Param("userId") long userId, @Param("tokenHash") String tokenHash, @Param("ip") String ip, @Param("userAgent") String userAgent,
      @Param("ttlHours") int ttlHours);

  /** 作废某一种的全部会话（应急开关关上时作废全部 EMERGENCY） */
  int revokeSessionsOfKind(@Param("kind") String kind);

  /** 开关的最新一条记录；没有记录 = 关 */
  Rows.EmergencyChange latestEmergency();

  /** 加锁读最新一条（开、关在同一个事务里先锁再写，两个人同时点只有一个算数） */
  Rows.EmergencyChange latestEmergencyForUpdate();

  int insertEmergencyLog(@Param("on") boolean on, @Param("actor") String actor, @Param("actorName") String actorName, @Param("reason") String reason,
      @Param("at") java.time.LocalDateTime at);

  java.util.List<Rows.EmergencyChange> emergencyHistory(@Param("limit") int limit);

  /** 插入用户时 MyBatis 回填 id */
  final class UserInsert {
    Long id;
    final String email;
    final String passwordHash;

    UserInsert(String email, String passwordHash) {
      this.email = email;
      this.passwordHash = passwordHash;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
  }
}
