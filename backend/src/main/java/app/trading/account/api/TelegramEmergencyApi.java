package app.trading.account.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Telegram 应急开关（TG 机器人第 5 批，技术方案 TG机器人-11 页第 3 节，决定清单 TB-34）：平时关；机器人被封、客户进不来时由 Amos 打开，
 * 网页登录页出现「用 Telegram 登录」（挂在另一个只管登录的机器人上）。这样登录算安全事件（24 小时不能提现）；关上后这类会话全部作废。
 * <p>谁能开关由后台决定（只有 Amos 的后台账号，见 admin.ops.EmergencyOps）；这里只管状态和记录。服务器备用办法
 * {@code sudo trading set TR_TG_EMERGENCY_LOGIN on|off} 直接写同一张记录表。
 */
public interface TelegramEmergencyApi {

  /** 现在的状态和最近一次变化（从没开过时 changedAt 等为空） */
  record State(boolean on, LocalDateTime changedAt, String changedBy, String reason) {}

  /** 一条开关记录。actor：admin:编号 / server */
  record Change(long id, boolean on, String actor, String actorName, String reason, LocalDateTime at) {}

  State state();

  /** 最近的开关记录，新的在前 */
  List<Change> history(int limit);

  /**
   * 打开或关闭。和现在一样时报 {@code EMERGENCY_UNCHANGED}。关闭时同一个事务里作废全部应急登录会话。
   *
   * @param actor admin:编号
   * @param actorName 后台用户名（写进记录和告警）
   */
  void set(boolean on, String actor, String actorName, String reason);
}
