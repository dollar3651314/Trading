package app.trading.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.trading.account.api.AccountApi;
import app.trading.common.health.AlertProperties;
import app.trading.common.task.Task;
import app.trading.common.task.TaskService;
import app.trading.common.task.TaskStatus;
import app.trading.notification.api.TelegramSender;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * 通知任务里和 Telegram、纯 TG 客户有关的部分（不启动 Spring）：TG 提醒按客户语言生成、按分类定优先级、「查看」直达页面，
 * 发出后回调才记"已发送"；邮件任务遇到没有邮箱的客户记"跳过"、不算邮件服务故障（技术方案 07 页第 2b 节）。
 */
class TelegramWorkerTest {

  record Sent(long userId, String text, TelegramSender.Priority priority, String viewPath, List<TelegramSender.Action> actions, Consumer<String> done) {}

  final ObjectMapper json = new ObjectMapper();
  final TaskService tasks = mock(TaskService.class);
  final NotifyMapper mapper = mock(NotifyMapper.class);
  final AccountApi accounts = mock(AccountApi.class);
  final List<Sent> sent = new ArrayList<>();
  final List<String> mails = new ArrayList<>();
  final MailHealth mailHealth = new MailHealth(AlertProperties.defaults(), true);
  final TelegramSender sender = (userId, text, priority, viewPath, actions, done) -> {
    sent.add(new Sent(userId, text, priority, viewPath, actions, done));
    return TelegramSender.Result.QUEUED;
  };

  TelegramWorkerTest() {
    Rows.TemplateRow margin = new Rows.TemplateRow();
    margin.code = "MARGIN_CALL";
    margin.titleZh = "保证金预警";
    margin.titleEn = "Margin call";
    margin.bodyZh = "保证金水平 {level}%";
    margin.bodyEn = "Margin level {level}%";
    when(mapper.template("MARGIN_CALL")).thenReturn(margin);
    Rows.TemplateRow login = new Rows.TemplateRow();
    login.code = "LOGIN_NEW_DEVICE";
    login.titleZh = "刚在浏览器里登录";
    login.titleEn = "New browser sign-in";
    login.bodyZh = "你的账户刚在浏览器里登录：{device}，IP {ip}。";
    login.bodyEn = "Signed in: {device}, IP {ip}.";
    when(mapper.template("LOGIN_NEW_DEVICE")).thenReturn(login);
  }

  @Test
  void 刚在浏览器里登录_高优先级_带不是我按钮() {
    worker().handle(task(NotifyService.TG_TASK, Map.of("recordId", 7, "userId", 7, "template", "LOGIN_NEW_DEVICE",
        "params", Map.of("device", "iPhone · Telegram 内置浏览器", "ip", "203.0.113.*"))));
    Sent s = sent.getFirst();
    assertThat(s.text()).isEqualTo("刚在浏览器里登录\n\n你的账户刚在浏览器里登录：iPhone · Telegram 内置浏览器，IP 203.0.113.*。");
    assertThat(s.priority()).isEqualTo(TelegramSender.Priority.HIGH);
    assertThat(s.actions()).containsExactly(new TelegramSender.Action("不是我，退出所有网页会话", TelegramSender.ACTION_LOGOUT_ALL));
  }

  NotifyWorker worker() {
    StaticListableBeanFactory f = new StaticListableBeanFactory(Map.of("tg", sender));
    return new NotifyWorker(tasks, mapper, (to, s, b) -> mails.add(to), accounts, json, mock(WebPush.class), "", mailHealth, f.getBeanProvider(TelegramSender.class));
  }

  Task task(String type, Map<String, Object> payload) {
    return new Task(9, type, json.writeValueAsString(payload), TaskStatus.RUNNING, 1, 5, null, null);
  }

  @Test
  void 强平预警高优先级_直达持仓_发出后才记已发送() {
    worker().handle(task(NotifyService.TG_TASK, Map.of("recordId", 5, "userId", 7, "template", "MARGIN_CALL", "params", Map.of("level", "80"))));
    assertThat(sent).hasSize(1);
    Sent s = sent.getFirst();
    assertThat(s.text()).isEqualTo("保证金预警\n\n保证金水平 80%");
    assertThat(s.priority()).isEqualTo(TelegramSender.Priority.HIGH);
    assertThat(s.viewPath()).isEqualTo("/positions");
    verify(tasks).succeed(9);
    verify(mapper, never()).updateRecord(anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    s.done().accept(null);
    verify(mapper).updateRecord(5, "SENT", null);
  }

  @Test
  void 客户屏蔽了机器人_记失败原因() {
    worker().handle(task(NotifyService.TG_TASK, Map.of("recordId", 6, "userId", 7, "subject", "维护", "body", "今晚维护")));
    Sent s = sent.getFirst();
    assertThat(s.priority()).as("后台手动发的算系统消息，低优先级").isEqualTo(TelegramSender.Priority.LOW);
    assertThat(s.viewPath()).isNull();
    s.done().accept("Telegram 拒绝（403）");
    verify(mapper).updateRecord(6, "FAILED", "Telegram 拒绝（403）");
  }

  @Test
  void 邮件任务遇到没有邮箱的客户_记跳过_不算邮件故障() {
    when(accounts.email(7L)).thenReturn(Optional.empty());
    for (int i = 0; i < 10; i++) worker().handle(task(NotifyService.MAIL_TASK, Map.of("recordId", 8, "userId", 7, "subject", "s", "body", "b")));
    assertThat(mails).isEmpty();
    verify(mapper, org.mockito.Mockito.times(10)).updateRecord(8, "SKIPPED", NotifyService.SKIP_NO_EMAIL);
    assertThat(mailHealth.failures()).isZero();
    assertThat(mailHealth.evaluate().getFirst().ok()).isTrue();
  }
}
