package app.trading.account.auth;

import java.time.LocalDate;

/** account 模块的表对应的行（只取用到的字段）。 */
final class Rows {
  private Rows() {}

  record UserRow(long id, String email, String passwordHash, String status) {}

  /** expired、consumed、ageSeconds 由数据库按 UTC 时间算出 */
  record EmailCodeRow(long id, String codeHash, int attempts, boolean expired, boolean consumed, long ageSeconds) {}

  /** idleSeconds：距离上次续期的秒数，由数据库算出 */
  /** kind：WEB 网页登录 / BOT 从机器人一键登录（TG 机器人第 2 批） */
  record SessionRow(long id, long userId, long idleSeconds, String kind) {}

  /** 机器人"当前网页会话"列表用 */
  record ActiveSession(String kind, String ip, String userAgent, java.time.LocalDateTime createdAt) {}

  /** 应急开关的一条记录（第 5 批） */
  record EmergencyChange(long id, boolean turnedOn, String actor, String actorName, String reason, java.time.LocalDateTime createdAt) {}


  record Profile(long userId, String firstName, String middleName, String lastName, LocalDate birthDate, String nationality) {}
}
