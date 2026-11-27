package app.trading.notification.api;

import java.util.Map;

/** 客户的 Telegram 提醒按类开关（机器人"通知设置"用）。没有记录 = 开；不能关的类（强平、安全）永远返回开、不能改 */
public interface TelegramPrefsApi {

  /** 全部分类的开关（按 {@link TelegramCategory} 的顺序） */
  Map<TelegramCategory, Boolean> telegramPrefs(long userId);

  /** 改一类；不能关的类抛 {@code BizException("CATEGORY_LOCKED")} */
  void setTelegramPref(long userId, TelegramCategory category, boolean enabled);
}
