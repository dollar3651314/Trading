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
}
