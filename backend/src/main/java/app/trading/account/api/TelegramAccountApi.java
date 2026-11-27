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

  /**
   * 第 4 批 4b：人工换绑执行——这个客户的 TG 账户改成新的 TG 用户编号（新 TG 不能已经绑了别的客户，唯一键兜底），授权时间改成 at。
   * 安全事件和作废会话由调用方做
   */
  void rebind(long userId, long newTelegramId, String username, LocalDateTime at);

  /** "不是我，退出所有网页会话"（第 2 批）：作废这个客户的全部网页会话（网页登录的和从机器人进入的） */
  void logoutAllWeb(long userId);
}
