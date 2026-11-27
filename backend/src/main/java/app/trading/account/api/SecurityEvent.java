package app.trading.account.api;

import java.time.LocalDateTime;

/**
 * 账号安全事件（表 account_security_event，v2 提现自动审批 R6）。
 *
 * @param type PASSWORD_CHANGE 改密码 / PASSWORD_RESET 忘记密码重置 / EMAIL_CHANGE 改邮箱（v1 没有这个功能，先留类型）/ FREEZE 冻结 / UNFREEZE 解冻
 * @param at 发生时间（UTC）
 */
public record SecurityEvent(String type, LocalDateTime at) {

  public static final String PASSWORD_CHANGE = "PASSWORD_CHANGE";
  public static final String PASSWORD_RESET = "PASSWORD_RESET";
  public static final String EMAIL_CHANGE = "EMAIL_CHANGE";
  public static final String FREEZE = "FREEZE";
  public static final String UNFREEZE = "UNFREEZE";
  /** TG 机器人第 4 批：第一次设提现密码 / 改提现密码 / 忘记后重设（重设后 48 小时不能提现）/ TG 客户第一次提交 KYC（03 页第 4 节、场景 H） */
  public static final String PIN_SET = "PIN_SET";
  public static final String PIN_CHANGE = "PIN_CHANGE";
  public static final String PIN_RESET = "PIN_RESET";
  /** 第 4 批 4b：人工换绑 Telegram（48 小时不能提现） */
  public static final String REBIND = "REBIND";
  public static final String KYC_FIRST = "KYC_FIRST";
}
