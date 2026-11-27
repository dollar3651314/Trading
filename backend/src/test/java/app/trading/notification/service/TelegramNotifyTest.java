package app.trading.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.trading.account.api.TelegramAccountApi;
import app.trading.common.error.BizException;
import app.trading.notification.api.NotifyChannel;
import app.trading.notification.api.TelegramCategory;
import app.trading.notification.api.TelegramPrefsApi;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 通知的 Telegram 渠道和纯 TG 客户（TG 机器人第 1 批）：没有邮箱的客户不排邮件任务（记"跳过：没有邮箱"）；
 * Telegram 只发给有 TG 账户的客户、按类开关、强平和安全提醒不能关。测试环境没配机器人令牌：任务交出去得到"机器人没开通"。
 */
class TelegramNotifyTest extends NotifyTestSupport {

  @Autowired TelegramAccountApi telegram;
  @Autowired TelegramPrefsApi prefs;

  long tgUser() {
    long uid = telegram.openAccount(8_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L), null).userId();
    jdbc.update("DELETE FROM notify_message WHERE user_id = ?", uid);
    jdbc.update("DELETE FROM notify_record WHERE user_id = ?", uid);
    return uid;
  }

  int mailTasks(long uid) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM common_task WHERE task_type = 'MAIL_SEND' AND JSON_EXTRACT(payload, '$.userId') = ?", Integer.class, uid);
  }

  @Test
  void 纯TG客户_不排邮件任务_Telegram排任务() {
    long uid = tgUser();
    notify.toUser(uid, "DEPOSIT_CREDITED", Map.of("amount", "10", "chain", "TRC20"));
    assertThat(records(uid)).containsExactly("INBOX SENT -", "PUSH SKIPPED 没有订阅推送的设备", "EMAIL SKIPPED 没有邮箱", "TELEGRAM PENDING -");
    assertThat(mailTasks(uid)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM common_task WHERE task_type = 'TG_SEND' AND JSON_EXTRACT(payload, '$.userId') = ?", Integer.class, uid))
        .isEqualTo(1);
    worker.drain();
    // 测试环境没配令牌：交给机器人得到"没开通"，记失败；不算邮件发送失败
    assertThat(records(uid)).last().isEqualTo("TELEGRAM FAILED 机器人没开通或后台已关闭");
  }

  @Test
  void 网页客户没有TG账户_不记Telegram() throws Exception {
    User u = registerClean();
    notify.toUser(u.id(), "DEPOSIT_CREDITED", Map.of("amount", "10", "chain", "TRC20"));
    assertThat(records(u.id())).noneMatch(r -> r.startsWith("TELEGRAM"));
    assertThat(mailTasks(u.id())).isEqualTo(1);
  }

  @Test
  void 客户关了这一类就跳过_强平预警关不掉() {
    long uid = tgUser();
    for (TelegramCategory c : TelegramCategory.values()) if (c.closable) prefs.setTelegramPref(uid, c, false);
    assertThatThrownBy(() -> prefs.setTelegramPref(uid, TelegramCategory.RISK, false)).isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).code()).isEqualTo("CATEGORY_LOCKED");
    assertThatThrownBy(() -> prefs.setTelegramPref(uid, TelegramCategory.SECURITY, false)).isInstanceOf(BizException.class);
    notify.toUser(uid, "DEPOSIT_CREDITED", Map.of("amount", "10", "chain", "TRC20"));
    assertThat(records(uid)).last().isEqualTo("TELEGRAM SKIPPED 客户关闭了这类 Telegram 提醒");
    notify.toUser(uid, "MARGIN_CALL", Map.of("level", "80", "line", "100"));
    assertThat(records(uid)).last().isEqualTo("TELEGRAM PENDING -");
    assertThat(prefs.telegramPrefs(uid)).containsEntry(TelegramCategory.RISK, true).containsEntry(TelegramCategory.TRADE, false);
  }

  @Test
  void 冻结发安全提醒_只走Telegram() {
    long uid = tgUser();
    accounts.freeze(uid);
    assertThat(jdbc.queryForList("SELECT CONCAT(channel, ' ', status) FROM notify_record WHERE user_id = ? AND template_code = 'ACCOUNT_FROZEN' ORDER BY id",
        String.class, uid)).containsExactly("TELEGRAM PENDING");
    accounts.unfreeze(uid);
  }

  @Test
  void 后台手动发送可以选Telegram_没邮箱的跳过邮件() {
    long uid = tgUser();
    admin.manual(uid, Set.of(NotifyChannel.EMAIL, NotifyChannel.TELEGRAM), "标题", "正文", 1L);
    assertThat(records(uid)).containsExactlyInAnyOrder("EMAIL SKIPPED 没有邮箱", "TELEGRAM PENDING -");
  }
}
