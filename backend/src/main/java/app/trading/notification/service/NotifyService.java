package app.trading.notification.service;

import app.trading.account.api.AccountApi;
import app.trading.account.api.TelegramAccountApi;
import app.trading.common.error.BizException;
import app.trading.common.error.ValidationException;
import app.trading.common.task.TaskService;
import app.trading.notification.api.NotifyAdminApi;
import app.trading.notification.api.NotifyApi;
import app.trading.notification.api.NotifyChannel;
import app.trading.notification.api.TelegramCategory;
import app.trading.notification.api.TelegramPrefsApi;
import app.trading.trading.api.UserEventsApi;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 通知（内部文档）：
 * <ul>
 *   <li>站内信始终发送；存模板编号和变量，打开时按客户当前界面语言生成（切换语言后旧消息也跟着变）。</li>
 *   <li>推送和邮件按客户的开关（X-05），关闭的渠道记为已跳过（AC-25）。推送每台设备一条记录、一个 {@code PUSH_SEND} 任务（S15b）。</li>
 *   <li>邮件写成 {@code MAIL_SEND} 任务，和业务同一事务；业务提交后由 {@link NotifyWorker} 发出。</li>
 *   <li>Telegram（TG 机器人第 1 批）：只发给开过 TG 账户的客户，按类开关（强平、安全不能关）；写成 {@code TG_SEND} 任务，提交后交给机器人的发送队列。</li>
 *   <li>每个渠道一条通知记录（后台"通知记录"页面）。</li>
 * </ul>
 */
@Service
class NotifyService implements NotifyApi, NotifyAdminApi, TelegramPrefsApi {

  static final String MAIL_TASK = "MAIL_SEND";
  static final int MAIL_ATTEMPTS = 5;

  static final String SKIP_PUSH_OFF = "客户关闭了推送";
  static final String SKIP_EMAIL_OFF = "客户关闭了邮件";
  static final String SKIP_NO_DEVICE = "没有订阅推送的设备";
  static final String SKIP_PUSH_NOT_CONFIGURED = "推送未配置";
  static final String PUSH_TASK = "PUSH_SEND";
  static final int PUSH_ATTEMPTS = 5;
  /** 纯 Telegram 客户（机器人里开户）没有邮箱：不排邮件任务（TG 机器人第 1 批，技术方案 07 页第 2b 节） */
  static final String SKIP_NO_EMAIL = "没有邮箱";
  static final String SKIP_TG_OFF = "客户关闭了这类 Telegram 提醒";
  /** Telegram 提醒任务：通知和业务一起提交，提交后由 {@link NotifyWorker} 交给机器人的发送队列 */
  static final String TG_TASK = "TG_SEND";
  static final int TG_ATTEMPTS = 5;

  private static final Logger log = LoggerFactory.getLogger(NotifyService.class);
  private static final TypeReference<Map<String, String>> PARAMS = new TypeReference<>() {};

  /** 站内信，字段和客户端 NotificationItem 一致 */
  record Item(String id, String kind, String title, String body, boolean read, String createdAt) {}

  record Page<T>(List<T> items, long total, int page, int pageSize) {}

  record Prefs(boolean push, boolean email) {}

  private final NotifyMapper mapper;
  private final TaskService tasks;
  private final ObjectMapper json;
  private final UserEventsApi events;
  private final Clock clock;
  private final WebPush webPush;
  private final NotifyProperties.Push pushProps;

  /** 按需取（account 模块依赖通知模块，构造时直接注入会循环依赖） */
  private final ObjectProvider<AccountApi> accounts;
  private final ObjectProvider<TelegramAccountApi> telegram;

  NotifyService(NotifyMapper mapper, TaskService tasks, ObjectMapper json, UserEventsApi events, Clock clock, WebPush webPush, NotifyProperties props,
      ObjectProvider<AccountApi> accounts, ObjectProvider<TelegramAccountApi> telegram) {
    this.accounts = accounts;
    this.telegram = telegram;
    this.mapper = mapper;
    this.tasks = tasks;
    this.json = json;
    this.events = events;
    this.clock = clock;
    this.webPush = webPush;
    this.pushProps = props.push();
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }

  // ---------- 发送 ----------

  @Override
  @Transactional
  public void toUser(long userId, String template, Map<String, String> params) {
    Rows.TemplateRow t = mapper.template(template);
    if (t == null || !"USER".equals(t.audience)) throw new IllegalArgumentException("不是发给客户的模板：" + template);
    if (!t.enabled) {
      log.info("模板 {} 已停用，不发送 user={}", template, userId);
      return;
    }
    Rows.PrefRow pref = prefRow(userId);
    String title = Render.render(t, params, Render.ZH).title();
    String paramsJson = json.writeValueAsString(params == null ? Map.of() : params);
    Long messageId = null;
    if (t.has("INBOX")) {
      Rows.MessageRow m = new Rows.MessageRow();
      m.userId = userId;
      m.kind = t.kind;
      m.templateCode = t.code;
      m.params = paramsJson;
      m.createdAt = now();
      mapper.insertMessage(m);
      record(userId, null, "TEMPLATE", t.code, "INBOX", title, "SENT", null, null);
      messageId = m.id;
      afterCommit(() -> pushItem(userId, view(m, t, pref.lang)));
    }
    if (t.has("PUSH")) {
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("userId", userId);
      p.put("template", t.code);
      p.put("params", params == null ? Map.of() : params);
      p.put("lang", pref.lang);
      p.put("kind", t.kind);
      p.put("messageId", messageId);
      push(userId, pref, "TEMPLATE", t.code, title, p, null);
    }
    if (t.has("EMAIL")) {
      if (!pref.emailEnabled) {
        record(userId, null, "TEMPLATE", t.code, "EMAIL", title, "SKIPPED", SKIP_EMAIL_OFF, null);
      } else if (!hasEmail(userId)) {
        record(userId, null, "TEMPLATE", t.code, "EMAIL", title, "SKIPPED", SKIP_NO_EMAIL, null);
      } else {
        long rid = record(userId, null, "TEMPLATE", t.code, "EMAIL", title, "PENDING", null, null);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("recordId", rid);
        p.put("userId", userId);
        p.put("template", t.code);
        p.put("params", params == null ? Map.of() : params);
        p.put("lang", pref.lang);
        enqueueMail(p);
      }
    }
    if (t.has("TELEGRAM")) telegram(userId, "TEMPLATE", t.code, title, params, null, null, null);
  }

  /** 有邮箱吗（纯 Telegram 客户没有）；客户不存在按没有处理 */
  private boolean hasEmail(long userId) {
    try {
      return accounts.getObject().email(userId).isPresent();
    } catch (RuntimeException e) {
      return false;
    }
  }

  /**
   * Telegram 渠道（TG 机器人第 1 批）：只发给开过 TG 账户的客户（没有的不记录，网页客户不会多出一堆"跳过"）；
   * 客户关了这一类就记"已跳过"（强平、安全提醒不能关）；否则记一条、排 {@code TG_SEND} 任务。
   */
  private void telegram(long userId, String source, String templateCode, String title, Map<String, String> params, String subject, String body, Long operatorId) {
    TelegramAccountApi tg = telegram.getIfAvailable();
    if (tg == null || tg.byUserId(userId).isEmpty()) return;
    TelegramCategory cat = TelegramCategory.ofTemplate(templateCode);
    if (cat.closable && !tgEnabled(userId, cat)) {
      record(userId, null, source, templateCode, "TELEGRAM", title, "SKIPPED", SKIP_TG_OFF, operatorId);
      return;
    }
    long rid = record(userId, null, source, templateCode, "TELEGRAM", title, "PENDING", null, operatorId);
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("recordId", rid);
    p.put("userId", userId);
    if (templateCode != null) {
      p.put("template", templateCode);
      p.put("params", params == null ? Map.of() : params);
    } else {
      p.put("subject", subject);
      p.put("body", body);
    }
    tasks.enqueue(TG_TASK, json.writeValueAsString(p), 0, TG_ATTEMPTS);
  }

