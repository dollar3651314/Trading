package app.trading.account.api;

/**
 * 事件：找回申请执行了（第 4 批 4b，需求文档 TG机器人-08 页第 1、3 节）——人工换绑（REBIND）或没邮箱人工重设提现密码（PIN_RESET）。
 * 账户模块在执行的事务里同步发出；钱包模块在同一个事务里清空这个客户的白名单（换绑、重设都要清空白名单）。
 */
public record AccountRecovered(long userId, String kind, long recoveryId) {}
