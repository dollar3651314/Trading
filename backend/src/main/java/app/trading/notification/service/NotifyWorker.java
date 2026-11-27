package app.trading.notification.service;

import app.trading.account.api.AccountApi;
import app.trading.common.health.AlertProperties;
import app.trading.common.task.Task;
import app.trading.common.task.TaskService;
import app.trading.common.task.TaskStatus;
import app.trading.notification.api.TelegramCategory;
import app.trading.notification.api.TelegramSender;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 发送邮件任务（{@code MAIL_SEND}）、推送任务（{@code PUSH_SEND}，S15b）和 Telegram 提醒任务（{@code TG_SEND}，TG 机器人第 1 批：交给机器人的发送队列）。失败按任务表的规则退避重试，最多 5 次，仍失败把通知记录标为失败。
 * 发给客户的邮件在发送时才查邮箱（客户改了邮箱也发到新邮箱）。日志不打印邮件内容（可能有验证码）。
 * 测试环境（{@code FX_ENV_LABEL} 非空）发出的邮件和推送，标题前加"[测试] / [TEST] "（{@link EnvLabel}）。
 */
@Component
class NotifyWorker {

  private static final Logger log = LoggerFactory.getLogger(NotifyWorker.class);
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final TypeReference<Map<String, String>> PARAMS = new TypeReference<>() {};

  private final TaskService tasks;
  private final NotifyMapper mapper;
  private final MailTransport transport;
  private final AccountApi accounts;
  private final ObjectMapper json;
  private final WebPush webPush;
  private final EnvLabel envLabel;
  private final MailHealth mailHealth;
  /** 机器人的发送接口（telegram 模块实现）；没有时 Telegram 提醒记"失败：机器人没开通" */
  private ObjectProvider<TelegramSender> telegram;

  /** 测试用：不关心邮件告警时 */
  NotifyWorker(TaskService tasks, NotifyMapper mapper, MailTransport transport, AccountApi accounts, ObjectMapper json, WebPush webPush, String envLabel) {
    this(tasks, mapper, transport, accounts, json, webPush, envLabel, new MailHealth(AlertProperties.defaults(), true));
  }

  @Autowired
  NotifyWorker(TaskService tasks, NotifyMapper mapper, MailTransport transport, AccountApi accounts, ObjectMapper json, WebPush webPush,
      @Value("${trading.env-label:}") String envLabel, MailHealth mailHealth, ObjectProvider<TelegramSender> telegram) {
    this(tasks, mapper, transport, accounts, json, webPush, envLabel, mailHealth);
    this.telegram = telegram;
  }

  NotifyWorker(TaskService tasks, NotifyMapper mapper, MailTransport transport, AccountApi accounts, ObjectMapper json, WebPush webPush,
      String envLabel, MailHealth mailHealth) {
    this.tasks = tasks;
    this.mapper = mapper;
    this.transport = transport;
    this.accounts = accounts;
    this.json = json;
    this.webPush = webPush;
    this.envLabel = new EnvLabel(envLabel);
    this.mailHealth = mailHealth;
  }

  /** 把到期的邮件和推送任务全部发完，返回处理的数量 */
  int drain() {
    return drain(NotifyService.MAIL_TASK) + drain(NotifyService.PUSH_TASK) + drain(NotifyService.TG_TASK);
  }

  private int drain(String type) {
    int n = 0;
    while (true) {
      var batch = tasks.claim(type, "notify", 20);
      if (batch.isEmpty()) return n;
      for (Task t : batch) {
        handle(t);
        n++;
      }
    }
  }

  void handle(Task t) {
    if (NotifyService.PUSH_TASK.equals(t.taskType())) handlePush(t);
    else if (NotifyService.TG_TASK.equals(t.taskType())) handleTelegram(t);
    else handleMail(t);
  }

