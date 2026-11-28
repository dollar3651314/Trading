package app.trading.account.api;

/**
 * 事件：这个客户刚记了一条安全事件（验收反馈 1a）。账户模块在记事件的事务里发出；机器人（telegram 模块）在事务提交以后看一眼：
 * 这个客户现在要不要先「重新授权」（和机器人收到消息时的检查一样），要的话主动发一条带「重新授权」按钮的消息——
 * 网页把客户送回 Telegram 时，已经打开过的聊天不会自动有新消息，客户会卡住。
 *
 * @param type {@link SecurityEvent} 里的类型
 */
public record SecurityEventRecorded(long userId, String type) {}
