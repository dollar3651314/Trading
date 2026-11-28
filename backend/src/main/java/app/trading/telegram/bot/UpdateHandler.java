package app.trading.telegram.bot;

import app.trading.account.api.AccountApi;
import app.trading.account.api.SecurityEvent;
import app.trading.account.api.TelegramAccountApi;
import app.trading.account.api.TelegramAccountApi.TgAccount;
import app.trading.account.api.UserStatus;
import app.trading.common.config.ConfigKey;
import app.trading.common.config.SystemConfig;
import app.trading.common.ratelimit.RateLimiter;
import app.trading.notification.api.TelegramCategory;
import app.trading.notification.api.TelegramPrefsApi;
import app.trading.notification.api.TelegramSender.Priority;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * 处理机器人收到的消息和按钮（TG 机器人第 1 批；对话流程见需求文档 TG机器人-02 第 1 节，文案是草稿、交 Amos 看）。
 * <ul>
 *   <li><b>只认私聊</b>：群、频道里的消息一律不回应；机器人发的不理。</li>
 *   <li>认人<b>只认 {@code from.id}</b>（TG 用户编号），用户名只用来显示。</li>
 *   <li>每一次操作前先查：后台关了机器人吗？账户冻结了吗（冻结只回"账户已冻结，请联系客服"，不显示任何账户信息）？
 *       授权之后出过安全事件吗（要先点「重新授权」）？</li>
 *   <li>没开户的：后台开户名单（{@code telegram.signup.allowIds}）里有才给「授权开户」（TB-13：先只给 Amos 用）。</li>
 *   <li>每个 TG 用户每分钟最多处理 {@code user-per-minute} 条，超过的不回应（Redis 计数，Redis 不可用时放行）。</li>
 * </ul>
 * 回复一律走发送队列（高优先级），按钮先 answerCallbackQuery（不排队）。
 */
public class UpdateHandler {

  private static final Logger log = LoggerFactory.getLogger(UpdateHandler.class);

  static final String CB_SIGNUP = "signup";
  static final String CB_DECLINE = "decline";
  static final String CB_REAUTH = "reauth";
  static final String CB_MENU = "menu";
  static final String CB_PREFS = "prefs";
  /** 验收反馈：「当前网页会话」（不再每次登录都发提醒，改成随时可以查看） */
  static final String CB_SESSIONS = "sessions";
  static final String CB_PREF = "pref:";
  static final String CB_NOOP = "noop";
  /** 退路模式（FX_TG_LOGIN_MODE=link）：打开网页的回调按钮 open:路径，点了现发一条 60 秒一次性链接 */
  static final String CB_OPEN = "open:";
  /** 第 4 批 4c：机器人里自己冻结提现（来源 BOT_SELF），先确认 */
  static final String CB_FREEZE = "wdfreeze";
  static final String CB_FREEZE_YES = "wdfreeze:yes";
  static final String SOURCE_BOT_SELF = "BOT_SELF";
  static final String TEXT_FREEZE_ASK = "冻结提现：冻结后不能提现、加白名单、改提现密码，交易照常。怀疑账号被盗时用。\n"
      + "要解除：在网页输对提现密码，24 小时后自动解冻（期间 Telegram 和邮箱各通知一次）；这 24 小时里如果有人在网页申请冻结，就转人工。确定冻结吗？";
  static final String TEXT_FREEZE_DONE = "已冻结提现（交易照常）。要解除请点主菜单里的「解除自己设的提现冻结」，在网页输提现密码。";

