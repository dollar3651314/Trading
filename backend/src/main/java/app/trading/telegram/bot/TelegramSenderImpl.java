package app.trading.telegram.bot;

import app.trading.account.api.TelegramAccountApi;
import app.trading.common.config.ConfigKey;
import app.trading.common.config.SystemConfig;
import app.trading.notification.api.TelegramSender;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.LoginUrl;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * 通知模块交来的提醒：找到客户的 TG 账户（私聊的聊天编号就是 TG 用户编号），按优先级排进发送队列。
 * 提醒里的「查看」直达对应页面（一键登录模式是 login_url 按钮，退路模式是回调按钮，网页地址没配置时不加）；可以另外加回调按钮（{@link Action}）。没配令牌、后台关了机器人时返回 {@link Result#BOT_OFF}。
 */
public class TelegramSenderImpl implements TelegramSender {

  /** Telegram 一条消息最多 4096 个字符 */
  static final int MAX_TEXT = 4096;

  private final TelegramProperties props;
  private final TelegramAccountApi accounts;
  private final SystemConfig config;
  private final TelegramOutbox outbox;

  public TelegramSenderImpl(TelegramProperties props, TelegramAccountApi accounts, SystemConfig config, TelegramOutbox outbox) {
    this.props = props;
    this.accounts = accounts;
    this.config = config;
    this.outbox = outbox;
  }

  @Override
  public Result send(long userId, String text, Priority priority, String viewPath, List<Action> actions, Consumer<String> done) {
    if (outbox == null || !props.configured() || "false".equalsIgnoreCase(config.text(ConfigKey.TELEGRAM_ENABLED, "true").strip())) return Result.BOT_OFF;
    var acc = accounts.byUserId(userId);
    if (acc.isEmpty()) return Result.NOT_LINKED;
    long chat = acc.get().telegramId();
    String t = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT - 1) + "…" : text;
    SendMessage.SendMessageBuilder<?, ?> m = SendMessage.builder().chatId(chat).text(t);
    List<InlineKeyboardRow> rows = new ArrayList<>();
    if (viewPath != null && props.hasWebUrl()) rows.add(new InlineKeyboardRow(open(props, "查看", viewPath)));
    for (Action a : actions == null ? List.<Action>of() : actions) {
      rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder().text(a.text()).callbackData(a.data()).build()));
    }
    if (!rows.isEmpty()) m.replyMarkup(InlineKeyboardMarkup.builder().keyboard(rows).build());
    outbox.offer(chat, priority, m.build(), done);
    return Result.QUEUED;
  }

  /**
   * 打开网页的按钮（第 2 批）：一键登录模式是 login_url 按钮（落地页验过身份后直达 path）；
   * 退路模式是回调按钮 {@code open:path}，点了机器人现发一条 60 秒一次性链接。网页地址没配置时调用方不加这个按钮
   */
  static InlineKeyboardButton open(TelegramProperties props, String text, String path) {
    if (props.linkMode()) return InlineKeyboardButton.builder().text(text).callbackData(UpdateHandler.CB_OPEN + path).build();
    return InlineKeyboardButton.builder().text(text).loginUrl(new LoginUrl(props.loginUrl(path))).build();
  }
}