  private void handleMail(Task t) {
    Long recordId = null;
    try {
      Map<String, Object> p = json.readValue(t.payload(), MAP);
      recordId = p.get("recordId") instanceof Number num ? num.longValue() : null;
      String to = p.get("to") instanceof String s ? s : null;
      if (to == null && p.get("userId") instanceof Number uid) {
        to = accounts.email(uid.longValue()).orElse(null);
        if (to == null) {
          // 纯 Telegram 客户没有邮箱（排任务时已跳过；这里是排队后客户才变成没邮箱的情况）：不算邮件服务故障
          tasks.succeed(t.id());
          if (recordId != null) mapper.updateRecord(recordId, "SKIPPED", NotifyService.SKIP_NO_EMAIL);
          return;
        }
      }
      if (to == null) throw new IllegalStateException("没有收件人");
      Render.Text text;
      if (p.get("template") instanceof String code) {
        Rows.TemplateRow tpl = mapper.template(code);
        if (tpl == null) throw new IllegalStateException("模板不存在：" + code);
        Map<String, String> params = json.convertValue(p.getOrDefault("params", Map.of()), PARAMS);
        if (p.get("lang") instanceof String lang) {
          Render.Text r = Render.render(tpl, params, lang);
          text = new Render.Text(envLabel.prefix(lang) + r.title(), r.body());
        } else {
          text = Render.bilingual(tpl, params, envLabel.zh(), envLabel.en());
        }
      } else {
        // 后台手写的邮件：不知道是什么语言
        text = new Render.Text(envLabel.prefix(null) + p.get("subject"), String.valueOf(p.get("body")));
      }
      transport.send(to, text.title(), text.body());
      mailHealth.succeeded();
      tasks.succeed(t.id());
      if (recordId != null) mapper.updateRecord(recordId, "SENT", null);
    } catch (MailTransport.NotConfigured e) {
      log.warn("邮件未配置，未发送 task={}", t.id());
      tasks.succeed(t.id());
      if (recordId != null) mapper.updateRecord(recordId, "FAILED", e.getMessage());
    } catch (Exception e) {
      String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
      log.warn("邮件发送失败 task={} 第 {} 次：{}", t.id(), t.attempts(), msg);
      // 告警（E8）：连续失败算一次；模板不存在、没有收件人这类和邮件服务器无关的错误也算进去（同样需要人处理）
      mailHealth.failed(msg);
      tasks.fail(t.id(), msg);
      if (recordId != null) {
        Task after = tasks.find(t.id());
        boolean dead = after != null && after.status() == TaskStatus.FAILED;
        mapper.updateRecord(recordId, dead ? "FAILED" : "PENDING", msg.length() > 500 ? msg.substring(0, 500) : msg);
      }
    }
  }

