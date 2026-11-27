package app.trading.notification.api;

/** 通知渠道：站内信、Web Push、邮件（需求 4.7）、Telegram（TG 机器人第 1 批：只发给开过 TG 账户的客户）。 */
public enum NotifyChannel {
  INBOX,
  PUSH,
  EMAIL,
  TELEGRAM
}