  /** 第 4 批 4b：没绑账户的新 TG 点「找回原账户」 */
  static final String CB_RECOVER = "recover";
  static final String TEXT_RECOVER_ASK = "找回原账户（人工换绑）：请发一条消息，写原账户的客户号、KYC 时的姓名和证件号后 4 位，用空格隔开，例如：\n100482 张三 1234\n\n"
      + "只有原账户做过身份认证才能找回。原账户持有人会收到通知，48 小时内没人反对后，审核员会通过这个 Telegram 联系你做视频核对。客服不会私信向你要密码或验证码。";
  static final String TEXT_RECOVER_FORMAT = "格式不对。请发：客户号 姓名 证件号后4位（用空格隔开），例如：100482 张三 1234。";
  static final String TEXT_RECOVER_FREQUENT = "申请太频繁，请明天再试。";
  /** 信息对不对都回这一句（防止拿来猜客户号、姓名、证件号） */
  static final String TEXT_RECOVER_APPLIED = "已收到你的找回申请。信息核对无误的话，我们会通知原账户持有人；48 小时内没人反对，审核员会通过这个 Telegram 联系你做本人和资金来源核对。"
      + "信息不对的不会有后续通知。";
  static final String TEXT_RECOVER_BANNED = "暂不受理你的找回申请。";
  /** 第 6 批 TB-38：暂停受理人工申请中 */
  static final String TEXT_RECOVER_PAUSED = "找回申请暂停受理中（审核员暂时不在）。如果担心账户被别人用，请先在网页「申请冻结提现」止损，之后再来申请找回。";
  /** 等申请人发信息的时间 */
  static final Duration RECOVER_WAIT = Duration.ofMinutes(10);
  private static final java.util.regex.Pattern RECOVER_INPUT = java.util.regex.Pattern.compile("^\\s*#?(\\d{1,19})\\s+(.+?)\\s+([0-9A-Za-z]{4})\\s*$");

  /** "刚在浏览器里登录"提醒里的「不是我，退出所有网页会话」 */
  static final String CB_LOGOUT_ALL = app.trading.notification.api.TelegramSender.ACTION_LOGOUT_ALL;

  static final String TEXT_FROZEN = "账户已冻结，请联系客服。";
  static final String TEXT_PAUSED = "机器人暂停服务，请稍后再试。";
  static final String TEXT_NOT_OPEN = "暂未开放开户，请稍后再来。";
  static final String TEXT_DECLINED = "已取消，没有为你创建任何账户。想开户时再发 /start。";
  static final String TEXT_CONSENT = """
      欢迎使用 Trading 交易助手。

      风险提示：差价合约（CFD）带杠杆交易，价格波动可能让你在短时间内损失全部保证金。请只用你能承受损失的资金。

      ⚠️ 你的 Telegram 账号就是交易账户的钥匙，请打开 Telegram 两步验证（设置 → 隐私与安全 → 两步验证）。

      点「授权开户」表示你已阅读并同意服务条款和隐私政策。""";
  static final String TEXT_FAILED = "操作没有成功：账户服务暂时不可用，可以稍后再试；一直不行请联系客服。";
  static final String TEXT_LINK = "点「打开」进入网页（60 秒内有效，只能用一次；别转发给别人）。";
  static final String TEXT_LOGGED_OUT = "已退出所有网页会话。如果不是你本人登录的，建议马上检查账户。";