  /** 按邮箱发（验证码、风控人员，{@link AddressMailer}）。不知道收件人语言，中英文各一份 */
  @Transactional
  void sendToAddress(String to, String template, Map<String, String> params) {
    Rows.TemplateRow t = mapper.template(template);
    if (t == null || "USER".equals(t.audience)) throw new IllegalArgumentException("不能按邮箱发的模板：" + template);
    String title = Render.render(t, params, Render.ZH).title();
    long rid = record(null, to, "TEMPLATE", t.code, "EMAIL", title, "PENDING", null, null);
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("recordId", rid);
    p.put("to", to);
    p.put("template", t.code);
    p.put("params", params == null ? Map.of() : params);
    enqueueMail(p);
  }

  @Override
  @Transactional
  public void manual(long userId, Set<NotifyChannel> channels, String title, String body, long operatorId) {
    List<ValidationException.Field> errors = new ArrayList<>();
    if (channels == null || channels.isEmpty()) errors.add(new ValidationException.Field("channels", "required"));
    if (title == null || title.isBlank()) errors.add(new ValidationException.Field("title", "required"));
    else if (title.length() > 200) errors.add(new ValidationException.Field("title", "tooLong"));
    if (body == null || body.isBlank()) errors.add(new ValidationException.Field("body", "required"));
    else if (body.length() > 2000) errors.add(new ValidationException.Field("body", "tooLong"));
    if (!errors.isEmpty()) throw new ValidationException(errors);
    String tt = title.trim();
    String bb = body.trim();
    Rows.PrefRow pref = prefRow(userId);
    Long messageId = null;
    if (channels.contains(NotifyChannel.INBOX)) {
      Rows.MessageRow m = new Rows.MessageRow();
      m.userId = userId;
      m.kind = "system";
      m.title = tt;
      m.body = bb;
      m.createdAt = now();
      mapper.insertMessage(m);
      record(userId, null, "MANUAL", null, "INBOX", tt, "SENT", null, operatorId);
      messageId = m.id;
      afterCommit(() -> pushItem(userId, view(m, null, pref.lang)));
    }
    if (channels.contains(NotifyChannel.PUSH)) {
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("userId", userId);
      p.put("subject", tt);
      p.put("body", bb);
      p.put("kind", "system");
      p.put("messageId", messageId);
      push(userId, pref, "MANUAL", null, tt, p, operatorId);
    }
    if (channels.contains(NotifyChannel.EMAIL)) {
      if (!pref.emailEnabled) {
        record(userId, null, "MANUAL", null, "EMAIL", tt, "SKIPPED", SKIP_EMAIL_OFF, operatorId);
      } else if (!hasEmail(userId)) {
        record(userId, null, "MANUAL", null, "EMAIL", tt, "SKIPPED", SKIP_NO_EMAIL, operatorId);
      } else {
        long rid = record(userId, null, "MANUAL", null, "EMAIL", tt, "PENDING", null, operatorId);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("recordId", rid);
        p.put("userId", userId);
        p.put("subject", tt);
        p.put("body", bb);
        enqueueMail(p);
      }
    }
    if (channels.contains(NotifyChannel.TELEGRAM)) telegram(userId, "MANUAL", null, tt, null, tt, bb, operatorId);
  }

  /**
   * 推送：客户关了、没有配置、没有设备都记"已跳过"；否则每台设备一条记录、一个任务（某台设备失败重试时不会重复推给别的设备）。
   */
  private void push(long userId, Rows.PrefRow pref, String source, String template, String title, Map<String, Object> base, Long operatorId) {
    if (!pref.pushEnabled) {
      record(userId, null, source, template, "PUSH", title, "SKIPPED", SKIP_PUSH_OFF, operatorId);
      return;
    }
    if (!webPush.enabled()) {
      record(userId, null, source, template, "PUSH", title, "SKIPPED", SKIP_PUSH_NOT_CONFIGURED, operatorId);
      return;
    }
    List<Rows.PushSubRow> subs = mapper.pushSubs(userId);
    if (subs.isEmpty()) {
      record(userId, null, source, template, "PUSH", title, "SKIPPED", SKIP_NO_DEVICE, operatorId);
      return;
    }
    for (Rows.PushSubRow sub : subs) {
      long rid = record(userId, null, source, template, "PUSH", title, "PENDING", null, operatorId);
      Map<String, Object> p = new LinkedHashMap<>(base);
      p.put("recordId", rid);
      p.put("subId", sub.id);
      tasks.enqueue(PUSH_TASK, json.writeValueAsString(p), 0, PUSH_ATTEMPTS);
    }
  }