  /** 推送内容：标题、正文、类型、站内信编号（客户端点开后跳到这条站内信）；超长时截短正文 */
  private void handlePush(Task t) {
    Long recordId = null;
    try {
      Map<String, Object> p = json.readValue(t.payload(), MAP);
      recordId = p.get("recordId") instanceof Number num ? num.longValue() : null;
      long subId = ((Number) p.get("subId")).longValue();
      long userId = ((Number) p.get("userId")).longValue();
      Rows.PushSubRow sub = mapper.pushSub(subId);
      if (sub == null || sub.userId != userId) {
        tasks.succeed(t.id());
        if (recordId != null) mapper.updateRecord(recordId, "SKIPPED", "订阅已删除");
        return;
      }
      Render.Text text;
      boolean urgent = false;
      if (p.get("template") instanceof String code) {
        Rows.TemplateRow tpl = mapper.template(code);
        if (tpl == null) throw new IllegalStateException("模板不存在：" + code);
        String lang = String.valueOf(p.get("lang"));
        Render.Text r = Render.render(tpl, json.convertValue(p.getOrDefault("params", Map.of()), PARAMS), lang);
        text = new Render.Text(envLabel.prefix(lang) + r.title(), r.body());
        urgent = "MARGIN_CALL".equals(code) || "LIQUIDATED".equals(code);
      } else {
        text = new Render.Text(envLabel.prefix(null) + p.get("subject"), String.valueOf(p.get("body")));
      }
      byte[] plaintext = pushPayload(text, p.get("kind"), p.get("messageId"));
      WebPush.Result r = webPush.send(sub.endpoint, WebPushCrypto.b64(sub.p256dh), WebPushCrypto.b64(sub.auth), plaintext, urgent);
      switch (r) {
        case WebPush.Delivered d -> {
          tasks.succeed(t.id());
          if (recordId != null) mapper.updateRecord(recordId, "SENT", null);
        }
        case WebPush.Gone g -> {
          mapper.deletePushSub(subId);
          tasks.succeed(t.id());
          if (recordId != null) mapper.updateRecord(recordId, "FAILED", "订阅已失效（" + g.status() + "），已删除");
        }
        case WebPush.Rejected rej -> {
          log.warn("推送被拒绝 task={} {}", t.id(), rej.reason());
          tasks.succeed(t.id());
          if (recordId != null) mapper.updateRecord(recordId, "FAILED", cut(rej.reason()));
        }
        case WebPush.Retry retry -> retry(t, recordId, retry.reason());
      }
    } catch (RuntimeException e) {
      retry(t, recordId, e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /**
   * Telegram 提醒：按客户当前语言生成文字，交给机器人的发送队列（按分类定优先级；强平、安全最先发）。
   * 交出去任务就算完成；通知记录在真正发出（或确定发不出）时由回调改成"已发送 / 失败"。
   */
  private void handleTelegram(Task t) {
    Long recordId = null;
    try {
      Map<String, Object> p = json.readValue(t.payload(), MAP);
      recordId = p.get("recordId") instanceof Number num ? num.longValue() : null;
      long userId = ((Number) p.get("userId")).longValue();
      Rows.PrefRow pref = mapper.pref(userId);
      String lang = pref == null ? Render.ZH : pref.lang;
      String code = p.get("template") instanceof String c ? c : null;
      Render.Text text;
      if (code != null) {
        Rows.TemplateRow tpl = mapper.template(code);
        if (tpl == null) throw new IllegalStateException("模板不存在：" + code);
        Render.Text r = Render.render(tpl, json.convertValue(p.getOrDefault("params", Map.of()), PARAMS), lang);
        text = new Render.Text(envLabel.prefix(lang) + r.title(), r.body());
      } else {
        text = new Render.Text(envLabel.prefix(null) + p.get("subject"), String.valueOf(p.get("body")));
      }
      TelegramCategory cat = TelegramCategory.ofTemplate(code);
      TelegramSender sender = telegram == null ? null : telegram.getIfAvailable();
      final Long rid = recordId;
      TelegramSender.Result r = sender == null ? TelegramSender.Result.BOT_OFF
          : sender.send(userId, text.title() + "\n\n" + text.body(), cat.priority, viewPath(cat), actions(code, json.convertValue(p.getOrDefault("params", Map.of()), PARAMS)), err -> {
            if (rid != null) mapper.updateRecord(rid, err == null ? "SENT" : "FAILED", cut(err));
          });
      tasks.succeed(t.id());
      if (recordId != null) {
        switch (r) {
          case QUEUED -> { /* 等回调 */ }
          case NOT_LINKED -> mapper.updateRecord(recordId, "SKIPPED", "没有 Telegram 账户");
          case BOT_OFF -> mapper.updateRecord(recordId, "FAILED", "机器人没开通或后台已关闭");
        }
      }
    } catch (RuntimeException e) {
      String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
      log.warn("Telegram 提醒失败 task={} 第 {} 次：{}", t.id(), t.attempts(), msg);
      tasks.fail(t.id(), msg);
      if (recordId != null) {
        Task after = tasks.find(t.id());
        boolean dead = after != null && after.status() == TaskStatus.FAILED;
        mapper.updateRecord(recordId, dead ? "FAILED" : "PENDING", cut(msg));
      }
    }
  }

  static final String RESUME_TOO_OLD = "进程重启时还没发出，超过补发时限，不再补发";
  static final String RESUME_LOST = "进程重启时还没发出，找不到原任务，不再补发";

  /**
   * 启动时补发 Telegram 提醒（审查 🟡1）：发送队列在内存里，进程重启时已交给队列、还没发出的通知记录停在"发送中"。
   * 对这些记录（还有没做完的 {@code TG_SEND} 任务的跳过，那些会照常发）：创建不超过 {@code within} 的，用原任务的内容重新排一个任务；
   * 更早的、或者找不到原任务的，记失败（过了很久的成交、预警再发出去只会误导客户）。
   *
   * @return [重新排队的条数, 记失败的条数]
   */
  int[] resumeTelegram(java.time.Duration within, java.time.LocalDateTime now) {
    List<Rows.RecordRow> pending = mapper.pendingTelegram();
    if (pending.isEmpty()) return new int[] {0, 0};
    java.util.Set<Long> active = new java.util.HashSet<>();
    for (Task t : tasks.active(NotifyService.TG_TASK)) {
      Long id = recordIdOf(t.payload());
      if (id != null) active.add(id);
    }
    Map<Long, String> sent = new java.util.HashMap<>();
    for (Task t : tasks.recentSucceeded(NotifyService.TG_TASK, within.plusMinutes(10).toSeconds())) {
      Long id = recordIdOf(t.payload());
      if (id != null) sent.put(id, t.payload());
    }
    java.time.LocalDateTime cutoff = now.minus(within);
    int requeued = 0;
    int failed = 0;
    for (Rows.RecordRow r : pending) {
      if (active.contains(r.id)) continue;
      String payload = sent.get(r.id);
      if (payload != null && r.createdAt != null && !r.createdAt.isBefore(cutoff)) {
        tasks.enqueue(NotifyService.TG_TASK, payload, 0, NotifyService.TG_ATTEMPTS);
        requeued++;
      } else {
        mapper.updateRecord(r.id, "FAILED", payload == null && r.createdAt != null && !r.createdAt.isBefore(cutoff) ? RESUME_LOST : RESUME_TOO_OLD);
        failed++;
      }
    }
    if (requeued + failed > 0) log.info("重启后 Telegram 提醒：重新排队 {} 条，记失败 {} 条", requeued, failed);
    return new int[] {requeued, failed};
  }

  private Long recordIdOf(String payload) {
    try {
      Map<String, Object> p = json.readValue(payload, MAP);
      return p.get("recordId") instanceof Number n ? n.longValue() : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * 提醒里额外的按钮：刚在浏览器里登录 → 「不是我，退出所有网页会话」；第 4 批 4b：有人申请换绑 → 「不是我」；
   * 换绑已批准、人工重设提现密码申请 / 已批准 → 「撤销」（按钮数据带申请编号）
   */
  static List<TelegramSender.Action> actions(String template, Map<String, String> params) {
    if (template == null) return List.of();
    String id = params == null ? null : params.get("id");
    return switch (template) {
      case "LOGIN_NEW_DEVICE" -> List.of(new TelegramSender.Action("不是我，退出所有网页会话", TelegramSender.ACTION_LOGOUT_ALL));
      case "RECOVERY_REQUESTED" -> id == null ? List.of() : List.of(new TelegramSender.Action("不是我", TelegramSender.ACTION_RECOVERY_OBJECT + id));
      case "RECOVERY_APPROVED", "PIN_RESET_REQUESTED", "PIN_RESET_APPROVED" ->
          id == null ? List.of() : List.of(new TelegramSender.Action("撤销", TelegramSender.ACTION_RECOVERY_REVOKE + id));
      default -> List.of();
    };
  }

  /** 提醒里「查看」按钮直达的网页客户端页面 */
  static String viewPath(TelegramCategory c) {
    return switch (c) {
      case TRADE, TPSL, RISK -> "/positions";
      case FUNDS -> "/assets/records";
      case KYC -> "/me/kyc";
      case SECURITY, SYSTEM -> null;
    };
  }

  private void retry(Task t, Long recordId, String msg) {
    log.warn("推送失败 task={} 第 {} 次：{}", t.id(), t.attempts(), msg);
    tasks.fail(t.id(), msg);
    if (recordId != null) {
      Task after = tasks.find(t.id());
      boolean dead = after != null && after.status() == TaskStatus.FAILED;
      mapper.updateRecord(recordId, dead ? "FAILED" : "PENDING", cut(msg));
    }
  }

  private static String cut(String s) {
    return s == null || s.length() <= 500 ? s : s.substring(0, 500);
  }

  /** JSON：{"title","body","kind","id"}；加密前不超过 RFC 8291 一条记录能放的长度，超长截短正文 */
  byte[] pushPayload(Render.Text text, Object kind, Object messageId) {
    String body = text.body();
    while (true) {
      Map<String, Object> m = new java.util.LinkedHashMap<>();
      m.put("title", text.title());
      m.put("body", body);
      m.put("kind", kind);
      if (messageId != null) m.put("id", String.valueOf(messageId));
      byte[] b = json.writeValueAsBytes(m);
      if (b.length <= WebPushCrypto.MAX_PLAINTEXT || body.isEmpty()) return b;
      // 中文一个字最多 3 字节：每次至少去掉超出部分对应的字数，最后加省略号
      int drop = Math.max(20, (b.length - WebPushCrypto.MAX_PLAINTEXT) / 3 + 2);
      body = body.length() <= drop ? "" : body.substring(0, body.length() - drop) + "…";
    }
  }
}
