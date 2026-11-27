package app.trading.wallet.withdraw;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** wallet_withdrawal 一行。 */
class WithdrawalRow {
  Long id;
  /** 对外编号（雪花 ID；V22 之前的旧数据等于 id） */
  Long publicNo;
  long userId;
  String network;
  String address;
  BigDecimal amount;
  BigDecimal fee;
  String status;
  /** WEB 原规则 / TG TG 规则（一律人工审核，第 4 批） */
  String ruleType = "WEB";
  /** 提交入口：WEB 网页登录的会话 / BOT 从机器人进入的会话 */
  String source = "WEB";
  /** 没邮箱的 TG 客户的首笔：审核前要人工核实（08 页第 0 节） */
  boolean firstCheck;
  Long firstCheckedBy;
  LocalDateTime coolingEndsAt;
  LocalDateTime delayEndsAt;
  String txHash;
  String rejectReason;
  LocalDateTime createdAt;
  /** 最近一次自动审批判定（v2）；没有时为空 */
  Long lastCheckId;
}
