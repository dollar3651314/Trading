/**
 * telegram 模块：Telegram 机器人（TG 机器人第 1 批"机器人底座"，技术方案 TG机器人-05、07、11 页）。
 * 长轮询收消息（只认私聊）、授权开户、冻结 / 安全事件拦截、欢迎卡片和通知设置、按三档优先级发送提醒。
 * 只依赖 account、notification 的 api 包和 common；其他模块不依赖本模块（通知模块通过 {@code notification.api.TelegramSender} 交出提醒）。
 */
package app.trading.telegram;
