package app.trading.admin.security;

import java.util.Arrays;
import java.util.Optional;

/**
 * 权限点（后台方案第 2 节）：和后台原型 {@code web/admin/src/config/permissions.ts} 一一对应（测试 {@code PermissionCatalogTest} 核对，不一致 CI 失败）。
 * highRisk 标记高危权限（修复现有系统 S6：高危权限全部登记）。
 */
public enum Permission {
  DASHBOARD_VIEW("dashboard:view", "dashboard", false),

  CUSTOMER_VIEW("customer:view", "customer", false),
  /** v2（E1）：编辑客户资料（姓名、出生日期、国籍） */
  CUSTOMER_EDIT("customer:edit", "customer", true),
  CUSTOMER_FREEZE("customer:freeze", "customer", true),
  CUSTOMER_ADJUST_BALANCE("customer:adjust_balance", "customer", true),
  /** 第 4 批：人工解除提现冻结（高危：重新输入管理员密码、原因必填、告警邮件）。默认只有超管有（超管自带全部权限，其他角色要单独分配） */
  WITHDRAW_UNFREEZE("withdraw:unfreeze", "customer", true),
  /** 第 4 批 4b：找回申请（人工换绑、没邮箱忘记提现密码）的查看和审核——Amos 一人审核，默认只有超管有（批准、维持要再输管理员密码、告警邮件） */
  RECOVERY_REVIEW("recovery:review", "customer", true),

  KYC_VIEW("kyc:view", "kyc", false),
  KYC_REVIEW("kyc:review", "kyc", false),

  DEPOSIT_VIEW("deposit:view", "funds", false),
  WITHDRAWAL_VIEW("withdrawal:view", "funds", false),
  WITHDRAWAL_REVIEW("withdrawal:review", "funds", false),
  WITHDRAWAL_EMERGENCY_CANCEL("withdrawal:emergency_cancel", "funds", true),
  /** v2 提现自动审批：改总开关和 13 条规则（查看用 withdrawal:view）。不放进系统配置页，否则有 config:edit 的人就能绕过去放宽审核 */
  WITHDRAWAL_AUTO_RULE_EDIT("withdrawal:auto_rule_edit", "funds", true),
  RECON_VIEW("recon:view", "funds", false),
  RECON_RESOLVE("recon:resolve", "funds", false),
  /** 第 1 批：钱包提供方页（接口状态、可用余额、网络开关、切换记录） */
  PROVIDER_VIEW("provider:view", "funds", false),
  /** 第 1 批：充值网络开关、切换钱包提供方（高危，原因必填，TY-3） */
  PROVIDER_OPERATE("provider:operate", "funds", true),
  TASK_VIEW("task:view", "funds", false),
  TASK_RETRY("task:retry", "funds", false),

  ORDER_VIEW("order:view", "trading", false),
  POSITION_VIEW("position:view", "trading", false),
  POSITION_LIQUIDATE("position:liquidate", "trading", true),
  PENDING_VIEW("pending:view", "trading", false),
  PENDING_FORCE_CANCEL("pending:force_cancel", "trading", true),

  RISK_VIEW("risk:view", "risk", false),
  RISK_GLOBAL_PAUSE("risk:global_pause", "risk", true),
  RISK_AUTO_LIQUIDATION("risk:auto_liquidation", "risk", true),
  RISK_SYMBOL_PAUSE("risk:symbol_pause", "risk", true),
  RISK_THRESHOLD("risk:threshold", "risk", true),
  RISK_USER_LIMIT("risk:user_limit", "risk", true),

  SYMBOL_VIEW("symbol:view", "market", false),
  SYMBOL_EDIT("symbol:edit", "market", true),
  /** v2（E3）：新增品种 */
  SYMBOL_CREATE("symbol:create", "market", true),
  SESSION_VIEW("session:view", "market", false),
  SESSION_EDIT("session:edit", "market", false),
  FX_VIEW("fx:view", "market", false),

  /** 第 3 批：报价服务菜单（外部客户和 Key、限额和用量、授权登记和总开关、报价源状态）查看 */
  QUOTE_SERVICE_VIEW("quote_service:view", "quote_service", false),
  /** 第 3 批：建外部客户、建 / 轮换 / 停用 Key（高危，原因必填） */
  QUOTE_KEY_OPERATE("quote_key:operate", "quote_service", true),
  /** 第 3 批：改某个 Key 的限额、改默认限额（高危，原因必填） */
  QUOTE_LIMIT_EDIT("quote_limit:edit", "quote_service", true),
  /** 第 3 批：报价源授权登记 / 续登记（高危，原因必填）。对外开放总开关不用权限点：只有超管能改 */
  QUOTE_LICENSE_EDIT("quote_license:edit", "quote_service", true),

  PARAMS_VIEW("params:view", "params", false),
  PARAMS_EDIT("params:edit", "params", true),

  NOTIFY_TEMPLATE_VIEW("notify_template:view", "notify", false),
  NOTIFY_TEMPLATE_EDIT("notify_template:edit", "notify", false),
  NOTIFY_SEND("notify:send", "notify", true),
  NOTIFY_RECORD_VIEW("notify_record:view", "notify", false),

  ADMIN_VIEW("admin:view", "system", false),
  ADMIN_EDIT("admin:edit", "system", true),
  ROLE_VIEW("role:view", "system", false),
  ROLE_EDIT("role:edit", "system", true),
  MENU_VIEW("menu:view", "system", false),
  MENU_EDIT("menu:edit", "system", false),
  PERMISSION_VIEW("permission:view", "system", false),
  AUDIT_VIEW("audit:view", "system", false),
  CONFIG_VIEW("config:view", "system", false),
  CONFIG_EDIT("config:edit", "system", true);

  public final String code;
  public final String module;
  public final boolean highRisk;

  Permission(String code, String module, boolean highRisk) {
    this.code = code;
    this.module = module;
    this.highRisk = highRisk;
  }

  public static Optional<Permission> of(String code) {
    return Arrays.stream(values()).filter(p -> p.code.equals(code)).findFirst();
  }
}