  /** 60 秒后删一次性链接消息（守护线程，进程重启时没删的就留着：链接本身 60 秒后已经失效） */
  private static final java.util.concurrent.ScheduledExecutorService DELETER = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "tg-link-delete");
    t.setDaemon(true);
    return t;
  });

  /** 一次性链接消息多久后删（和链接有效期 60 秒一样；测试里改短） */
  static volatile long linkDeleteMillis = 60_000;

  private final TelegramAccountApi accounts;
  private final AccountApi account;
  private final TelegramPrefsApi prefs;
  private final SystemConfig config;
  private final RateLimiter limiter;
  private final TelegramProperties props;
  private final TelegramOutbox outbox;
  private final TelegramOutbox.Api api;
  private final app.trading.account.api.RecoveryApi recovery;
  private final app.trading.account.api.WithdrawPinApi pins;
  /** 点了「找回原账户」、等着发信息的新 TG（单实例，放内存；重启后丢了就再点一次） */
  private final java.util.concurrent.ConcurrentHashMap<Long, Long> awaitingRecover = new java.util.concurrent.ConcurrentHashMap<>();

  public UpdateHandler(TelegramAccountApi accounts, AccountApi account, TelegramPrefsApi prefs, SystemConfig config, RateLimiter limiter,
      TelegramProperties props, TelegramOutbox outbox, TelegramOutbox.Api api, app.trading.account.api.RecoveryApi recovery,
      app.trading.account.api.WithdrawPinApi pins) {
    this.recovery = recovery;
    this.pins = pins;
    this.accounts = accounts;
    this.account = account;
    this.prefs = prefs;
    this.config = config;
    this.limiter = limiter;
    this.props = props;
    this.outbox = outbox;
    this.api = api;
  }

  /** 一批更新逐条处理；某条出错只记日志（不含消息内容），不影响后面的 */
  public void consume(List<Update> updates) {
    for (Update u : updates) {
      try {
        handle(u);
      } catch (RuntimeException e) {
        log.warn("处理 Telegram 更新 {} 出错：{}", u.getUpdateId(), props.scrub(e.toString()));
      }
    }
  }

  void handle(Update u) {
    if (u.hasMessage()) {
      Message m = u.getMessage();
      if (m.getChat() == null || !"private".equals(m.getChat().getType()) || m.getFrom() == null || Boolean.TRUE.equals(m.getFrom().getIsBot())) return;
      if (!allow(m.getFrom().getId())) return;
      onMessage(m.getFrom(), m.getText());
    } else if (u.hasCallbackQuery()) {
      CallbackQuery q = u.getCallbackQuery();
      MaybeInaccessibleMessage m = q.getMessage();
      if (m == null || m.getChat() == null || !"private".equals(m.getChat().getType()) || q.getFrom() == null) return;
      if (!allow(q.getFrom().getId())) return;
      answer(q.getId());
      onCallback(q.getFrom(), m.getMessageId(), q.getData() == null ? "" : q.getData());
    }
    // 其他更新（群成员变动、编辑消息等）不处理
  }

  private boolean allow(long tgId) {
    return limiter.hit("tg", String.valueOf(tgId), props.userPerMinute(), Duration.ofMinutes(1)) == 0;
  }

  // ---------- 消息 ----------

  private void onMessage(User from, String text) {
    long chat = from.getId();
    if (!botEnabled()) {
      send(chat, TEXT_PAUSED, null);
      return;
    }
    Optional<TgAccount> acc = accounts.byTelegramId(from.getId());
    if (acc.isEmpty()) {
      String t = text == null ? "" : text.strip();
      if (t.equals("/start recover") || t.equals("/recover")) {
        askRecover(chat);
        return;
      }
      Long since = awaitingRecover.get(from.getId());
      if (since != null && System.nanoTime() - since < RECOVER_WAIT.toNanos() && !t.startsWith("/")) {
        applyRecover(from, t);
        return;
      }
      if (!signupAllowed(from.getId())) send(chat, TEXT_NOT_OPEN, keyboard(List.of(row(cb("找回原账户", CB_RECOVER)))));
      else send(chat, TEXT_CONSENT, keyboard(List.of(row(cb("授权开户", CB_SIGNUP), cb("不同意", CB_DECLINE)), row(cb("找回原账户", CB_RECOVER)))));
      return;
    }
    TgAccount a = acc.get();
    if (!gate(chat, a)) return;
    syncUsername(a, from);
    String cmd = text == null ? "" : text.strip();
    if (cmd.startsWith("/settings")) send(chat, prefsText(), prefsKeyboard(a.userId()));
    else send(chat, menuText(a), menuKeyboard(a.userId()));
  }

  // ---------- 按钮 ----------

  private void onCallback(User from, Integer messageId, String data) {
    long chat = from.getId();
    if (!botEnabled()) {
      send(chat, TEXT_PAUSED, null);
      return;
    }
    Optional<TgAccount> acc = accounts.byTelegramId(from.getId());
    if (acc.isEmpty()) {
      switch (data) {
        case CB_SIGNUP -> signup(from, messageId);
        case CB_DECLINE -> edit(chat, messageId, TEXT_DECLINED, null);
        case CB_RECOVER -> askRecover(chat);
        default -> send(chat, signupAllowed(from.getId()) ? "请先发 /start 授权开户。" : TEXT_NOT_OPEN, null);
      }
      return;
    }
    TgAccount a = acc.get();
    // 第 4 批 4b：找回申请的「不是我」「不输，直接冻结提现」「撤销」——只会减少权限，冻结、要重新授权时也照样处理
    if (data.startsWith(app.trading.notification.api.TelegramSender.ACTION_RECOVERY_OBJECT)
        || data.startsWith(app.trading.notification.api.TelegramSender.ACTION_RECOVERY_FREEZE)
        || data.startsWith(app.trading.notification.api.TelegramSender.ACTION_RECOVERY_REVOKE)) {
      recoveryAction(chat, a, data);
      return;
    }
    // 第 4 批 4c：自己冻结提现——只会减少权限，要重新授权时也照样处理
    if (CB_FREEZE.equals(data)) {
      send(chat, TEXT_FREEZE_ASK, keyboard(List.of(row(cb("确认冻结提现", CB_FREEZE_YES), cb("取消", CB_MENU)))));
      return;
    }
    if (CB_FREEZE_YES.equals(data)) {
      try {
        pins.freezeWithdraw(a.userId(), SOURCE_BOT_SELF, "客户在机器人里自己冻结");
        edit(chat, messageId, TEXT_FREEZE_DONE, null);
      } catch (RuntimeException e) {
        log.warn("机器人里冻结提现失败 user={}：{}", a.userId(), e.toString());
        send(chat, TEXT_FAILED, null);
      }
      return;
    }
    if (UserStatus.FROZEN == status(a)) {
      send(chat, TEXT_FROZEN, null);
      return;
    }
    if (CB_SESSIONS.equals(data)) {
      // 只读：冻结之外不用先重新授权（要重新授权时网页会话本来就已作废，列表是空的）
      send(chat, sessionsText(accounts.activeWebSessions(a.userId(), 10)),
          keyboard(List.of(row(cb("退出所有网页会话", CB_LOGOUT_ALL)), row(cb("返回", CB_MENU)))));
      return;
    }
    if (CB_LOGOUT_ALL.equals(data)) {
      // 「不是我，退出所有网页会话」：只会减少权限，冻结之外不用先重新授权
      accounts.logoutAllWeb(a.userId());
      send(chat, TEXT_LOGGED_OUT, null);
      return;
    }
    if (CB_REAUTH.equals(data)) {
      accounts.reauthorize(a.userId());
      edit(chat, messageId, "已重新授权。", null);
      send(chat, menuText(a), menuKeyboard(a.userId()));
      return;
    }
    if (!gate(chat, a)) return;
    if (CB_SIGNUP.equals(data) || CB_MENU.equals(data)) {
      // 已经开过户又点了旧的「授权开户」：不再开户，回主菜单
      send(chat, menuText(a), menuKeyboard(a.userId()));
    } else if (CB_PREFS.equals(data)) {
      send(chat, prefsText(), prefsKeyboard(a.userId()));
    } else if (data.startsWith(CB_PREF)) {
      togglePref(chat, messageId, a, data.substring(CB_PREF.length()));
    } else if (data.startsWith(CB_OPEN)) {
      openLink(chat, a, data.substring(CB_OPEN.length()));
    } else if (!CB_DECLINE.equals(data) && !CB_NOOP.equals(data)) {
      send(chat, menuText(a), menuKeyboard(a.userId()));
    }
  }

  /**
   * 退路模式（FX_TG_LOGIN_MODE=link）：现做一条 60 秒、只能用一次的链接，令牌放在网址 # 后面（不进服务器日志、不随请求发出），
   * 网页读出来用 POST 换会话；这条消息 60 秒后删掉。不排队、直接发（要拿到消息编号才能删）
   */
  private void openLink(long chat, TgAccount a, String path) {
    if (!props.linkMode() || !props.hasWebUrl()) {
      send(chat, menuText(a), menuKeyboard(a.userId()));
      return;
    }
    String token;
    try {
      token = accounts.createLoginLink(a.userId(), path);
    } catch (RuntimeException e) {
      log.warn("做一次性链接失败：{}", e.toString());
      send(chat, TEXT_FAILED, null);
      return;
    }
    InlineKeyboardButton b = InlineKeyboardButton.builder().text("打开").url(props.base() + "/tg-link#t=" + token).build();
    try {
      Message m = api.execute(SendMessage.builder().chatId(chat).text(TEXT_LINK).replyMarkup(keyboard(List.of(row(b)))).build());
      if (m != null && m.getMessageId() != null) {
        Integer id = m.getMessageId();
        DELETER.schedule(() -> {
          try {
            api.execute(DeleteMessage.builder().chatId(chat).messageId(id).build());
          } catch (Exception e) {
            log.debug("删一次性链接消息失败：{}", props.scrub(e.toString()));
          }
        }, linkDeleteMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
      }
    } catch (Exception e) {
      log.warn("发一次性链接失败：{}", props.scrub(e.toString()));
    }
  }

  // ---------- 找回（第 4 批 4b） ----------

  private void askRecover(long chat) {
    awaitingRecover.put(chat, System.nanoTime());
    send(chat, TEXT_RECOVER_ASK, null);
  }

  /** 申请人发来"客户号 姓名 证件号后4位"：交给账户模块；信息对不对都回同一句 */
  private void applyRecover(User from, String text) {
    long chat = from.getId();
    var m = RECOVER_INPUT.matcher(text);
    if (!m.matches()) {
      send(chat, TEXT_RECOVER_FORMAT, null);
      return;
    }
    awaitingRecover.remove(chat);
    long customerNo;
    try {
      customerNo = Long.parseLong(m.group(1));
    } catch (NumberFormatException e) {
      send(chat, TEXT_RECOVER_FORMAT, null);
      return;
    }
    app.trading.account.api.RecoveryApi.ApplyResult r;
    try {
      r = recovery.applyRebind(from.getId(), from.getUserName(), customerNo, m.group(2), m.group(3));
    } catch (RuntimeException e) {
      log.warn("找回申请出错 tg={}：{}", from.getId(), e.toString());
      send(chat, TEXT_FAILED, null);
      return;
    }
    send(chat, switch (r) {
      case ACCEPTED -> TEXT_RECOVER_APPLIED;
      case TOO_FREQUENT -> TEXT_RECOVER_FREQUENT;
      case BANNED -> TEXT_RECOVER_BANNED;
      case PAUSED -> TEXT_RECOVER_PAUSED;
    }, null);
  }

  /** 原 TG 点了找回申请通知里的按钮 */
  private void recoveryAction(long chat, TgAccount a, String data) {
    int i = data.indexOf(':');
    long id;
    try {
      id = Long.parseLong(data.substring(i + 1));
    } catch (NumberFormatException e) {
      return;
    }
    String p = data.substring(0, i + 1);
    try {
      if (p.equals(app.trading.notification.api.TelegramSender.ACTION_RECOVERY_OBJECT)) {
        String reply = recovery.object(a.userId(), id);
        List<InlineKeyboardRow> rows = new ArrayList<>();
        if (props.hasWebUrl()) rows.add(row(login("输提现密码（输对不冻结）", "/me/recovery?id=" + id)));
        rows.add(row(cb("不输，直接冻结提现", app.trading.notification.api.TelegramSender.ACTION_RECOVERY_FREEZE + id)));
        send(chat, reply, reply.startsWith("已记下") ? keyboard(rows) : null);
      } else if (p.equals(app.trading.notification.api.TelegramSender.ACTION_RECOVERY_FREEZE)) {
        send(chat, recovery.freezeNow(a.userId(), id), null);
      } else {
        send(chat, recovery.revokeByOwner(a.userId(), id), null);
      }
    } catch (RuntimeException e) {
      log.warn("找回申请按钮处理出错 user={} id={}：{}", a.userId(), id, e.toString());
      send(chat, TEXT_FAILED, null);
    }
  }

  private void signup(User from, Integer messageId) {
    long chat = from.getId();
    if (!signupAllowed(from.getId())) {
      edit(chat, messageId, TEXT_NOT_OPEN, null);
      return;
    }
    TgAccount a;
    try {
      a = accounts.openAccount(from.getId(), from.getUserName());
    } catch (RuntimeException e) {
      log.warn("机器人开户失败 tg={}：{}", from.getId(), e.toString());
      send(chat, TEXT_FAILED, null);
      return;
    }
    edit(chat, messageId, "已授权开户。", null);
    send(chat, welcomeText(a), menuKeyboard(a.userId()));
  }

  private void togglePref(long chat, Integer messageId, TgAccount a, String code) {
    TelegramCategory c;
    try {
      c = TelegramCategory.valueOf(code);
    } catch (IllegalArgumentException e) {
      return;
    }
    if (!c.closable) {
      send(chat, c.nameZh + "不能关闭。", null);
      return;
    }
    Map<TelegramCategory, Boolean> now = prefs.telegramPrefs(a.userId());
    prefs.setTelegramPref(a.userId(), c, !now.getOrDefault(c, true));
    edit(chat, messageId, prefsText(), prefsKeyboard(a.userId()));
  }

  // ---------- 检查 ----------

  private boolean botEnabled() {
    return !"false".equalsIgnoreCase(config.text(ConfigKey.TELEGRAM_ENABLED, "true").strip());
  }

  /** 开户名单：空 = 谁都不能开户；* = 不限；否则逗号分隔的 TG 用户编号 */
  boolean signupAllowed(long tgId) {
    String v = config.text(ConfigKey.TELEGRAM_SIGNUP_ALLOW_IDS, "").replaceAll("\\s", "");
    if (v.isEmpty()) return false;
    if ("*".equals(v)) return true;
    for (String s : v.split(",")) if (s.equals(String.valueOf(tgId))) return true;
    return false;
  }

  private UserStatus status(TgAccount a) {
    return account.status(a.userId());
  }

  /** 冻结、要重新授权时回应并返回 false */
  private boolean gate(long chat, TgAccount a) {
    if (UserStatus.FROZEN == status(a)) {
      send(chat, TEXT_FROZEN, null);
      return false;
    }
    Optional<SecurityEvent> ev = account.lastSecurityEventSince(a.userId(), a.authorizedAt());
    if (ev.isPresent()) {
      send(chat, "你的账户最近有安全变更（" + eventName(ev.get().type()) + "）。为了安全，请确认是你本人后重新授权。",
          keyboard(List.of(row(cb("重新授权", CB_REAUTH)))));
      return false;
    }
    return true;
  }

  private static final java.time.format.DateTimeFormatter SESSION_TIME = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm 'UTC'");

  /** 「当前网页会话」的文字：设备 / 浏览器、IP 前三段、登录时间、怎么登录的 */
  static String sessionsText(List<TelegramAccountApi.WebSession> list) {
    if (list.isEmpty()) return "现在没有登录着的网页会话。";
    StringBuilder sb = new StringBuilder("当前网页会话（" + list.size() + " 个，新的在前）：");
    int i = 1;
    for (TelegramAccountApi.WebSession s : list) {
      String how = switch (s.kind()) {
        case "BOT" -> "从机器人进入";
        case "EMERGENCY" -> "应急开关下用 Telegram 登录";
        default -> "网页登录";
      };
      sb.append('\n').append(i++).append(". ").append(s.device()).append("，IP ").append(s.ip()).append("，")
          .append(s.createdAt() == null ? "时间未知" : s.createdAt().format(SESSION_TIME)).append(" 登录（").append(how).append("）");
    }
    sb.append("\n不认识的，点「退出所有网页会话」。");
    return sb.toString();
  }

  static String eventName(String type) {
    return switch (type) {
      case SecurityEvent.PASSWORD_CHANGE -> "改了密码";
      case SecurityEvent.PASSWORD_RESET -> "重置了密码";
      case SecurityEvent.EMAIL_CHANGE -> "改了邮箱";
      case SecurityEvent.FREEZE -> "账户被冻结过";
      case SecurityEvent.UNFREEZE -> "账户刚解冻";
      case SecurityEvent.REBIND -> "换绑了 Telegram";
      case SecurityEvent.PIN_SET, SecurityEvent.PIN_CHANGE, SecurityEvent.PIN_RESET -> "提现密码有变化";
      case SecurityEvent.KYC_FIRST -> "提交了身份认证";
      case SecurityEvent.TG_WEB_LOGIN -> "用 Telegram 登录了网页";
      default -> "安全设置有变化";
    };
  }

  private void syncUsername(TgAccount a, User from) {
    String u = from.getUserName();
    if (u != null && !u.equals(a.username())) accounts.updateUsername(a.userId(), u);
  }

  // ---------- 文案和按钮 ----------

  private String welcomeText(TgAccount a) {
    return "开户成功，客户号 #" + a.userId() + "。可以充值、交易了。" + noWebHint();
  }

  private String menuText(TgAccount a) {
    return "客户号 #" + a.userId() + "。" + noWebHint();
  }

  private String noWebHint() {
    return props.hasWebUrl() ? "" : "\n\n网页交易入口还没开通，开通后这里会出现「打开交易」等按钮。";
  }

  static String prefsText() {
    return "Telegram 提醒设置（只影响你自己）。点一下开 / 关；保证金预警和强平、安全提醒不能关。";
  }

  /** 欢迎卡片 / 主菜单：「打开交易」和快捷入口是 login_url 按钮（网页地址没配置时不显示），「通知设置」 */
  private InlineKeyboardMarkup menuKeyboard(long userId) {
    List<InlineKeyboardRow> rows = new ArrayList<>();
    if (props.hasWebUrl()) {
      rows.add(row(login("打开交易", "/trade")));
      rows.add(row(login("行情", "/markets"), login("持仓", "/positions")));
      rows.add(row(login("充值", "/assets"), login("提现", "/assets/withdraw")));
    }
    // 第 4 批 4c：自己冻结提现（BOT_SELF）；冻结着的给"解除"入口（网页输提现密码，24 小时后自动解冻）
    boolean selfFrozen = false;
    try {
      selfFrozen = pins.status(userId).frozenBy(SOURCE_BOT_SELF);
    } catch (RuntimeException e) {
      log.debug("查提现冻结失败：{}", e.toString());
    }
    if (selfFrozen && props.hasWebUrl()) rows.add(row(login("解除自己设的提现冻结", "/me/withdraw-freeze")));
    else if (!selfFrozen) rows.add(row(cb("冻结提现", CB_FREEZE)));
    rows.add(row(cb("通知设置", CB_PREFS)));
    // 验收反馈 B：不再每次登录都发"刚在浏览器里登录"；「当前网页会话」随时能看，旁边就是「退出所有网页会话」
    rows.add(row(cb("当前网页会话", CB_SESSIONS), cb("退出所有网页会话", CB_LOGOUT_ALL)));
    return keyboard(rows);
  }

  private InlineKeyboardMarkup prefsKeyboard(long userId) {
    Map<TelegramCategory, Boolean> p = prefs.telegramPrefs(userId);
    List<InlineKeyboardRow> rows = new ArrayList<>();
    for (TelegramCategory c : TelegramCategory.values()) {
      if (c.closable) rows.add(row(cb((p.getOrDefault(c, true) ? "✅ " : "⬜ ") + c.nameZh, CB_PREF + c.name())));
      else rows.add(row(cb("🔒 " + c.nameZh + "（不能关）", CB_NOOP)));
    }
    rows.add(row(cb("返回", CB_MENU)));
    return keyboard(rows);
  }

  /** 打开网页的按钮：一键登录模式是 login_url 按钮（落地页 /api/tg/login），退路模式是回调按钮 open:路径 */
  InlineKeyboardButton login(String text, String path) {
    return TelegramSenderImpl.open(props, text, path);
  }

  static InlineKeyboardButton cb(String text, String data) {
    return InlineKeyboardButton.builder().text(text).callbackData(data).build();
  }

  static InlineKeyboardRow row(InlineKeyboardButton... b) {
    return new InlineKeyboardRow(b);
  }

  static InlineKeyboardMarkup keyboard(List<InlineKeyboardRow> rows) {
    return InlineKeyboardMarkup.builder().keyboard(rows).build();
  }

  // ---------- 发送 ----------

  private void send(long chat, String text, InlineKeyboardMarkup kb) {
    outbox.offer(chat, Priority.HIGH, SendMessage.builder().chatId(chat).text(text).replyMarkup(kb).build(), null);
  }

  private void edit(long chat, Integer messageId, String text, InlineKeyboardMarkup kb) {
    if (messageId == null) {
      send(chat, text, kb);
      return;
    }
    outbox.offer(chat, Priority.HIGH, EditMessageText.builder().chatId(chat).messageId(messageId).text(text).replyMarkup(kb).build(), null);
  }

  private void answer(String callbackId) {
    try {
      api.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());
    } catch (Exception e) {
      // 只是让按钮停止转圈；失败不影响处理
      log.debug("answerCallbackQuery 失败：{}", props.scrub(e.toString()));
    }
  }
}