  private void enqueueMail(Map<String, Object> payload) {
    tasks.enqueue(MAIL_TASK, json.writeValueAsString(payload), 0, MAIL_ATTEMPTS);
  }

  private long record(Long userId, String recipient, String source, String template, String channel, String title, String status, String error,
      Long operatorId) {
    Rows.RecordRow r = new Rows.RecordRow();
    r.userId = userId;
    r.recipient = recipient;
    r.source = source;
    r.templateCode = template;
    r.channel = channel;
    r.title = title.length() > 200 ? title.substring(0, 200) : title;
    r.status = status;
    r.error = error;
    r.operatorId = operatorId;
    r.createdAt = now();
    mapper.insertRecord(r);
    return r.id;
  }

  /** 新站内信实时推给客户端（{"type":"notification","item":{…}}，和客户端 ServerEvent 一致）；事务提交后再推 */
  private void pushItem(long userId, Item item) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("type", "notification");
    e.put("item", item);
    events.send(userId, e);
  }

  private static void afterCommit(Runnable r) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          r.run();
        }
      });
    } else {
      r.run();
    }
  }

  // ---------- 后台（S16b） ----------

  private static NotifyAdminApi.Template template(Rows.TemplateRow t) {
    List<String> vars = t.variables == null || t.variables.isBlank() ? List.of() : java.util.Arrays.stream(t.variables.split(",")).map(String::strip).toList();
    List<NotifyChannel> chs = java.util.Arrays.stream(t.channels.split(",")).map(c -> NotifyChannel.valueOf(c.strip())).toList();
    return new NotifyAdminApi.Template(t.code, t.audience, t.kind, t.nameZh, t.nameEn, t.titleZh, t.bodyZh, t.titleEn, t.bodyEn, vars, chs, t.builtIn,
        t.enabled, t.updatedAt);
  }

  @Override
  @Transactional(readOnly = true)
  public List<NotifyAdminApi.Template> templates() {
    return mapper.templates().stream().map(NotifyService::template).toList();
  }

  private static final java.util.regex.Pattern VAR = java.util.regex.Pattern.compile("\\{([A-Za-z0-9_]+)}");

  @Override
  @Transactional
  public NotifyAdminApi.Template updateTemplate(String code, String titleZh, String bodyZh, String titleEn, String bodyEn, Boolean enabled) {
    Rows.TemplateRow t = mapper.template(code);
    if (t == null) throw new app.trading.common.error.BizException("NOT_FOUND", "模板不存在");
    // 第 6 批 TB-37：告警邮件模板（STAFF）不能在后台改——偷到后台账号的人不能把告警里的内容、撤销入口删掉；要改走迁移
    if ("STAFF".equals(t.audience)) throw new app.trading.common.error.BizException("TEMPLATE_LOCKED", "告警邮件模板不能在后台修改");
    java.util.Set<String> allowed = new java.util.HashSet<>(template(t).variables());
    ValidationException.Collector c = new ValidationException.Collector();
    String tz = text(c, "titleZh", titleZh, t.titleZh, 200, allowed);
    String bz = text(c, "bodyZh", bodyZh, t.bodyZh, 1000, allowed);
    String te = text(c, "titleEn", titleEn, t.titleEn, 200, allowed);
    String be = text(c, "bodyEn", bodyEn, t.bodyEn, 1000, allowed);
    boolean en = enabled == null ? t.enabled : enabled;
    // #46 审查：客户的安全类提醒只能改文案——不能停用（不管是不是内置），原来文案里有的变量（客户号、时间、设备、IP……）一个都不能少
    boolean security = NotifyAdminApi.securityTemplate(code);
    if (security && !en) throw new app.trading.common.error.BizException("SECURITY_TEMPLATE_CANNOT_DISABLE", "安全提醒模板不能停用");
    if (t.builtIn && !en) throw new app.trading.common.error.BizException("BUILT_IN_CANNOT_DISABLE", "内置模板不能停用");
    if (security) {
      keepVars(c, "titleZh", t.titleZh, tz);
      keepVars(c, "bodyZh", t.bodyZh, bz);
      keepVars(c, "titleEn", t.titleEn, te);
      keepVars(c, "bodyEn", t.bodyEn, be);
    }
    c.throwIfAny();
    mapper.updateTemplate(code, tz, bz, te, be, en);
    return template(mapper.template(code));
  }

  /** 安全类模板：新文案要保留旧文案里出现过的全部变量 */
  private static void keepVars(ValidationException.Collector c, String field, String oldText, String newText) {
    if (oldText == null || newText == null || newText.equals(oldText)) return;
    java.util.regex.Matcher m = VAR.matcher(oldText);
    while (m.find()) {
      if (!newText.contains("{" + m.group(1) + "}")) {
        c.add(field, "missingVariable");
        return;
      }
    }
  }

  /** 为空不改；不能为空白、不能超长、只能用模板列出的变量 */
  private static String text(ValidationException.Collector c, String field, String value, String old, int max, java.util.Set<String> allowed) {
    if (value == null) return old;
    String v = value.strip();
    if (v.isEmpty()) {
      c.add(field, "required");
      return old;
    }
    if (v.length() > max) {
      c.add(field, "tooLong");
      return old;
    }
    java.util.regex.Matcher m = VAR.matcher(v);
    while (m.find()) {
      if (!allowed.contains(m.group(1))) {
        c.add(field, "unknownVariable");
        return old;
      }
    }
    return v;
  }

  @Override
  @Transactional(readOnly = true)
  public NotifyAdminApi.Page<NotifyAdminApi.Record> records(NotifyChannel channel, String status, java.util.Collection<Long> userIds, int offset, int limit) {
    if (userIds != null && userIds.isEmpty()) return new NotifyAdminApi.Page<>(List.of(), 0);
    String ch = channel == null ? null : channel.name();
    List<NotifyAdminApi.Record> items = mapper.records(ch, status, userIds, offset, limit).stream()
        .map(r -> new NotifyAdminApi.Record(r.id, r.userId, r.recipient, r.source, r.templateCode, NotifyChannel.valueOf(r.channel), r.title, r.status, r.error,
            r.operatorId, r.createdAt))
        .toList();
    return new NotifyAdminApi.Page<>(items, mapper.countRecords(ch, status, userIds));
  }

  // ---------- 客户端读取 ----------

  @Transactional(readOnly = true)
  Page<Item> list(long userId, String lang, int page, int pageSize) {
    int p = Math.max(1, page);
    int size = Math.min(100, Math.max(1, pageSize));
    Map<String, Rows.TemplateRow> cache = new LinkedHashMap<>();
    List<Item> items = mapper.messages(userId, (p - 1) * size, size).stream()
        .map(m -> view(m, m.templateCode == null ? null : cache.computeIfAbsent(m.templateCode, mapper::template), lang)).toList();
    return new Page<>(items, mapper.countMessages(userId), p, size);
  }

  @Transactional(readOnly = true)
  long unread(long userId) {
    return mapper.countUnread(userId);
  }

  @Transactional
  void markRead(long userId, long id) {
    mapper.markRead(userId, id, now());
  }

  @Transactional
  void markAllRead(long userId) {
    mapper.markAllRead(userId, now());
  }

  @Transactional(readOnly = true)
  Prefs prefs(long userId) {
    Rows.PrefRow p = prefRow(userId);
    return new Prefs(p.pushEnabled, p.emailEnabled);
  }

  @Transactional
  Prefs setPrefs(long userId, boolean push, boolean email) {
    mapper.upsertPref(userId, push, email);
    return new Prefs(push, email);
  }

  /** 客户端切换语言时告诉服务端，推送和邮件按这个语言生成 */
  @Transactional
  void setLang(long userId, String lang) {
    if (!Render.ZH.equals(lang) && !Render.EN.equals(lang)) throw new ValidationException(List.of(new ValidationException.Field("lang", "invalid")));
    mapper.upsertLang(userId, lang);
  }

  /** Web Push 的公钥（客户端订阅时用）；没有配置时为空 */
  String pushPublicKey() {
    return webPush.publicKey();
  }

  /** 保存订阅（浏览器 PushSubscription.toJSON() 的 endpoint、keys.p256dh、keys.auth）；超过设备上限时删掉最早的 */
  @Transactional
  void subscribe(long userId, String endpoint, String p256dh, String auth) {
    ValidationException.Collector c = new ValidationException.Collector();
    if (endpoint == null || endpoint.isBlank()) c.add("endpoint", "required");
    else if (endpoint.length() > 1000 || !webPush.allowedEndpoint(endpoint)) c.add("endpoint", "invalid");
    if (!validKey(p256dh, 65)) c.add("p256dh", "invalid");
    if (!validKey(auth, 16)) c.add("auth", "invalid");
    c.throwIfAny();
    mapper.upsertPushSub(userId, endpoint, sha256(endpoint), p256dh.strip(), auth.strip(), now());
    mapper.trimPushSubs(userId, pushProps.maxDevices());
  }

  @Transactional
  void unsubscribe(long userId, String endpoint) {
    if (endpoint == null || endpoint.isBlank()) throw new ValidationException(List.of(new ValidationException.Field("endpoint", "required")));
    mapper.deletePushSubByEndpoint(userId, sha256(endpoint));
  }

  private static boolean validKey(String v, int len) {
    if (v == null || v.isBlank() || v.length() > 120) return false;
    try {
      byte[] b = WebPushCrypto.b64(v);
      if (b.length != len) return false;
      if (len == 65) WebPushCrypto.publicKey(b);
      return true;
    } catch (IllegalArgumentException | java.security.GeneralSecurityException e) {
      return false;
    }
  }

  static String sha256(String s) {
    try {
      return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---------- Telegram 提醒按类开关（机器人"通知设置"） ----------

  @Override
  @Transactional(readOnly = true)
  public Map<TelegramCategory, Boolean> telegramPrefs(long userId) {
    Map<TelegramCategory, Boolean> m = new EnumMap<>(TelegramCategory.class);
    for (TelegramCategory c : TelegramCategory.values()) m.put(c, true);
    for (Rows.TgPrefRow r : mapper.tgPrefs(userId)) {
      try {
        TelegramCategory c = TelegramCategory.valueOf(r.category);
        if (c.closable) m.put(c, r.enabled);
      } catch (IllegalArgumentException ignored) {
        // 旧分类：忽略
      }
    }
    return m;
  }

  @Override
  @Transactional
  public void setTelegramPref(long userId, TelegramCategory category, boolean enabled) {
    if (!category.closable) throw new BizException("CATEGORY_LOCKED", category.nameZh + "不能关闭");
    mapper.upsertTgPref(userId, category.name(), enabled);
  }

  private boolean tgEnabled(long userId, TelegramCategory c) {
    for (Rows.TgPrefRow r : mapper.tgPrefs(userId)) if (c.name().equals(r.category)) return r.enabled;
    return true;
  }

  private Rows.PrefRow prefRow(long userId) {
    Rows.PrefRow p = mapper.pref(userId);
    return p == null ? Rows.PrefRow.defaults() : p;
  }

  private Item view(Rows.MessageRow m, Rows.TemplateRow t, String lang) {
    String title = m.title;
    String body = m.body;
    if (t != null) {
      Render.Text text = Render.render(t, json.readValue(m.params == null ? "{}" : m.params, PARAMS), lang);
      title = text.title();
      body = text.body();
    }
    return new Item(String.valueOf(m.id), m.kind, title, body, m.readAt != null, m.createdAt.toInstant(ZoneOffset.UTC).toString());
  }
}
