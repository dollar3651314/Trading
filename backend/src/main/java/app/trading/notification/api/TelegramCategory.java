package app.trading.notification.api;

import java.util.Map;

/**
 * Telegram 提醒的分类（TG 机器人第 1 批）：客户在机器人"通知设置"里按类开关；强平预警 / 强平（{@link #RISK}）、安全提醒（{@link #SECURITY}）<b>不能关</b>。
 * 每类有一个发送优先级（{@link TelegramSender.Priority}）：排队发送时高的先发。
 */
public enum TelegramCategory {
  /** 成交、开仓、平仓 */
  TRADE("成交和持仓", true, TelegramSender.Priority.NORMAL),
  /** 止盈止损触发 */
  TPSL("止盈止损", true, TelegramSender.Priority.NORMAL),
  /** 充值到账、提现进度 */
  FUNDS("充值和提现", true, TelegramSender.Priority.NORMAL),
  /** KYC 结果 */
  KYC("身份认证结果", true, TelegramSender.Priority.LOW),
  /** 后台手动发的、其他 */
  SYSTEM("系统消息", true, TelegramSender.Priority.LOW),
  /** 保证金预警、强平：不能关 */
  RISK("保证金预警和强平", false, TelegramSender.Priority.HIGH),
  /** 安全提醒（冻结等）：不能关 */
  SECURITY("安全提醒", false, TelegramSender.Priority.HIGH);

  public final String nameZh;
  /** 客户能不能关 */
  public final boolean closable;
  public final TelegramSender.Priority priority;

  TelegramCategory(String nameZh, boolean closable, TelegramSender.Priority priority) {
    this.nameZh = nameZh;
    this.closable = closable;
    this.priority = priority;
  }

  private static final Map<String, TelegramCategory> BY_TEMPLATE = Map.ofEntries(
      Map.entry("ORDER_FILLED", TRADE), Map.entry("POSITION_OPENED", TRADE), Map.entry("POSITION_CLOSED", TRADE), Map.entry("POSITION_PARTIAL_CLOSED", TRADE),
      Map.entry("TP_SL_TRIGGERED", TPSL), Map.entry("MARGIN_CALL", RISK), Map.entry("LIQUIDATED", RISK), Map.entry("DEPOSIT_CREDITED", FUNDS),
      Map.entry("WITHDRAW_STATUS", FUNDS), Map.entry("KYC_APPROVED", KYC), Map.entry("KYC_REJECTED", KYC), Map.entry("ACCOUNT_FROZEN", SECURITY),
      Map.entry("LOGIN_NEW_DEVICE", SECURITY), Map.entry("WITHDRAW_PIN_SET", SECURITY), Map.entry("WITHDRAW_PIN_CHANGED", SECURITY),
      Map.entry("WITHDRAW_PIN_LOCKED", SECURITY), Map.entry("WITHDRAW_FROZEN", SECURITY), Map.entry("WHITELIST_ADDED", SECURITY),
      Map.entry("WHITELIST_SOON", SECURITY), Map.entry("WHITELIST_ACTIVE", SECURITY));

  /** 模板编号 → 分类；不认识的（后台新加的模板、手动发送）算 {@link #SYSTEM} */
  public static TelegramCategory ofTemplate(String templateCode) {
    return templateCode == null ? SYSTEM : BY_TEMPLATE.getOrDefault(templateCode, SYSTEM);
  }
}
