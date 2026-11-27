/**
 * 权限点字典（真源）。每个后台页面和操作都对应一个权限点；highRisk 标记高危权限（修复 fxsystem S6：高危权限全部登记）。
 * Permission dictionary. Every page/action maps to a code; high-risk ones are flagged.
 */
import type { PermissionDef } from '../api/types';

export const PERMISSIONS = [
  { code: 'dashboard:view', module: 'dashboard', highRisk: false },

  { code: 'customer:view', module: 'customer', highRisk: false },
  { code: 'customer:edit', module: 'customer', highRisk: true },
  { code: 'customer:freeze', module: 'customer', highRisk: true },
  { code: 'customer:adjust_balance', module: 'customer', highRisk: true },
  // 第 4 批：人工解除提现冻结（重新输入管理员密码、告警邮件），默认只有超管
  { code: 'withdraw:unfreeze', module: 'customer', highRisk: true },
  // 第 4 批 4b：找回申请（Amos 一人审核），默认只有超管
  { code: 'recovery:review', module: 'customer', highRisk: true },

  { code: 'kyc:view', module: 'kyc', highRisk: false },
  { code: 'kyc:review', module: 'kyc', highRisk: false },

  { code: 'deposit:view', module: 'funds', highRisk: false },
  { code: 'withdrawal:view', module: 'funds', highRisk: false },
  { code: 'withdrawal:review', module: 'funds', highRisk: false },
  { code: 'withdrawal:emergency_cancel', module: 'funds', highRisk: true },
  { code: 'withdrawal:auto_rule_edit', module: 'funds', highRisk: true },
  { code: 'recon:view', module: 'funds', highRisk: false },
  { code: 'recon:resolve', module: 'funds', highRisk: false },
  { code: 'provider:view', module: 'funds', highRisk: false },
  { code: 'provider:operate', module: 'funds', highRisk: true },
  { code: 'task:view', module: 'funds', highRisk: false },
  { code: 'task:retry', module: 'funds', highRisk: false },

  { code: 'order:view', module: 'trading', highRisk: false },
  { code: 'position:view', module: 'trading', highRisk: false },
  { code: 'position:liquidate', module: 'trading', highRisk: true },
  { code: 'pending:view', module: 'trading', highRisk: false },
  { code: 'pending:force_cancel', module: 'trading', highRisk: true },

  { code: 'risk:view', module: 'risk', highRisk: false },
  { code: 'risk:global_pause', module: 'risk', highRisk: true },
  { code: 'risk:auto_liquidation', module: 'risk', highRisk: true },
  { code: 'risk:symbol_pause', module: 'risk', highRisk: true },
  { code: 'risk:threshold', module: 'risk', highRisk: true },
  { code: 'risk:user_limit', module: 'risk', highRisk: true },

  { code: 'symbol:view', module: 'market', highRisk: false },
  { code: 'symbol:edit', module: 'market', highRisk: true },
  { code: 'symbol:create', module: 'market', highRisk: true },
  { code: 'session:view', module: 'market', highRisk: false },
  { code: 'session:edit', module: 'market', highRisk: false },
  { code: 'fx:view', module: 'market', highRisk: false },

  { code: 'quote_service:view', module: 'quote_service', highRisk: false },
  { code: 'quote_key:operate', module: 'quote_service', highRisk: true },
  { code: 'quote_limit:edit', module: 'quote_service', highRisk: true },
  { code: 'quote_license:edit', module: 'quote_service', highRisk: true },

  { code: 'params:view', module: 'params', highRisk: false },
  { code: 'params:edit', module: 'params', highRisk: true },

  { code: 'notify_template:view', module: 'notify', highRisk: false },
  { code: 'notify_template:edit', module: 'notify', highRisk: false },
  { code: 'notify:send', module: 'notify', highRisk: true },
  { code: 'notify_record:view', module: 'notify', highRisk: false },

  { code: 'admin:view', module: 'system', highRisk: false },
  { code: 'admin:edit', module: 'system', highRisk: true },
  { code: 'role:view', module: 'system', highRisk: false },
  { code: 'role:edit', module: 'system', highRisk: true },
  { code: 'menu:view', module: 'system', highRisk: false },
  { code: 'menu:edit', module: 'system', highRisk: false },
  { code: 'permission:view', module: 'system', highRisk: false },
  { code: 'audit:view', module: 'system', highRisk: false },
  { code: 'config:view', module: 'system', highRisk: false },
  { code: 'config:edit', module: 'system', highRisk: true },
  // 第 5 批：看"应急开关"页（TB-34），默认只有超管；能开关的只有 Amos 的后台账号（后端配置写死编号），不靠权限点
  { code: 'emergency:view', module: 'system', highRisk: true },
] as const satisfies readonly PermissionDef[];

export type PermissionCode = (typeof PERMISSIONS)[number]['code'];

export const ALL_PERMISSION_CODES: string[] = PERMISSIONS.map((p) => p.code);

export const PERMISSION_MODULES = ['dashboard', 'customer', 'kyc', 'funds', 'trading', 'risk', 'market', 'params', 'notify', 'system'] as const;

/** 超级管理员角色编码：只有超级管理员才能把这个角色分配给别人（修复 fxsystem S4） */
export const SUPER_ADMIN_ROLE = 'super_admin';
