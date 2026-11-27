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

  @Autowired java.time.Clock clock;

  /** 模拟"已交给发送队列、进程就重启了"：任务标成功，通知记录还停在发送中 */
  long handedOffThenCrashed(long uid) {
    notify.toUser(uid, "DEPOSIT_CREDITED", Map.of("amount", "10", "chain", "TRC20"));
    long rid = jdbc.queryForObject("SELECT MAX(id) FROM notify_record WHERE user_id = ? AND channel = 'TELEGRAM'", Long.class, uid);
    assertThat(jdbc.update("UPDATE common_task SET status = 'SUCCEEDED' WHERE task_type = 'TG_SEND' AND JSON_EXTRACT(payload, '$.recordId') = ?", rid)).isEqualTo(1);
    return rid;
  }

  int tgTasks(long rid, String status) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM common_task WHERE task_type = 'TG_SEND' AND status = ? AND JSON_EXTRACT(payload, '$.recordId') = ?",
        Integer.class, status, rid);
  }

  String status(long rid) {
    return jdbc.queryForObject("SELECT CONCAT(status, ' ', COALESCE(error, '-')) FROM notify_record WHERE id = ?", String.class, rid);
  }

  @Test
  void 重启后补发_30分钟以内的重新排队_更早的记失败_还有任务在排的不动() {
    java.time.LocalDateTime now = java.time.LocalDateTime.now(clock);
    long fresh = handedOffThenCrashed(tgUser());
    long old = handedOffThenCrashed(tgUser());
    jdbc.update("UPDATE notify_record SET created_at = ? WHERE id = ?", now.minusMinutes(31), old);
    long waiting = tgUser();
    notify.toUser(waiting, "DEPOSIT_CREDITED", Map.of("amount", "10", "chain", "TRC20"));
    long waitingRid = jdbc.queryForObject("SELECT MAX(id) FROM notify_record WHERE user_id = ? AND channel = 'TELEGRAM'", Long.class, waiting);

    int[] r = worker.resumeTelegram(java.time.Duration.ofMinutes(30), now);

    assertThat(r[0]).isGreaterThanOrEqualTo(1);
    assertThat(tgTasks(fresh, "PENDING")).as("用原任务的内容重新排了一个").isEqualTo(1);
    assertThat(status(fresh)).isEqualTo("PENDING -");
    assertThat(status(old)).isEqualTo("FAILED " + NotifyWorker.RESUME_TOO_OLD);
    assertThat(tgTasks(old, "PENDING")).isZero();
    assertThat(tgTasks(waitingRid, "PENDING")).as("原任务还在排，不重复排").isEqualTo(1);
    assertThat(status(waitingRid)).isEqualTo("PENDING -");
    // 再跑一次：刚排的任务还没做，不会再排
    worker.resumeTelegram(java.time.Duration.ofMinutes(30), now);
    assertThat(tgTasks(fresh, "PENDING")).isEqualTo(1);
  }

  @Test
  void 重启后补发_找不到原任务的记失败() {
    java.time.LocalDateTime now = java.time.LocalDateTime.now(clock);
    long rid = handedOffThenCrashed(tgUser());
    jdbc.update("DELETE FROM common_task WHERE task_type = 'TG_SEND' AND JSON_EXTRACT(payload, '$.recordId') = ?", rid);
    worker.resumeTelegram(java.time.Duration.ofMinutes(30), now);
    assertThat(status(rid)).isEqualTo("FAILED " + NotifyWorker.RESUME_LOST);
  }
}
