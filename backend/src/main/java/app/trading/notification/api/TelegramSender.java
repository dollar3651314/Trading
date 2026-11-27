package app.trading.notification.api;

/**
 * 通知模块把 Telegram 提醒交出去的接口（TG 机器人第 1 批，技术方案 05 页第 2 节："通知模块通过一个接口把消息交出去"）。
 * 由 telegram 模块实现；通知模块不依赖 telegram 模块。实现只排队、不等发完（发送在机器人自己的发送线程里，按优先级、每个聊天每秒 ≤ 1 条）。
 */
public interface TelegramSender {

  /** 发送优先级：三档，高的先发。HIGH 安全提醒、强平预警和对客户操作的回复；NORMAL 成交、资金；LOW 其他 */
  enum Priority {
    HIGH,
    NORMAL,
    LOW
  }

  /** 交出去的结果 */
  enum Result {
    /** 已排队，等发送线程发出 */
    QUEUED,
    /** 这个客户没有 Telegram 账户 */
    NOT_LINKED,
    /** 机器人没开通（没配令牌）或后台关了机器人 */
    BOT_OFF
  }

  /**
   * @param text 纯文本（不解析 Markdown / HTML）
   * @param viewPath 提醒里「查看」按钮要打开的网页客户端路径（例如 {@code /positions}）；为空不加按钮。按钮是 login_url 按钮，网页地址没配置时也不加
   * @param done 发完（或确定发不出）时回调：成功传 {@code null}，失败传原因（不含令牌）；只在返回 {@link Result#QUEUED} 时回调。
   *     发送队列在内存里：进程重启时还没发出的不会回调（通知记录停在"发送中"）
   */
  Result send(long userId, String text, Priority priority, String viewPath, java.util.function.Consumer<String> done);
}
