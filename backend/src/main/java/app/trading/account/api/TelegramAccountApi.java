package app.trading.account.api;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Telegram 账户（TG 机器人第 1 批，技术方案 07 页第 5 节）：一个 Telegram 用户编号（{@code from.id}）对应一个客户号。
 * 只认 {@code from.id}，不认用户名（用户名客户可以改，只用来显示）。
 */
public interface TelegramAccountApi {

  /**
   * @param authorizedAt 最近一次授权（开户或安全事件后重新授权，UTC）：之后出过安全事件（改密码、冻结、解冻等）就要重新授权
   */
  record TgAccount(long userId, long telegramId, String username, LocalDateTime authorizedAt) {}

  Optional<TgAccount> byTelegramId(long telegramId);

  Optional<TgAccount> byUserId(long userId);

  /**
   * 授权开户：建客户（没有邮箱、没有密码、没有资料）、TG 账户和交易账户，同一个事务。
   * 同一个 TG 编号重复调用（连点两次、两个请求同时到）只开一个户，返回已有的。
   */
  TgAccount openAccount(long telegramId, String username);

  /** 安全事件之后客户在机器人里点了"重新授权"：授权时间改成现在 */
  void reauthorize(long userId);

  /** 客户改了 Telegram 用户名：跟着改（只用来显示） */
  void updateUsername(long userId, String username);

  /**
   * 一次性登录链接（第 2 批，一键登录的退路，{@code FX_TG_LOGIN_MODE=link}）：返回令牌原值（库里只存哈希），60 秒、只能用一次。
   *
   * @param next 登录后打开的页面（站内路径，不合法的回首页）
   */
  String createLoginLink(long userId, String next);

  /** 一套菜单的一组一次性登录令牌（每个路径一个，60 秒；用掉一个同组一起作废）。返回 路径 → 令牌原值 */
  java.util.Map<String, String> createLoginLinkGroup(long userId, java.util.List<String> paths);

  /**
   * 第 4 批 4b：人工换绑执行——这个客户的 TG 账户改成新的 TG 用户编号（新 TG 不能已经绑了别的客户，唯一键兜底），授权时间改成 at。
   * 安全事件和作废会话由调用方做
   */
  void rebind(long userId, long newTelegramId, String username, LocalDateTime at);

  /** 第 4 批 4c：后台解除关联（删掉这个客户的 TG 账户）；安全事件、冻结由调用方做 */
  void unlink(long userId);

  /** 第 4 批 4c：后台给没有 TG 账户的客户关联一个 TG（新 TG 不能已经绑了别的客户，唯一键兜底） */
  void link(long userId, long telegramId, String username, LocalDateTime at);

  /** "不是我，退出所有网页会话"（第 2 批）：作废这个客户的全部网页会话（网页登录的和从机器人进入的） */
  void logoutAllWeb(long userId);

  /**
   * 一个还有效的网页会话（验收反馈：不再每次登录都发提醒，机器人菜单里改成可以随时查看）。
   *
   * @param device 设备大致信息（系统 · 浏览器，从 User-Agent 粗略判断）
   * @param ip IP 只露前面一部分（IPv4 前三段）
   * @param kind WEB 网页登录 / BOT 从机器人进入 / EMERGENCY 应急开关下用 Telegram 登录
   * @param createdAt 登录时间（UTC）
   */
  record WebSession(String device, String ip, String kind, LocalDateTime createdAt) {}

  /** 这个客户现在还有效的网页会话，新的在前，最多 limit 个 */
  java.util.List<WebSession> activeWebSessions(long userId, int limit);
}
