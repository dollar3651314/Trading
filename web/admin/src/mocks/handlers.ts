/**
 * 模拟接口实现。函数名和参数与 src/api 一一对应；操作会修改内存数据并写审计日志。
 * 服务端校验（权限、超管分配、余额调整上限等）在这里也模拟一遍，确保前端不是唯一防线。
 */
import {
  ApiError,
  type AdjustLimits,
  type AdminInput,
  type AdminSession,
  type AdminUser,
  type AuditLog,
  type AuditQuery,
  type AuditValue,
  type CurrentAdmin,
  type CustomerDetail,
  type WithdrawSafety,
  freezesOf,
  type HeldWithdrawal,
  type Recovery,
  type RecoveryDetail,
  type CustomerProfileInput,
  type CustomerQuery,
  type CustomerSummary,
  type DashboardStats,
  type DepositQuery,
  type Deposit,
  type Holiday,
  type HolidayInput,
  type KycApplication,
  type KycQuery,
  type LedgerEntry,
  type ManualNotifyInput,
  type MenuBadges,
  type MenuNode,
  type NotifyRecordQuery,
  type NotifyTemplate,
  type Page,
  type ClosedPosition,
  type PageQuery,
  type ReconQuery,
  type Role,
  type RoleInput,
  type SymbolQuery,
  type TaskQuery,
  type TemporaryPassword,
  type TradeQuery,
  type TradingParams,
  type TradingParamsInput,
  type RiskOverview,
  type SymbolCreateInput,
  type SymbolPage,
  type SymbolPatch,
  type TradingSymbol,
  type WithdrawalDetail,
  type AutoApproveChange,
  type AutoApproveSettings,
  type AutoApproveSettingsInput,
  type AutoReviewSummary,
  type WithdrawalQuery,
  type WithdrawalSummary,
  type Withdrawal,
  PaymentOverview,
  PaymentProvider,
  ReconStatus,
} from '../api/types';
import { AUTO_RULE_EDIT_PERM, AUTO_RULES, crossRuleProblems, ruleParamProblem } from '../config/autoApprove';
import { defaultMarkup } from '../config/constants';
import { MENU, type MenuItemDef } from '../config/menu';
import { PERMISSIONS, SUPER_ADMIN_ROLE } from '../config/permissions';
import { sanitizeAuditPayload } from '../utils/audit';
import { compareAmounts, fromScaled, isValidAmountInput, sumAmounts, toScaled, usagePercent } from '../utils/money';
import { CURRENCY_RE, SYMBOL_CODE_RE, birthDateProblems, nameProblems, nationalityProblems, simStartPriceProblems } from '../utils/validators';
import * as db from './data/db';
import { NOW_MS, createRng, minutesAgo, nowString } from './data/seed';
import * as auto from './data/autoApprove';
import { emptyPage, matches, mutate, paginate, respond } from './runtime';

// ============ 工具 ============
function currentAdmin(): AdminUser {
  return db.admins.find((a) => a.id === db.session.adminId) ?? db.admins[0];
}

function permissionsOf(admin: AdminUser): Set<string> {
  const set = new Set<string>();
  admin.roleIds.forEach((rid) => db.roles.find((r) => r.id === rid)?.permissions.forEach((p) => set.add(p)));
  return set;
}

function isSuper(admin: AdminUser): boolean {
  return admin.roleIds.some((rid) => db.roles.find((r) => r.id === rid)?.code === SUPER_ADMIN_ROLE);
}

/** 模拟服务端的权限检查（没有权限返回 403） */
function requirePerm(code: string): void {
  if (!permissionsOf(currentAdmin()).has(code)) throw new ApiError('errors.forbidden');
}

function requireReason(reason: string | undefined): string {
  const r = (reason ?? '').trim();
  if (!r) throw new ApiError('errors.reasonRequired');
  return r;
}

/** 审计里不记录任何密码字段（修复 fxsystem S5）：服务端写入前就过滤 */
const scrub = sanitizeAuditPayload;

let auditSeq = 240001;
function audit(action: string, target: string, highRisk: boolean, reason: string | null, before: AuditLog['before'], after: AuditLog['after']): void {
  db.auditLogs.unshift({
    id: `AU-${auditSeq++}`,
    time: nowString(),
    adminName: currentAdmin().displayName,
    ip: '203.0.113.24',
    action,
    target,
    highRisk,
    result: 'success',
    reason,
    before: scrub(before),
    after: scrub(after),
  });
}

const sortDesc = <T,>(arr: T[], key: (x: T) => string) => [...arr].sort((a, b) => (key(a) < key(b) ? 1 : key(a) > key(b) ? -1 : 0));

// ============ 认证 / 原型身份 ============
export function login(username: string, password: string): Promise<AdminSession> {
  return respond(
    () => {
      if (!username.trim() || !password) throw new ApiError('errors.loginRequired');
      const admin = db.admins.find((a) => a.username === username.trim());
      if (admin && admin.status === 'disabled') throw new ApiError('errors.accountDisabled');
      // 原型：任意密码可登录；未知账号按超级管理员 负责人 登录
      const target = admin ?? db.admins[0];
      db.session.adminId = target.id;
      return {
        token: `proto-${target.id}-${Date.now()}`,
        adminId: target.id,
        username: target.username,
        displayName: target.displayName,
        mustChangePassword: target.mustChangePassword,
      };
    },
    { bypass: true },
  );
}

export function changePassword(oldPassword: string, newPassword: string): Promise<void> {
  return respond(
    () => {
      if (!oldPassword || !newPassword) throw new ApiError('errors.loginRequired');
      if (oldPassword === newPassword) throw new ApiError('errors.samePassword');
      currentAdmin().mustChangePassword = false;
      audit('admin.change_password', currentAdmin().username, false, null, { mustChangePassword: true }, { mustChangePassword: false });
    },
    { bypass: true },
  );
}

export type { CurrentAdmin };

export function getCurrentAdmin(adminId: number): Promise<CurrentAdmin> {
  return respond(
    () => {
      db.session.adminId = adminId;
      const admin = currentAdmin();
      return {
        admin,
        roles: db.roles.filter((r) => admin.roleIds.includes(r.id)),
        permissions: [...permissionsOf(admin)],
        isSuperAdmin: isSuper(admin),
      };
    },
    { bypass: true },
  );
}

export type ProtoIdentity = 'super' | 'ops' | 'risk';
export function switchIdentity(identity: ProtoIdentity): Promise<AdminSession> {
  return respond(
    () => {
      const admin = db.admins.find((a) => a.id === db.identityAdmin[identity])!;
      db.session.adminId = admin.id;
      return { token: `proto-${admin.id}-${Date.now()}`, adminId: admin.id, username: admin.username, displayName: admin.displayName, mustChangePassword: false };
    },
    { bypass: true },
  );
}

// ============ 仪表盘 ============
export function getDashboard(): Promise<DashboardStats> {
  const zero: DashboardStats = {
    todayRegistrations: 0, onlineCustomers: 0, todayDeposit: '0.00', todayDepositCount: 0, todayWithdraw: '0.00', todayWithdrawCount: 0,
    openPositions: 0, platformPnl: '0.00', platformNetExposure: '0', platformThreshold: db.riskSettings.platformThresholdUsd,
    pendingKyc: 0, pendingWithdrawals: 0, failedTasks: 0, openReconciliations: 0, updatedAt: nowString(),
  };
  return respond(
    () => {
      const today = '2026-10-14';
      const todayDeposits = db.deposits.filter((d) => d.status === 'credited' && d.creditedAt?.startsWith(today));
      const todayWd = db.withdrawals.filter((w) => w.status === 'completed' && w.submittedAt.startsWith(today));
      return {
        todayRegistrations: db.customers.filter((c) => c.registeredAt.startsWith(today)).length,
        onlineCustomers: 37,
        todayDeposit: sumAmounts(todayDeposits.map((d) => d.amount)),
        todayDepositCount: todayDeposits.length,
        todayWithdraw: sumAmounts(todayWd.map((w) => w.amount)),
        todayWithdrawCount: todayWd.length,
        openPositions: db.positions.length,
        platformPnl: '28417.36',
        platformNetExposure: db.riskSettings.platformNetUsd,
        platformThreshold: db.riskSettings.platformThresholdUsd,
        pendingKyc: db.kycApplications.filter((k) => k.status === 'pending').length,
        pendingWithdrawals: db.withdrawals.filter((w) => w.status === 'pending').length,
        failedTasks: db.tasks.filter((t) => t.status === 'failed').length,
        openReconciliations: db.reconItems.filter((r) => r.status === 'open').length,
        updatedAt: nowString(),
      };
    },
    { empty: () => zero },
  );
}

export function getMenuBadges(): Promise<MenuBadges> {
  return respond(
    () => ({
      kyc: db.kycApplications.filter((k) => k.status === 'pending').length,
      withdrawals: db.withdrawals.filter((w) => w.status === 'pending').length,
    }),
    { empty: () => ({ kyc: 0, withdrawals: 0 }) },
  );
}

// ============ 客户 ============
export function listCustomers(q: CustomerQuery): Promise<Page<CustomerSummary>> {
  return respond(
    () => {
      const rows = sortDesc(db.customers, (c) => c.registeredAt).filter(
        (c) => matches(q.keyword, c.name, c.email, c.id) && (!q.status || c.status === q.status) && (!q.kycStatus || c.kycStatus === q.kycStatus),
      );
      return paginate(rows, q.page, q.pageSize);
    },
    { empty: () => emptyPage(q.page, q.pageSize) },
  );
}

function ledgerFor(c: CustomerSummary, empty: boolean): LedgerEntry[] {
  if (empty) return [];
  const rng = createRng(c.id);
  const types: LedgerEntry['type'][] = ['pnl', 'fee', 'swap', 'pnl', 'fee', 'withdraw', 'pnl', 'refund', 'swap', 'adjust', 'fee', 'deposit'];
  let bal = toScaled(c.balance) ?? 0n;
  // 单笔金额不超过余额的 1/15（余额为 0 时按 500 计），倒推 11 笔后余额仍为正
  const cap = Number((bal > 50000n ? bal : 50000n) / 15n) / 100;
  return types.map((type, i) => {
    const raw = type === 'deposit' ? rng.amount(500, 12000) : type === 'fee' || type === 'swap' ? rng.amount(0, Math.min(30, cap)) : rng.amount(0, cap);
    const negative = type === 'withdraw' || type === 'fee' || ((type === 'pnl' || type === 'swap') && rng.bool(0.5));
    const amount = (negative ? '-' : '') + raw;
    const entry: LedgerEntry = {
      id: `LG-${c.id}-${12 - i}`,
      type,
      amount,
      balanceAfter: fromScaled(bal),
      ref: type === 'deposit' ? 'DP-20261001-000412' : type === 'withdraw' ? 'WD-20261010-000101' : type === 'adjust' ? 'ADJ-0007' : `PS-${660000 + i}`,
      time: minutesAgo(i * 300 + 15),
    };
    bal -= toScaled(amount) ?? 0n;
    if (bal < 0n) bal = 0n;
    return entry;
  });
}

export function getCustomer(id: number): Promise<CustomerDetail> {
  return mutateRead(() => {
    const c = db.customerById(id);
    if (!c) throw new ApiError('errors.notFound');
    const kyc = db.kycApplications.find((k) => k.customerId === id);
    const positions = db.positions.filter((p) => p.customerId === id);
    const used = sumAmounts(positions.map((p) => p.margin));
    const floating = sumAmounts(positions.map((p) => p.pnl));
    const frozenWd = sumAmounts(db.withdrawals.filter((w) => w.customerId === id && ['cooling', 'pending', 'delayed', 'broadcasting'].includes(w.status)).map((w) => w.amount));
    const avail = fromScaled((toScaled(c.balance) ?? 0n) - (toScaled(used) ?? 0n) - (toScaled(frozenWd) ?? 0n));
    const [first, ...rest] = c.name.split(' ');
    const edited = db.customerProfiles.get(id);
    return {
      ...c,
      lastLoginIp: db.lastLoginIpOf(c),
      lastLoginIpAt: c.lastLoginAt,
      firstName: edited ? edited.firstName : rest.length ? first : c.name.slice(1) || c.name,
      middleName: edited ? edited.middleName : '',
      lastName: edited ? edited.lastName : rest.length ? rest.join(' ') : c.name.slice(0, 1),
      birthDate: edited ? edited.birthDate : kyc?.birthDate ?? '1990-05-12',
      frozenReason: c.status === 'frozen' ? '客户来电称手机丢失，申请冻结' : null,
      kyc: {
        status: c.kycStatus,
        docType: kyc?.docType ?? null,
        docNumber: kyc?.docNumber ?? null,
        reviewedAt: kyc?.reviewedAt ?? null,
        rejectReason: kyc?.rejectReason ?? null,
      },
      account: { balance: c.balance, usedMargin: used, available: compareAmounts(avail, '0') < 0 ? '0.00' : avail, floatingPnl: floating, frozenWithdraw: frozenWd },
      positions,
      ledger: ledgerFor(c, false),
      closedPositions: db.closedPositions.filter((p) => p.customerId === id).slice(0, 20),
      heldWithdrawals: heldWithdrawals.get(id) ?? [],
      withdrawSafety: c.telegramId ? (withdrawSafety.get(id) ?? { pinSet: false, pinLockedUntil: null, withdrawFrozen: false, frozenSource: null, frozenAt: null }) : null,
    };
  });
}

/** 第 4 批：TG 客户的提现安全（原型里只放在内存，刷新即恢复） */
export const withdrawSafety = new Map<number, WithdrawSafety>();

export function unfreezeCustomerWithdraw(id: number, reason: string, password: string, source = 'all'): Promise<CustomerDetail> {
  return mutate(() => {
    requirePerm('withdraw:unfreeze');
    const r = requireReason(reason);
    // 原型登录不核对密码，这里也只要求填了（真实接口核对管理员自己的登录密码，不对 CURRENT_PASSWORD_WRONG）
    if (!password) throw new ApiError('errors.currentPasswordWrong', undefined, { status: 400, code: 'CURRENT_PASSWORD_WRONG' });
    const s = withdrawSafety.get(id);
    if (!s?.withdrawFrozen) throw new ApiError('errors.notWithdrawFrozen');
    // 4b 审查 🔴R2：一个来源一条，只解除选的来源（'all' 全部）
    const all = freezesOf(s);
    const left = source === 'all' ? [] : all.filter((f) => f.source !== source);
    if (left.length === all.length) throw new ApiError('errors.notWithdrawFrozen');
    withdrawSafety.set(id, { ...s, withdrawFrozen: left.length > 0, frozenSource: left[0]?.source ?? null, frozenAt: left[0]?.at ?? null, freezes: left });
    audit('customer.withdraw_unfreeze', String(id), true, r, { withdrawFrozen: all.map((f) => f.source).join(',') }, { withdrawFrozen: left.length ? left.map((f) => f.source).join(',') : 'no' });
  }).then(() => getCustomer(id));
}

/** 详情类接口：空数据场景下返回的明细列表为空 */
function mutateRead<T extends object>(produce: () => T): Promise<T> {
  return respond(produce, {
    empty: () => {
      const v = produce() as T & { positions?: unknown[]; ledger?: unknown[]; recentDeposits?: unknown[]; recentWithdrawals?: unknown[] };
      const acc = (v as { account?: CustomerDetail['account'] }).account;
      if (acc) Object.assign(acc, { usedMargin: '0.00', floatingPnl: '0.00', frozenWithdraw: '0.00', available: acc.balance });
      if (v.positions) v.positions = [];
      if (v.ledger) v.ledger = [];
      if ((v as { closedPositions?: unknown[] }).closedPositions) (v as { closedPositions?: unknown[] }).closedPositions = [];
      if (v.recentDeposits) v.recentDeposits = [];
      if (v.recentWithdrawals) v.recentWithdrawals = [];
      return v;
    },
  });
}

export function setCustomerFrozen(id: number, frozen: boolean, reason: string): Promise<void> {
  return mutate(() => {
    requirePerm('customer:freeze');
    const r = requireReason(reason);
    const c = db.customerById(id);
    if (!c) throw new ApiError('errors.notFound');
    const before = c.status;
    c.status = frozen ? 'frozen' : 'active';
    audit(frozen ? 'customer.freeze' : 'customer.unfreeze', String(id), true, r, { status: before }, { status: c.status });
  });
}

/** 字段错误：和真实接口一样一次返回全部（apiFieldCode 的翻译键） */
function fieldError(fields: { field: string; code: string }[]): ApiError {
  return new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields });
}

/** 翻译键 validation.xxx → 后端的字段错误码（和后端 CustomerAdmin 一致） */
const FIELD_CODE: Record<string, string> = {
  'validation.required': 'required',
  'validation.nameTooLong': 'nameTooLong',
  'validation.nameChars': 'nameChars',
  'validation.dateIncomplete': 'dateFormat',
  'validation.yearFormat': 'dateFormat',
  'validation.dateInvalid': 'dateInvalid',
  'validation.ageUnder18': 'ageUnder18',
  'validation.nationalityFormat': 'nationalityInvalid',
};

/** 编辑客户资料（v2 E1，高危）：校验和客户端注册一致；KYC 已通过也允许改，审计里标出 */
export function updateCustomerProfile(id: number, input: CustomerProfileInput, reason: string): Promise<CustomerDetail> {
  return mutate(() => {
    requirePerm('customer:edit');
    const r = requireReason(reason);
    const c = db.customerById(id);
    if (!c) throw new ApiError('errors.notFound');
    const problems: { field: string; code: string }[] = [];
    const push = (field: string, keys: string[]) => keys.forEach((k) => problems.push({ field, code: FIELD_CODE[k] ?? k }));
    push('firstName', nameProblems(input.firstName, true));
    push('middleName', nameProblems(input.middleName, false));
    push('lastName', nameProblems(input.lastName, true));
    push('birthDate', birthDateProblems(input.birthDate, new Date(NOW_MS)));
    push('nationality', nationalityProblems(input.nationality));
    if (problems.length) throw fieldError(problems);
    const kyc = db.kycApplications.find((k) => k.customerId === id);
    const [first, ...rest] = c.name.split(' ');
    const old = db.customerProfiles.get(id) ?? {
      firstName: rest.length ? first : c.name.slice(1) || c.name,
      middleName: '',
      lastName: rest.length ? rest.join(' ') : c.name.slice(0, 1),
      birthDate: kyc?.birthDate ?? '1990-05-12',
    };
    const next = { firstName: input.firstName.trim(), middleName: input.middleName.trim(), lastName: input.lastName.trim(), birthDate: input.birthDate.trim() };
    const before: Record<string, AuditValue> = {};
    const after: Record<string, AuditValue> = {};
    (Object.keys(next) as (keyof typeof next)[]).forEach((k) => {
      if (old[k] !== next[k]) {
        before[k] = old[k];
        after[k] = next[k];
      }
    });
    if (c.nationality !== input.nationality.trim()) {
      before.nationality = c.nationality;
      after.nationality = input.nationality.trim();
    }
    if (c.kycStatus === 'approved') {
      after.kycApproved = true;
      if (['firstName', 'middleName', 'lastName', 'birthDate'].some((k) => k in after)) after.kycMismatch = 'KYC 已通过，修改后和证件不一致';
    }
    db.customerProfiles.set(id, next);
    c.nationality = input.nationality.trim();
    c.name = [next.lastName, next.firstName].every((x) => /^[\u4e00-\u9fff]+$/.test(x))
      ? `${next.lastName}${next.firstName}`
      : [next.firstName, next.middleName, next.lastName].filter(Boolean).join(' ');
    audit('customer.edit', String(id), true, r, before, after);
  }).then(() => getCustomer(id));
}

export function getAdjustLimits(): Promise<AdjustLimits> {
  return respond(() => ({
    singleLimit: db.systemConfigs.find((c) => c.key === 'adjust.singleLimit')!.value + '.00',
    dailyLimit: db.systemConfigs.find((c) => c.key === 'adjust.dailyLimit')!.value + '.00',
    usedToday: db.adjustState.usedToday,
  }), { bypass: true });
}

export function adjustBalance(id: number, direction: 'credit' | 'debit', amount: string, reason: string): Promise<void> {
  return mutate(() => {
    requirePerm('customer:adjust_balance');
    const r = requireReason(reason);
    if (!isValidAmountInput(amount)) throw new ApiError('errors.invalidAmount');
    const c = db.customerById(id);
    if (!c) throw new ApiError('errors.notFound');
    const single = db.systemConfigs.find((x) => x.key === 'adjust.singleLimit')!.value;
    const daily = db.systemConfigs.find((x) => x.key === 'adjust.dailyLimit')!.value;
    if (compareAmounts(amount, single) > 0) throw new ApiError('errors.overSingleLimit');
    if (compareAmounts(sumAmounts([db.adjustState.usedToday, amount]), daily) > 0) throw new ApiError('errors.overDailyLimit');
    const signed = direction === 'debit' ? '-' + amount : amount;
    const next = sumAmounts([c.balance, signed]);
    if (compareAmounts(next, '0') < 0) throw new ApiError('errors.negativeBalance');
    const before = c.balance;
    c.balance = next;
    db.adjustState.usedToday = sumAmounts([db.adjustState.usedToday, amount]);
    audit('customer.adjust_balance', String(id), true, r, { balance: before }, { balance: next });
  });
}

// ============ KYC ============
export function listKyc(q: KycQuery): Promise<Page<KycApplication>> {
  return respond(
    () => paginate(db.kycApplications.filter((k) => (!q.status || k.status === q.status) && matches(q.keyword, k.name, k.email, k.customerId, k.id)), q.page, q.pageSize),
    { empty: () => emptyPage(q.page, q.pageSize) },
  );
}

export function getKyc(id: string): Promise<KycApplication> {
  return respond(() => {
    const k = db.kycApplications.find((x) => x.id === id);
    if (!k) throw new ApiError('errors.notFound');
    return k;
  });
}

export function reviewKyc(id: string, approve: boolean, reason?: string): Promise<void> {
  return mutate(() => {
    requirePerm('kyc:review');
    const k = db.kycApplications.find((x) => x.id === id);
    if (!k) throw new ApiError('errors.notFound');
    if (k.status !== 'pending') throw new ApiError('errors.stateChanged');
    const r = approve ? null : requireReason(reason);
    k.status = approve ? 'approved' : 'rejected';
    k.reviewer = currentAdmin().displayName;
    k.reviewedAt = nowString();
    k.rejectReason = r;
    const c = db.customerById(k.customerId);
    if (c) c.kycStatus = k.status;
    audit(approve ? 'kyc.approve' : 'kyc.reject', id, false, r, { status: 'pending' }, { status: k.status });
  });
}

// ============ 资金 ============
export function listDeposits(q: DepositQuery): Promise<Page<Deposit>> {
  return respond(
    () =>
      paginate(
        db.deposits
          .map((d, i) => ({ ...d, provider: 'TOPUP', providerRef: `evt_${(0x55821 - i).toString(16)}` }))
          .filter(
            (d) =>
              (!q.chain || d.chain === q.chain) &&
              (!q.status || d.status === q.status) &&
              (!q.providerRef || d.providerRef === q.providerRef.trim()) &&
              (!q.from || (d.creditedAt ?? d.detectedAt) >= q.from.trim()) &&
              (!q.to || (d.creditedAt ?? d.detectedAt) <= q.to.trim()) &&
              matches(q.keyword, d.customerName, d.email, d.txHash, d.customerId, d.id),
          ),
        q.page,
        q.pageSize,
      ),
    { empty: () => emptyPage(q.page, q.pageSize) },
  );
}

const OPEN_WD = ['cooling', 'pending', 'delayed'];

/**
 * 第 1 批两道审（原型 P4）：交给 TopUP 以后的付款编号和付款状态。种子数据里"放款中"的那笔演示"未知"（红色置顶、只能重新查询），
 * 已完成的是"成功"、失败退回的是"驳回"；审核通过的新单是"待放款审核"。
 */
const payouts = new Map<string, Pick<Withdrawal, 'provider' | 'payoutNo' | 'payoutStatus' | 'payoutReason' | 'statusAt'>>();
function payoutOf(w: Withdrawal): Partial<Withdrawal> {
  const p = payouts.get(w.id);
  if (p) return p;
  const no = `WD-20261014-${w.id.slice(-4)}`;
  if (w.status === 'broadcasting') return { provider: 'TOPUP', payoutNo: no, payoutStatus: 'unknown', payoutReason: 'TopUP 查不到这笔付款', statusAt: w.submittedAt };
  if (w.status === 'completed') return { provider: 'TOPUP', payoutNo: no, payoutStatus: 'completed', payoutReason: null, statusAt: w.submittedAt };
  if (w.status === 'failed') return { provider: 'TOPUP', payoutNo: no, payoutStatus: 'rejected', payoutReason: '地址有风险', statusAt: w.submittedAt };
  return { provider: null, payoutNo: null, payoutStatus: null, payoutReason: null, statusAt: w.submittedAt };
}

/** v2 自动审批：出金加上规则判定摘要；自动通过的没有审核人（内部文档 第 3 节）。第 1 批加付款编号和付款状态 */
function withAutoReview(w0: Withdrawal): Withdrawal {
  const w = { ...w0, ...payoutOf(w0) };
  const r = auto.reviews.get(w.id);
  if (!r) return { ...w, autoReview: null };
  const summary: AutoReviewSummary = { phase: r.phase, outcome: r.outcome, failed: r.failed, decidedAt: r.decidedAt };
  return { ...w, autoReview: summary, reviewer: r.outcome === 'auto_approved' ? null : w.reviewer };
}

function matchesAutoFilter(w: Withdrawal, f: WithdrawalQuery['autoReview']): boolean {
  if (!f) return true;
  const r = w.autoReview;
  if (f === 'none') return !r;
  if (!r) return false;
  if (f === 'auto_approved' || f === 'manual' || f === 'master_off') return r.outcome === f || (f === 'manual' && r.outcome === 'error');
  return r.failed.includes(f);
}

export function listWithdrawals(q: WithdrawalQuery): Promise<Page<Withdrawal>> {
  return respond(
    () => {
      const rows = db.withdrawals.map(withAutoReview).filter((w) => {
        if (!matchesAutoFilter(w, q.autoReview)) return false;
        if (q.status === 'open' ? !OPEN_WD.includes(w.status) : q.status && w.status !== q.status) return false;
        if (q.chain && w.chain !== q.chain) return false;
        if (q.minAmount && compareAmounts(w.amount, q.minAmount) < 0) return false;
        if (q.maxAmount && compareAmounts(w.amount, q.maxAmount) > 0) return false;
        return matches(q.keyword, w.customerName, w.email, w.customerId, w.id);
      });
      // 待审核排在前面，其次按提交时间倒序
      const rank = (w: Withdrawal) => (w.status === 'pending' ? 0 : w.status === 'delayed' ? 1 : w.status === 'cooling' ? 2 : 3);
      rows.sort((a, b) => rank(a) - rank(b) || (a.submittedAt < b.submittedAt ? 1 : -1));
      return paginate(rows, q.page, q.pageSize);
    },
    { empty: () => emptyPage(q.page, q.pageSize) },
  );
}

/** 第 1 批：向 TopUP 重新查付款状态。原型里"未知"的那笔查清是成功（勾"下一次操作模拟失败"时还是查不到） */
export function requeryWithdrawal(id: string): Promise<Withdrawal> {
  return mutate(() => {
    requirePerm('withdrawal:review');
    const w = db.withdrawals.find((x) => x.id === id);
    if (!w) throw new ApiError('errors.notFound');
    const p = payoutOf(w);
    if (!p.payoutNo) throw new ApiError('errors.notFound');
    if (p.payoutStatus === 'unknown') {
      w.status = 'completed';
      w.txHash = '0x' + '9a2c'.repeat(16);
      payouts.set(id, { provider: 'TOPUP', payoutNo: p.payoutNo, payoutStatus: 'completed', payoutReason: null, statusAt: nowString() });
    }
    audit('withdrawal.requery', id, false, null, { payoutStatus: p.payoutStatus ?? null }, { payoutStatus: payoutOf(w).payoutStatus ?? null });
    return withAutoReview(w);
  });
}

export function getWithdrawalSummary(): Promise<WithdrawalSummary> {
  const group = (pred: (w: Withdrawal) => boolean) => {
    const rows = db.withdrawals.filter(pred);
    return { count: rows.length, amount: sumAmounts(rows.map((w) => w.amount)) };
  };
  const zero = { count: 0, amount: '0.00' };
  return respond(
    () => ({
      pending: group((w) => w.status === 'pending'),
      delayed: group((w) => w.status === 'delayed'),
      cooling: group((w) => w.status === 'cooling'),
      completedToday: group((w) => w.status === 'completed' && w.submittedAt.startsWith('2026-10-14')),
    }),
    { empty: () => ({ pending: zero, delayed: zero, cooling: zero, completedToday: zero }) },
  );
}

export function getWithdrawal(id: string): Promise<WithdrawalDetail> {
  return mutateRead(() => {
    const w = db.withdrawals.find((x) => x.id === id);
    if (!w) throw new ApiError('errors.notFound');
    const c = db.customerById(w.customerId)!;
    const deps = db.deposits.filter((d) => d.customerId === c.id && d.status === 'credited').slice(0, 3);
    const prevWd = db.withdrawals.filter((x) => x.customerId === c.id && x.id !== id && x.status === 'completed').slice(0, 3);
    const regDays = Math.max(1, Math.round((Date.UTC(2026, 9, 14) - Date.parse(c.registeredAt.replace(' ', 'T') + 'Z')) / 86_400_000));
    return {
      ...withAutoReview(w),
      autoReviewDetail: auto.reviews.get(w.id) ?? null,
      kycStatus: c.kycStatus,
      registeredDays: w.customerId === 100482 ? 30 : regDays,
      totalDeposit: w.customerId === 100482 ? '20000.00' : sumAmounts(db.deposits.filter((d) => d.customerId === c.id && d.status === 'credited').map((d) => d.amount)),
      balance: c.balance,
      openPositions: db.positions.filter((p) => p.customerId === c.id).length,
      recentDeposits:
        w.customerId === 100482
          ? [
              { time: '2026-10-13 20:14:00', chain: 'TRC20', amount: '12000.00' },
              { time: '2026-09-28 11:02:00', chain: 'TRC20', amount: '8000.00' },
            ]
          : deps.map((d) => ({ time: d.creditedAt ?? d.detectedAt, chain: d.chain, amount: d.amount })),
      recentWithdrawals: prevWd.map((x) => ({ time: x.submittedAt, chain: x.chain, amount: x.amount })),
      safety: withdrawalSafety(c),
    };
  });
}

/** 第 4 批审查：出金详情的安全情况（原型：账户冻结看客户状态，提现冻结看 withdrawSafety；安全事件原型里没有） */
function withdrawalSafety(c: { id: number; status: string }) {
  const s = withdrawSafety.get(c.id);
  return {
    accountFrozen: c.status === 'frozen',
    withdrawFrozen: !!s?.withdrawFrozen,
    withdrawFrozenSource: s?.frozenSource ?? null,
    withdrawFrozenSources: s ? freezesOf(s).map((f) => f.source) : [],
    securityEvents48h: withdrawEvents.get(c.id) ?? [],
  };
}

/** 原型：客户最近 48 小时的安全事件（测试里设置） */
export const withdrawEvents = new Map<number, { type: string; at: string }[]>();

export function approveWithdrawal(id: string, firstChecked = false): Promise<Withdrawal> {
  return mutate(() => {
    requirePerm('withdrawal:review');
    const w = db.withdrawals.find((x) => x.id === id);
    if (!w) throw new ApiError('errors.notFound');
    if (w.status !== 'pending') throw new ApiError('errors.stateChanged');
    // 第 4 批审查 🔴4a-1：客户账户冻结或提现冻结时不能通过（和后端 WITHDRAW_FROZEN 一致）
    const sf = withdrawalSafety(db.customerById(w.customerId)!);
    if (sf.accountFrozen || sf.withdrawFrozen) throw new ApiError('errors.customerWithdrawFrozen', undefined, { status: 403, code: 'WITHDRAW_FROZEN' });
    // 第 4 批：首笔核实单没勾"已核实"不能通过（和后端 FIRST_CHECK_REQUIRED 一致）
    if (w.firstCheck && !w.firstChecked && !firstChecked) throw new ApiError('errors.firstCheckRequired');
    if (w.firstCheck && firstChecked) w.firstChecked = true;
    const large = compareAmounts(w.amount, db.systemConfigs.find((c) => c.key === 'withdraw.largeThreshold')!.value) >= 0;
    w.status = large ? 'delayed' : 'broadcasting';
    w.delayRemainingMinutes = large ? 360 : null;
    w.reviewer = currentAdmin().displayName;
    // 小额：1 分钟内交 TopUP，状态"待放款审核"，有付款编号（两道审）
    if (!large) payouts.set(id, { provider: 'TOPUP', payoutNo: `WD-20261014-${id.slice(-4)}`, payoutStatus: 'pending', payoutReason: null, statusAt: nowString() });
    audit('withdrawal.approve', id, false, null, { status: 'pending' }, { status: w.status });
    return withAutoReview(w);
  });
}

export function rejectWithdrawal(id: string, reason: string): Promise<Withdrawal> {
  return mutate(() => {
    requirePerm('withdrawal:review');
    const r = requireReason(reason);
    const w = db.withdrawals.find((x) => x.id === id);
    if (!w) throw new ApiError('errors.notFound');
    if (w.status !== 'pending') throw new ApiError('errors.stateChanged');
    w.status = 'rejected';
    w.reason = r;
    w.reviewer = currentAdmin().displayName;
    audit('withdrawal.reject', id, false, r, { status: 'pending' }, { status: 'rejected' });
    return w;
  });
}

export function emergencyCancelWithdrawal(id: string, reason: string): Promise<Withdrawal> {
  return mutate(() => {
    requirePerm('withdrawal:emergency_cancel');
    const r = requireReason(reason);
    const w = db.withdrawals.find((x) => x.id === id);
    if (!w) throw new ApiError('errors.notFound');
    if (w.status !== 'delayed') throw new ApiError('errors.stateChanged');
    w.status = 'cancelled';
    w.reason = r;
    w.delayRemainingMinutes = null;
    audit('withdrawal.emergency_cancel', id, true, r, { status: 'delayed' }, { status: 'cancelled' });
    return w;
  });
}

// ============ v2 提现自动审批（内部文档；和后端 admin/ops/AutoApproveOps 同一口径） ============
/** GET /api/admin/withdraw-auto-approve */
export function getAutoApproveSettings(): Promise<AutoApproveSettings> {
  return respond(() => auto.settings);
}

/** GET /api/admin/withdraw-auto-approve/changes：最近 20 次修改 */
export function listAutoApproveChanges(): Promise<AutoApproveChange[]> {
  return respond(() => auto.changes.slice(0, 20), { empty: () => [] });
}

/**
 * PUT /api/admin/withdraw-auto-approve（高危，权限 withdrawal:auto_rule_edit）：一次保存总开关和全部规则，必须填原因，写审计。
 * expectedVersion 不是当前版本时和后端一样返回 409 CONFIG_CHANGED；没有变化返回 NO_CHANGE。
 */
export function updateAutoApproveSettings(input: AutoApproveSettingsInput): Promise<AutoApproveSettings> {
  return mutate(() => {
    requirePerm(AUTO_RULE_EDIT_PERM);
    const reason = requireReason(input.reason);
    const cur = auto.settings;
    if (input.expectedVersion !== cur.version) throw new ApiError('errors.configChanged', undefined, { status: 409, code: 'CONFIG_CHANGED' });
    const diff: AutoApproveChange['changes'] = [];
    if (input.masterEnabled !== cur.masterEnabled) diff.push({ field: 'master', before: String(cur.masterEnabled), after: String(input.masterEnabled) });
    const nextParams: Record<string, Record<string, string>> = {};
    for (const def of AUTO_RULES) {
      const before = cur.rules.find((r) => r.code === def.code)!;
      const after = input.rules.find((r) => r.code === def.code);
      if (!after) throw new ApiError('errors.autoApproveInvalid');
      if (after.enabled !== before.enabled) diff.push({ field: `${def.code}.enabled`, before: String(before.enabled), after: String(after.enabled) });
      nextParams[def.code] = {};
      for (const p of def.params) {
        const v = (after.params[p.key] ?? '').trim();
        if (ruleParamProblem(p, v)) throw new ApiError('errors.autoApproveInvalid');
        const norm = String(Number(v));
        nextParams[def.code][p.key] = norm;
        if (Number(norm) !== Number(before.params[p.key])) diff.push({ field: `${def.code}.${p.key}`, before: before.params[p.key], after: norm });
      }
    }
    if (crossRuleProblems(nextParams).length) throw new ApiError('errors.autoApproveInvalid');
    if (!diff.length) throw new ApiError('errors.autoApproveNoChange', undefined, { status: 400, code: 'NO_CHANGE' });
    const time = nowString();
    const admin = currentAdmin().displayName;
    cur.masterEnabled = input.masterEnabled;
    cur.rules = AUTO_RULES.map((d) => ({ code: d.code, enabled: input.rules.find((r) => r.code === d.code)!.enabled, params: nextParams[d.code] }));
    cur.version += 1;
    cur.updatedAt = time;
    cur.updatedBy = admin;
    auto.changes.unshift({ id: `AAC-${cur.version}`, version: cur.version, time, adminName: admin, reason, changes: diff });
    audit(
      'withdrawal.auto_rule_update',
      `v${cur.version}`,
      true,
      reason,
      Object.fromEntries(diff.map((d) => [d.field, d.before])),
      Object.fromEntries(diff.map((d) => [d.field, d.after])),
    );
    return cur;
  });
}

export function listRecon(q: ReconQuery) {
  return respond(
    () => paginate(sortDesc(db.reconItems, (r) => r.detectedAt).filter((r) => !q.status || r.status === q.status), q.page, q.pageSize),
    { empty: () => emptyPage<(typeof db.reconItems)[number]>(q.page, q.pageSize) },
  );
}

/** 热钱包余额：下限读系统配置（和后端一样，0 = 不告警） */
export function listHotWallets() {
  return respond(
    () =>
      db.hotWallets.map((h) => {
        const threshold = db.systemConfigs.find((c) => c.key === db.hotWalletMinKey(h.chain, h.gas))?.value ?? '0';
        const status = compareAmounts(threshold, '0') <= 0 ? 'off' : h.balance === null ? 'unknown' : compareAmounts(h.balance, threshold) < 0 ? 'low' : 'ok';
        return { ...h, threshold, status } as const;
      }),
    { empty: () => [] },
  );
}

// ---------------- 第 1 批：对账状态、钱包提供方（原型 P6、P7） ----------------
const reconRun: Omit<ReconStatus, 'open'> = { lastRunAt: '2026-10-14 02:00:41', checked: 126, providerOk: true, providerError: null };

export function getReconStatus(): Promise<ReconStatus> {
  return respond(() => ({ ...reconRun, open: db.reconItems.filter((r) => r.status === 'open').length }), {
    empty: () => ({ lastRunAt: null, checked: 0, providerOk: true, providerError: null, open: 0 }),
  });
}

export function runRecon(): Promise<{ fresh: number }> {
  return mutate(() => {
    requirePerm('recon:resolve');
    reconRun.lastRunAt = nowString();
    audit('recon.run', 'now', false, null, {}, { fresh: '0' });
    return { fresh: 0 };
  });
}

const payment: PaymentOverview = {
  providers: [
    { code: 'TOPUP', name: 'TopUP', connected: true, current: true, up: true, lastOkAt: '2026-10-14T06:20:01Z', lastCallbackAt: '2026-10-14T06:18:44Z', available: '48230.5', balanceAt: '2026-10-14T06:20:01Z', error: null },
    { code: 'COBO', name: 'Cobo', connected: false, current: false, up: false, lastOkAt: null, lastCallbackAt: null, available: null, balanceAt: null, error: null },
    { code: 'FIREBLOCKS', name: 'Fireblocks', connected: false, current: false, up: false, lastOkAt: null, lastCallbackAt: null, available: null, balanceAt: null, error: null },
  ],
  networks: [
    { network: 'ERC20', open: true },
    { network: 'BSC', open: true },
    { network: 'TRC20', open: true },
  ],
  audits: [],
};

export function getPayment(): Promise<PaymentOverview> {
  return respond(() => structuredClone(payment), { empty: () => ({ ...structuredClone(payment), audits: [] }) });
}

/** 测试用：在用的提供方接入 / 没接入（服务器上地址和 Key 没配全） */
export function setCurrentProviderConnectedForTest(connected: boolean): void {
  payment.providers.find((x) => x.current)!.connected = connected;
}

export function checkPayment(): Promise<PaymentProvider> {
  return mutate(() => {
    const p = payment.providers.find((x) => x.current)!;
    p.lastOkAt = new Date().toISOString();
    return { ...p };
  });
}

export function setPaymentNetwork(network: string, open: boolean, reason: string): Promise<void> {
  return mutate(() => {
    requirePerm('provider:operate');
    const r = requireReason(reason);
    const n = payment.networks.find((x) => x.network === network);
    if (!n) throw new ApiError('errors.notFound');
    const before = n.open;
    n.open = open;
    payment.audits.unshift({ id: String(payment.audits.length + 1), admin: currentAdmin().displayName, action: open ? 'NETWORK_ON' : 'NETWORK_OFF', detail: network, reason: r, at: nowString() });
    audit(open ? 'payment.network_on' : 'payment.network_off', network, true, r, { open: before }, { open });
  });
}

export function switchPaymentProvider(provider: string, reason: string): Promise<void> {
  return mutate(() => {
    requirePerm('provider:operate');
    const r = requireReason(reason);
    const p = payment.providers.find((x) => x.code === provider);
    if (!p) throw new ApiError('errors.notFound');
    if (!p.connected) throw new ApiError('errors.providerNotConnected');
    if (p.current) throw new ApiError('errors.providerAlreadyCurrent');
    const from = payment.providers.find((x) => x.current)!;
    from.current = false;
    p.current = true;
    payment.audits.unshift({ id: String(payment.audits.length + 1), admin: currentAdmin().displayName, action: 'SWITCH_PROVIDER', detail: `${from.code} → ${p.code}`, reason: r, at: nowString() });
    audit('payment.switch_provider', provider, true, r, { provider: from.code }, { provider: p.code });
  });
}

export function resolveRecon(id: string, note: string): Promise<void> {
  return mutate(() => {
    requirePerm('recon:resolve');
    const n = requireReason(note);
    const r = db.reconItems.find((x) => x.id === id);
    if (!r) throw new ApiError('errors.notFound');
    if (r.status !== 'open') throw new ApiError('errors.issueNotOpen');
    r.status = 'resolved';
    r.resolvedAt = nowString();
    r.note = n;
    r.handler = currentAdmin().displayName;
    audit('recon.resolve', id, false, n, { status: 'open' }, { status: 'resolved' });
  });
}

export function listTasks(q: TaskQuery) {
  return respond(
    () => paginate(db.tasks.filter((t) => (!q.type || t.type === q.type) && (!q.status || t.status === q.status)), q.page, q.pageSize),
    { empty: () => emptyPage<(typeof db.tasks)[number]>(q.page, q.pageSize) },
  );
}

export function retryTask(id: string): Promise<void> {
  return mutate(() => {
    requirePerm('task:retry');
    const t = db.tasks.find((x) => x.id === id);
    if (!t) throw new ApiError('errors.notFound');
    if (t.status !== 'failed') throw new ApiError('errors.stateChanged');
    t.status = 'pending';
    t.nextRetryAt = nowString();
    t.updatedAt = nowString();
    audit('task.retry', id, false, null, { status: 'failed', attempts: t.attempts }, { status: 'pending', attempts: t.attempts });
  });
}

// ============ 交易监控 ============
function tradeFilter<T extends { symbol: string; side: string; customerName: string; customerId: number; id: string }>(rows: T[], q: TradeQuery): T[] {
  return rows.filter((r) => (!q.symbol || r.symbol === q.symbol) && (!q.side || r.side === q.side) && matches(q.keyword, r.customerName, r.customerId, r.id));
}

export function listOrders(q: TradeQuery) {
  return respond(
    () => paginate(tradeFilter(db.orders, q).filter((o) => !q.status || o.status === q.status), q.page, q.pageSize),
    { empty: () => emptyPage<(typeof db.orders)[number]>(q.page, q.pageSize) },
  );
}

export function listPositions(q: TradeQuery) {
  return respond(() => paginate(tradeFilter(db.positions, q), q.page, q.pageSize), { empty: () => emptyPage<(typeof db.positions)[number]>(q.page, q.pageSize) });
}

/** 已平仓记录（体验优化 1021 UX-6）：关键字也匹配原持仓编号（和后端一样，输入原持仓编号能看到它拆出来的部分平仓记录） */
export function listClosedPositions(q: TradeQuery): Promise<Page<ClosedPosition>> {
  return respond(
    () =>
      paginate(
        db.closedPositions.filter(
          (r) => (!q.symbol || r.symbol === q.symbol) && (!q.side || r.side === q.side) && (matches(q.keyword, r.customerName, r.customerId, r.id) || (!!q.keyword && r.parentId === q.keyword.trim())),
        ),
        q.page,
        q.pageSize,
      ),
    { empty: () => emptyPage<ClosedPosition>(q.page, q.pageSize) },
  );
}

export function liquidatePosition(id: string, reason: string): Promise<void> {
  return mutate(() => {
    requirePerm('position:liquidate');
    const r = requireReason(reason);
    const idx = db.positions.findIndex((p) => p.id === id);
    if (idx < 0) throw new ApiError('errors.stateChanged');
    const [p] = db.positions.splice(idx, 1);
    // 强平后进已平仓记录（整笔，亏损封顶为保证金）
    db.closedPositions.unshift({
      id: p.id,
      parentId: null,
      partial: false,
      customerId: p.customerId,
      customerName: p.customerName,
      symbol: p.symbol,
      side: p.side,
      lots: p.lots,
      openPrice: p.openPrice,
      closePrice: p.currentPrice,
      leverage: p.leverage,
      margin: p.margin,
      fee: '0',
      swap: '0',
      pnl: compareAmounts(p.pnl, `-${p.margin}`) < 0 ? `-${p.margin}` : p.pnl,
      reason: 'liquidated',
      openedAt: p.openedAt,
      closedAt: nowString(),
    });
    audit('position.liquidate', id, true, r, { status: 'open' }, { status: 'closed' });
  });
}

export function listPendingOrders(q: TradeQuery) {
  return respond(() => paginate(tradeFilter(db.pendingOrders, q), q.page, q.pageSize), { empty: () => emptyPage<(typeof db.pendingOrders)[number]>(q.page, q.pageSize) });
}

export function forceCancelPending(id: string, reason: string): Promise<void> {
  return mutate(() => {
    requirePerm('pending:force_cancel');
    const r = requireReason(reason);
    const idx = db.pendingOrders.findIndex((p) => p.id === id);
    if (idx < 0) throw new ApiError('errors.stateChanged');
    db.pendingOrders.splice(idx, 1);
    audit('pending.force_cancel', id, true, r, { status: 'pending' }, { status: 'cancelled' });
  });
}

export function listTradeSymbols(): Promise<string[]> {
  return respond(() => [...new Set(db.orders.map((o) => o.symbol))].sort(), { bypass: true });
}

// ============ 风控 ============
export function getRiskOverview(): Promise<RiskOverview> {
  return respond<RiskOverview>(
    () => ({ settings: db.riskSettings, exposures: db.exposures, events: db.riskEvents.slice(0, 8) }),
    { empty: () => ({ settings: { ...db.riskSettings, platformNetUsd: '0' }, exposures: [], events: [] }) },
  );
}

function riskEvent(type: (typeof db.riskEvents)[number]['type'], symbol: string | null, reason: string | null, value: string | null = null, threshold: string | null = null) {
  db.riskEvents.unshift({ id: `RE-${db.riskEvents.length + 10}`, time: nowString(), actorType: 'admin', actorName: currentAdmin().displayName, type, symbol, value, threshold, reason });
}

export function setGlobalPause(on: boolean, reason: string): Promise<void> {
  return mutate(() => {
    requirePerm('risk:global_pause');
    const r = requireReason(reason);
    db.riskSettings.globalPause = on;
    riskEvent(on ? 'global_pause_on' : 'global_pause_off', null, r);
    audit(on ? 'risk.global_pause_on' : 'risk.global_pause_off', '全局', true, r, { globalPause: !on }, { globalPause: on });
  });
}

export function setAutoLiquidation(on: boolean, reason: string): Promise<void> {
  return mutate(() => {
    requirePerm('risk:auto_liquidation');
    const r = requireReason(reason);
    db.riskSettings.autoLiquidation = on;
    riskEvent(on ? 'auto_liq_on' : 'auto_liq_off', null, r);
    audit(on ? 'risk.auto_liquidation_on' : 'risk.auto_liquidation_off', '全局', true, r, { autoLiquidation: !on }, { autoLiquidation: on });
  });
}

export function setSymbolPaused(symbol: string, paused: boolean, reason: string): Promise<void> {
  return mutate(() => {
    requirePerm('risk:symbol_pause');
    const r = requireReason(reason);
    const e = db.exposures.find((x) => x.symbol === symbol);
    if (!e) throw new ApiError('errors.notFound');
    const before = e.status;
    if (paused) e.status = 'manual_paused';
    else {
      const u = usagePercent(e.netUsd, e.thresholdUsd);
      e.status = u >= 80 ? 'near' : 'normal';
    }
    riskEvent(paused ? 'manual_pause' : 'manual_resume', symbol, r);
    audit(paused ? 'risk.symbol_pause' : 'risk.symbol_resume', symbol, true, r, { status: before }, { status: e.status });
  });
}

export function setSymbolThreshold(symbol: string, threshold: string, reason: string): Promise<void> {
  return mutate(() => {
    requirePerm('risk:threshold');
    const r = requireReason(reason);
    const e = db.exposures.find((x) => x.symbol === symbol);
    if (!e) throw new ApiError('errors.notFound');
    const before = e.thresholdUsd;
    e.thresholdUsd = threshold;
    riskEvent('threshold_change', symbol, r, threshold, before);
    audit('risk.threshold', symbol, true, r, { thresholdUsd: before }, { thresholdUsd: threshold });
  });
}

export function setPlatformThreshold(threshold: string, reason: string): Promise<void> {
  return mutate(() => {
    requirePerm('risk:threshold');
    const r = requireReason(reason);
    const before = db.riskSettings.platformThresholdUsd;
    db.riskSettings.platformThresholdUsd = threshold;
    riskEvent('threshold_change', null, r, threshold, before);
    audit('risk.platform_threshold', '平台', true, r, { thresholdUsd: before }, { thresholdUsd: threshold });
  });
}

export function setPerUserLimit(limit: string, reason: string): Promise<void> {
  return mutate(() => {
    requirePerm('risk:user_limit');
    const r = requireReason(reason);
    const before = db.riskSettings.perUserLimitUsd;
    db.riskSettings.perUserLimitUsd = limit;
    audit('risk.user_limit', '单用户持仓上限', true, r, { perUserLimitUsd: before }, { perUserLimitUsd: limit });
  });
}

// ============ 品种与行情 ============
/**
 * 模拟的最后报价时间（v2 E6）：按真实时间算，开市品种几秒前有报价；HK50 演示"超过 1 分钟没报价"（标红）；
 * 停用的品种、新增后还没报价源的品种为空（显示"暂无报价"）；美股按休市处理（不标红）。
 */
const STALE_DEMO = 'HK50';
const quotedSymbols = new Set(db.symbols.map((s) => s.code));

/**
 * 原型模拟的是测试环境：报价源是模拟源（品种列表的 simulatedQuotes 为 true，页面显示"模拟起始价"）。
 * 测试里可以切成"不是模拟源"（例如接了 LP），看输入框是否隐藏、这一项是否被忽略。
 */
let simulatedQuotes = true;
export function setMockSimulatedQuotes(on: boolean) {
  simulatedQuotes = on;
}

/** 模拟起始价的校验（和后端一致）：返回后端的字段错误代码，合法时返回规范化的值（去掉末尾的 0） */
function checkSimStartPrice(v: string, digits: number): { code: string } | { value: string } {
  const p = simStartPriceProblems(v, digits);
  if (p.length) return { code: p[0] === 'validation.simStartPriceDecimals' ? 'tooManyDecimals' : 'invalid' };
  const t = v.trim();
  return { value: t.includes('.') ? t.replace(/0+$/, '').replace(/\.$/, '') : t };
}
function withQuoteTime(s: TradingSymbol): TradingSymbol {
  const tradingOpen = s.enabled && s.category !== 'stock';
  // 原型里都是模拟报价源（真实报价源见 内部文档 第 5.5 节）
  const quoteSource = s.enabled ? 'simulated' : null;
  if (!s.enabled || !quotedSymbols.has(s.code)) return { ...s, lastQuoteAt: null, tradingOpen, quoteSource, quoteSourceState: null };
  const ageMs = s.code === STALE_DEMO ? 3 * 60_000 + 12_000 : s.category === 'stock' ? 15 * 3600_000 : (s.code.charCodeAt(0) % 5) * 1000 + 600;
  return { ...s, lastQuoteAt: new Date(Date.now() - ageMs).toISOString(), tradingOpen, quoteSource, quoteSourceState: null };
}

export function listSymbols(q: SymbolQuery): Promise<SymbolPage> {
  return respond(
    () => ({
      ...paginate(
        db.symbols.filter(
          (s) =>
            (!q.category || s.category === q.category) &&
            (q.enabled === undefined || s.enabled === q.enabled) &&
            (!q.missingSwap || s.swapLong === null || s.swapShort === null) &&
            matches(q.keyword, s.code, s.nameZh, s.nameEn),
        ).map(withQuoteTime),
        q.page,
        q.pageSize,
      ),
      simulatedQuotes,
    }),
    { empty: () => ({ ...emptyPage<TradingSymbol>(q.page, q.pageSize), simulatedQuotes }) },
  );
}

export function updateSymbol(code: string, patch: SymbolPatch, reason: string): Promise<void> {
  return mutate(() => {
    requirePerm('symbol:edit');
    const r = requireReason(reason);
    const s = db.symbols.find((x) => x.code === code);
    if (!s) throw new ApiError('errors.notFound');
    // 模拟起始价：只在模拟报价源下生效，否则忽略；还没有报价的品种补填后开始"报价"（已经在报价的只保存）
    const { simStartPrice: sim, ...rest } = patch;
    const next: Partial<TradingSymbol> = { ...rest };
    if (simulatedQuotes && sim !== undefined && sim.trim() !== '') {
      const c = checkSimStartPrice(sim, s.digits);
      if ('code' in c) throw fieldError([{ field: 'simStartPrice', code: c.code }]);
      next.simStartPrice = c.value;
    }
    const before: Record<string, AuditValue> = {};
    const after: Record<string, AuditValue> = {};
    (Object.keys(next) as (keyof TradingSymbol)[]).forEach((k) => {
      if (next[k] !== s[k]) {
        before[k] = (s[k] ?? null) as AuditValue;
        after[k] = next[k] as AuditValue;
      }
    });
    Object.assign(s, next, { updatedAt: nowString() });
    if (next.simStartPrice) quotedSymbols.add(code);
    audit('symbol.update', code, true, r, before, after);
  });
}

/** 新增品种（v2 E3，高危）：校验和后端一致；新增后默认暂停开仓，没有报价源（暂无报价） */
export function createSymbol(input: SymbolCreateInput, reason: string): Promise<TradingSymbol> {
  return mutate(() => {
    requirePerm('symbol:create');
    const r = requireReason(reason);
    const code = (input.code ?? '').trim().toUpperCase();
    const problems: { field: string; code: string }[] = [];
    const bad = (field: string, c: string) => problems.push({ field, code: c });
    if (!code) bad('code', 'required');
    else if (!SYMBOL_CODE_RE.test(code)) bad('code', 'invalid');
    if (!input.nameZh?.trim()) bad('nameZh', 'required');
    if (!input.nameEn?.trim()) bad('nameEn', 'required');
    if (!Number.isInteger(input.digits) || input.digits < 0 || input.digits > 8) bad('digits', 'invalid');
    if (!isValidAmountInput(input.contractSize ?? '', 8)) bad('contractSize', 'invalid');
    const quote = (input.quoteCurrency ?? '').trim().toUpperCase();
    if (!CURRENCY_RE.test(quote)) bad('quoteCurrency', 'invalid');
    else if (quote !== 'USD' && quote !== 'USDT' && !db.symbols.some((s) => s.code === `${quote}USD` || s.code === `USD${quote}`) && quote !== 'HKD') bad('quoteCurrency', 'noFxRate');
    if (!Number.isInteger(input.maxLeverage) || input.maxLeverage < 1 || input.maxLeverage > 1000) bad('maxLeverage', 'invalid');
    if (!isValidAmountInput(input.maintenanceMarginRate ?? '', 8) || compareAmounts(input.maintenanceMarginRate, '0.5', 8) > 0) bad('maintenanceMarginRate', 'invalid');
    const fee = (input.openFeeRate ?? '').trim();
    if (!/^\d+(\.\d{1,8})?$/.test(fee) || compareAmounts(fee, '0.01', 8) > 0) bad('openFeeRate', 'invalid');
    (['minLots', 'maxLots', 'lotStep'] as const).forEach((k) => {
      if (!isValidAmountInput(input[k] ?? '', 8)) bad(k, 'invalid');
    });
    if (isValidAmountInput(input.minLots ?? '', 8) && isValidAmountInput(input.maxLots ?? '', 8) && compareAmounts(input.minLots, input.maxLots, 8) > 0) bad('maxLots', 'lessThanMin');
    if (!/^\d+(\.\d{1,8})?$/.test((input.markup ?? '0').trim() || '0')) bad('markup', 'invalid');
    const copy = input.copySessionsFrom?.trim();
    if (!copy && !input.sessionTemplate) bad('sessions', 'required');
    else if (copy && !db.sessions.some((s) => s.symbol === copy)) bad('copySessionsFrom', 'invalid');
    // 模拟起始价（可选）：只在模拟报价源下生效，否则忽略（不校验、不保存）
    let sim: string | null = null;
    if (simulatedQuotes && input.simStartPrice?.trim()) {
      const c = checkSimStartPrice(input.simStartPrice, input.digits);
      if ('code' in c) bad('simStartPrice', c.code);
      else sim = c.value;
    }
    if (problems.length) throw fieldError(problems);
    if (db.symbols.some((s) => s.code === code)) throw new ApiError('errors.symbolExists', undefined, { status: 409, code: 'SYMBOL_EXISTS' });
    const ranges = copy
      ? db.sessions.find((s) => s.symbol === copy)!.ranges.map((x) => ({ ...x }))
      : input.sessionTemplate === 'crypto'
        ? [1, 2, 3, 4, 5, 6, 7].map((day) => ({ day, open: '00:00', close: '24:00' }))
        : input.sessionTemplate === 'us_stock'
          ? [1, 2, 3, 4, 5].map((day) => ({ day, open: '13:30', close: '20:00' }))
          : [{ day: 7, open: '22:00', close: '24:00' }, ...[1, 2, 3, 4].map((day) => ({ day, open: '00:00', close: '24:00' })), { day: 5, open: '00:00', close: '21:00' }];
    const s: TradingSymbol = {
      code,
      nameZh: input.nameZh.trim(),
      nameEn: input.nameEn.trim(),
      category: input.category,
      quoteCurrency: quote,
      contractSize: input.contractSize.trim(),
      minLots: input.minLots.trim(),
      maxLots: input.maxLots.trim(),
      lotStep: input.lotStep.trim(),
      maxLeverage: input.maxLeverage,
      maintenanceMarginRate: input.maintenanceMarginRate.trim(),
      openFeeRate: fee,
      swapLong: null,
      swapShort: null,
      // 不填加点时按后端口径用默认的每边 10 点（买卖合计 20 点，V24）
      markup: input.markup == null ? (defaultMarkup(input.digits) ?? '0') : input.markup.trim() || '0',
      digits: input.digits,
      hot: !!input.hot,
      enabled: true,
      openPaused: true,
      sortOrder: Math.max(0, ...db.symbols.map((x) => x.sortOrder)) + 10,
      updatedAt: nowString(),
      simStartPrice: sim,
    };
    db.symbols.push(s);
    // 有起始价：模拟报价源立即开始给它报价（原型里就是"有最后报价时间"）
    if (sim) quotedSymbols.add(code);
    db.sessions.push({ symbol: code, ranges });
    const after: Record<string, AuditValue> = { ...(s as unknown as Record<string, AuditValue>), sessions: copy ? `copy:${copy}` : `template:${input.sessionTemplate}` };
    audit('symbol.create', code, true, r, null, after);
    return withQuoteTime(s);
  });
}

export function setSymbolEnabled(code: string, enabled: boolean, reason: string): Promise<void> {
  return mutate(() => {
    requirePerm('symbol:edit');
    const r = requireReason(reason);
    const s = db.symbols.find((x) => x.code === code);
    if (!s) throw new ApiError('errors.notFound');
    // 还有持仓或挂单的品种不能停用（后台方案第 9 节）
    if (!enabled && (db.positions.some((p) => p.symbol === code) || db.pendingOrders.some((p) => p.symbol === code))) throw new ApiError('errors.symbolInUse');
    const before = s.enabled;
    s.enabled = enabled;
    s.updatedAt = nowString();
    audit(enabled ? 'symbol.enable' : 'symbol.disable', code, true, r, { enabled: before }, { enabled });
  });
}

export function listSessions() {
  return respond(() => db.sessions, { empty: () => [] as typeof db.sessions });
}

export function listHolidays() {
  return respond(() => [...db.holidays].sort((a, b) => (a.date < b.date ? -1 : 1)), { empty: () => [] as Holiday[] });
}

export function addHoliday(h: HolidayInput): Promise<void> {
  return mutate(() => {
    requirePerm('session:edit');
    if (db.holidays.some((x) => x.symbol === h.symbol && x.date === h.date)) throw new ApiError('errors.holidayExists');
    db.holidays.push({ id: String(db.holidays.length + 10), date: h.date, symbol: h.symbol, reason: h.reason?.trim() || null });
    audit('holiday.add', `${h.symbol} ${h.date}`, false, h.reason?.trim() || null, null, { symbol: h.symbol, date: h.date });
  });
}

export function deleteHoliday(id: string): Promise<void> {
  return mutate(() => {
    requirePerm('session:edit');
    const idx = db.holidays.findIndex((h) => h.id === id);
    if (idx >= 0) {
      const [h] = db.holidays.splice(idx, 1);
      audit('holiday.delete', h.id, false, null, { symbol: h.symbol, date: h.date }, null);
    }
  });
}

export function listFxRates() {
  return respond(() => db.fxRates, { empty: () => [] as typeof db.fxRates });
}

// ============ 交易参数 ============
export function getTradingParams(): Promise<TradingParams> {
  return respond(() => db.tradingParams);
}

export function updateTradingParams(p: TradingParamsInput, reason: string): Promise<void> {
  return mutate(() => {
    requirePerm('params:edit');
    const r = requireReason(reason);
    const before = { marginCallLevel: db.tradingParams.marginCallLevel, marginCallThrottleMinutes: db.tradingParams.marginCallThrottleMinutes };
    Object.assign(db.tradingParams, p, { updatedAt: nowString(), updatedBy: currentAdmin().displayName });
    audit('params.update', 'trading', true, r, before, { ...p });
  });
}

// ============ 通知 ============
export function listTemplates() {
  return respond(() => db.templates, { empty: () => [] as NotifyTemplate[] });
}

export function updateTemplate(id: string, patch: Partial<Pick<NotifyTemplate, 'titleZh' | 'bodyZh' | 'titleEn' | 'bodyEn' | 'enabled'>>): Promise<void> {
  return mutate(() => {
    requirePerm('notify_template:edit');
    const t = db.templates.find((x) => x.id === id);
    if (!t) throw new ApiError('errors.notFound');
    // 内置模板不能禁用（禁用会导致出金结算等流程失败）
    if (t.builtIn && patch.enabled === false) throw new ApiError('errors.builtInCannotDisable');
    const before = { titleZh: t.titleZh, bodyZh: t.bodyZh, titleEn: t.titleEn, bodyEn: t.bodyEn, enabled: t.enabled };
    Object.assign(t, patch, { updatedAt: nowString() });
    audit('notify.template_update', t.code, false, null, before, { titleZh: t.titleZh, bodyZh: t.bodyZh, titleEn: t.titleEn, bodyEn: t.bodyEn, enabled: t.enabled });
  });
}

export function searchCustomers(keyword: string): Promise<CustomerSummary[]> {
  return respond(() => db.customers.filter((c) => matches(keyword, c.name, c.email, c.id)).slice(0, 20), { bypass: true });
}

export function sendManualNotification(input: ManualNotifyInput): Promise<void> {
  return mutate(() => {
    requirePerm('notify:send');
    const r = requireReason(input.reason);
    const c = db.customerById(input.customerId);
    if (!c) throw new ApiError('errors.notFound');
    input.channels.forEach((ch) =>
      db.notifyRecords.unshift({
        id: `NT-${553200 + db.notifyRecords.length}`,
        customerId: c.id,
        customerName: c.name,
        source: 'manual',
        templateCode: null,
        channel: ch,
        title: input.title,
        status: 'sent',
        error: null,
        operator: currentAdmin().displayName,
        time: nowString(),
      }),
    );
    audit('notify.manual_send', String(c.id), true, r, null, { channels: input.channels, title: input.title });
  });
}

export function listNotifyRecords(q: NotifyRecordQuery) {
  return respond(
    () => paginate(db.notifyRecords.filter((n) => (!q.channel || n.channel === q.channel) && (!q.status || n.status === q.status) && matches(q.keyword, n.customerName, n.customerId, n.title)), q.page, q.pageSize),
    { empty: () => emptyPage<(typeof db.notifyRecords)[number]>(q.page, q.pageSize) },
  );
}

// ============ 系统：管理员 ============
export function listAdmins(q: PageQuery & { keyword?: string }) {
  return respond(() => paginate(db.admins.filter((a) => matches(q.keyword, a.username, a.displayName, a.email)), q.page, q.pageSize), {
    empty: () => emptyPage<AdminUser>(q.page, q.pageSize),
  });
}

function guardSuperAssignment(roleIds: string[], existing?: AdminUser): void {
  const superRole = db.roles.find((r) => r.code === SUPER_ADMIN_ROLE)!;
  const addsSuper = roleIds.includes(superRole.id) && !(existing?.roleIds.includes(superRole.id) ?? false);
  // 修复 fxsystem S4：只有超级管理员能分配超级管理员角色（包括给自己）
  if (addsSuper && !isSuper(currentAdmin())) throw new ApiError('errors.cannotAssignSuper');
}

/** 原型演示用的临时密码（真实接口由后端生成，只返回一次） */
function demoTempPassword(): string {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789';
  let out = '';
  for (let i = 0; i < 14; i++) out += chars[Math.floor(Math.random() * chars.length)];
  return out + '7k';
}

export function createAdmin(input: AdminInput): Promise<TemporaryPassword> {
  return mutate(() => {
    requirePerm('admin:edit');
    if (db.admins.some((a) => a.username === input.username)) throw new ApiError('errors.usernameTaken');
    guardSuperAssignment(input.roleIds);
    const a: AdminUser = { id: Math.max(...db.admins.map((x) => x.id)) + 1, ...input, status: 'active', mustChangePassword: true, lastLoginAt: null, createdAt: nowString() };
    db.admins.push(a);
    audit('admin.create', input.username, true, null, null, { username: input.username, roleIds: input.roleIds, password: '(临时密码)' });
    return { id: a.id, temporaryPassword: demoTempPassword() };
  });
}

export function updateAdmin(id: number, input: AdminInput): Promise<void> {
  return mutate(() => {
    requirePerm('admin:edit');
    const a = db.admins.find((x) => x.id === id);
    if (!a) throw new ApiError('errors.notFound');
    guardSuperAssignment(input.roleIds, a);
    const before = { displayName: a.displayName, email: a.email, roleIds: a.roleIds };
    Object.assign(a, { displayName: input.displayName, email: input.email, roleIds: input.roleIds });
    audit('admin.update', a.username, true, null, before, { displayName: a.displayName, email: a.email, roleIds: a.roleIds });
  });
}

export function setAdminStatus(id: number, active: boolean, reason: string): Promise<void> {
  return mutate(() => {
    requirePerm('admin:edit');
    const r = requireReason(reason);
    const a = db.admins.find((x) => x.id === id);
    if (!a) throw new ApiError('errors.notFound');
    if (a.id === currentAdmin().id) throw new ApiError('errors.cannotDisableSelf');
    if (isSuper(a) && !isSuper(currentAdmin())) throw new ApiError('errors.forbidden');
    a.status = active ? 'active' : 'disabled';
    audit(active ? 'admin.enable' : 'admin.disable', a.username, true, r, { status: active ? 'disabled' : 'active' }, { status: a.status });
  });
}

export function resetAdminPassword(id: number, reason: string): Promise<TemporaryPassword> {
  return mutate(() => {
    requirePerm('admin:edit');
    const r = requireReason(reason);
    const a = db.admins.find((x) => x.id === id);
    if (!a) throw new ApiError('errors.notFound');
    if (isSuper(a) && !isSuper(currentAdmin())) throw new ApiError('errors.forbidden');
    a.mustChangePassword = true;
    // 临时密码只在结果里返回一次（后台方案第 7 节），审计里不出现密码
    audit('admin.reset_password', a.username, true, r, { mustChangePassword: false, password: '***' }, { mustChangePassword: true, password: '***' });
    return { id: a.id, temporaryPassword: demoTempPassword() };
  });
}

// ============ 系统：角色 / 菜单 / 权限 ============
export function listRoles() {
  return respond(
    () => db.roles.map((r) => ({ ...r, memberCount: db.admins.filter((a) => a.roleIds.includes(r.id)).length })),
    { empty: () => [] as Role[] },
  );
}

export function saveRole(id: string | null, input: RoleInput): Promise<void> {
  return mutate(() => {
    requirePerm('role:edit');
    if (id) {
      const r = db.roles.find((x) => x.id === id);
      if (!r) throw new ApiError('errors.notFound');
      if (r.builtIn) throw new ApiError('errors.builtInRole');
      const before = { nameZh: r.nameZh, permissions: r.permissions };
      Object.assign(r, input);
      audit('role.update', r.nameZh, true, null, before, { nameZh: r.nameZh, permissions: r.permissions });
    } else {
      if (input.code === SUPER_ADMIN_ROLE || db.roles.some((r) => r.code === input.code)) throw new ApiError('errors.roleCodeTaken');
      db.roles.push({ id: `R-${db.roles.length + 10}`, ...input, builtIn: false, memberCount: 0 });
      audit('role.create', input.nameZh, true, null, null, { code: input.code, permissions: input.permissions });
    }
  });
}

export function deleteRole(id: string): Promise<void> {
  return mutate(() => {
    requirePerm('role:edit');
    const r = db.roles.find((x) => x.id === id);
    if (!r) throw new ApiError('errors.notFound');
    if (r.builtIn) throw new ApiError('errors.builtInRole');
    if (db.admins.some((a) => a.roleIds.includes(id))) throw new ApiError('errors.roleInUse');
    db.roles.splice(db.roles.indexOf(r), 1);
    audit('role.delete', r.nameZh, true, null, { code: r.code }, null);
  });
}

function toMenuNodes(items: MenuItemDef[]): MenuNode[] {
  return items.map((m, i) => ({
    key: m.key,
    path: m.path ?? null,
    labelKey: m.labelKey,
    permission: m.permission ?? null,
    order: (i + 1) * 10,
    visible: true,
    children: m.children ? toMenuNodes(m.children) : undefined,
  }));
}
const menuTree: MenuNode[] = toMenuNodes(MENU);

export function listMenus() {
  return respond(() => menuTree, { empty: () => [] as MenuNode[] });
}

export function updateMenu(key: string, patch: Pick<MenuNode, 'order'> & Partial<Pick<MenuNode, 'visible'>>): Promise<void> {
  return mutate(() => {
    requirePerm('menu:edit');
    const walk = (nodes: MenuNode[]): MenuNode | undefined => nodes.reduce<MenuNode | undefined>((f, n) => f ?? (n.key === key ? n : walk(n.children ?? [])), undefined);
    const n = walk(menuTree);
    if (!n) throw new ApiError('errors.notFound');
    const before = { order: n.order, visible: n.visible };
    n.order = patch.order;
    if (patch.visible !== undefined) n.visible = patch.visible;
    audit('menu.update', key, false, null, before, { order: n.order, visible: n.visible });
  });
}

export function listPermissions() {
  return respond(() => [...PERMISSIONS], { empty: () => [] });
}

// ============ 系统：审计、配置 ============
export function listAuditLogs(q: AuditQuery) {
  return respond(
    () =>
      paginate(
        db.auditLogs
          .filter((l) => (!q.action || l.action === q.action) && (!q.admin || l.adminName === q.admin) && (!q.highRiskOnly || l.highRisk) && matches(q.keyword, l.target, l.adminName, l.reason))
          .map((l) => ({ ...l, before: scrub(l.before), after: scrub(l.after) })),
        q.page,
        q.pageSize,
      ),
    { empty: () => emptyPage<AuditLog>(q.page, q.pageSize) },
  );
}

export function listAuditActions(): Promise<string[]> {
  return respond(() => [...new Set(db.auditLogs.map((l) => l.action))].sort(), { bypass: true });
}

export function listSystemConfigs() {
  return respond(() => db.systemConfigs, { empty: () => [] as typeof db.systemConfigs });
}

export function updateSystemConfig(key: string, value: string, reason: string): Promise<void> {
  return mutate(() => {
    requirePerm('config:edit');
    const r = requireReason(reason);
    const c = db.systemConfigs.find((x) => x.key === key);
    if (!c) throw new ApiError('errors.notFound');
    const before = c.value;
    c.value = value;
    c.updatedAt = nowString();
    audit('config.update', key, true, r, { value: before }, { value });
  });
}

/** 第 3 批报价服务的模拟层（mocks/quoteService.ts）用的权限、原因、审计 */
export const mockGuards = {
  requirePerm,
  requireReason,
  isSuper: () => isSuper(currentAdmin()),
  adminName: () => currentAdmin().displayName,
  audit,
};

// ---------- 第 4 批 4b：找回申请、冻结时暂停的提现（原型：内存里，测试里设置） ----------
/** 原型：客户冻结时暂停的提现（按客户号） */
export const heldWithdrawals = new Map<number, HeldWithdrawal[]>();
/** 原型：找回申请 */
export const recoveries: Recovery[] = [];
const recoveryEvents = new Map<string, RecoveryDetail['events']>();

function recoveryEvent(id: string, action: string, note: string | null) {
  const list = recoveryEvents.get(id) ?? [];
  list.push({ action, actor: `admin:${currentAdmin().id}`, note, at: nowString() });
  recoveryEvents.set(id, list);
}

function recoveryDetail(id: string): RecoveryDetail {
  const r = recoveries.find((x) => x.id === id);
  if (!r) throw new ApiError('errors.notFound');
  return { recovery: { ...r }, events: [...(recoveryEvents.get(id) ?? [])] };
}

function requireAdminPassword(password: string) {
  // 原型登录不核对密码，这里也只要求填了（真实接口核对管理员自己的登录密码，输错计入登录失败锁定）
  if (!password) throw new ApiError('errors.currentPasswordWrong', undefined, { status: 400, code: 'CURRENT_PASSWORD_WRONG' });
}

export function listRecoveries(status?: string): Promise<Recovery[]> {
  return mutateRead(() => ({ items: recoveries.filter((r) => !status || r.status === status).map((r) => ({ ...r })) })).then((x) => x.items);
}

export function getRecovery(id: string): Promise<RecoveryDetail> {
  return mutateRead(() => recoveryDetail(id));
}

export function approveRecovery(id: string, note: string, password: string): Promise<RecoveryDetail> {
  return mutate(() => {
    requirePerm('recovery:review');
    if (!note.trim()) throw new ApiError('errors.proofNoteRequired');
    requireAdminPassword(password);
    const r = recoveries.find((x) => x.id === id);
    if (!r) throw new ApiError('errors.notFound');
    if (r.status === 'objection') throw new ApiError('errors.recoveryNotReady');
    if (r.status !== 'proof') throw new ApiError('errors.recoveryNotPending');
    if (r.revokeRequested) throw new ApiError('errors.recoveryRevokePending');
    r.status = 'approved';
    r.proofNote = note.trim();
    r.approvedBy = currentAdmin().displayName;
    r.approvedAt = nowString();
    r.executeAt = new Date(Date.now() + (r.kind === 'rebind' ? 24 : 48) * 3_600_000).toISOString();
    recoveryEvent(id, 'approve', note.trim());
    audit('recovery.approve', id, true, note.trim(), { status: 'proof' }, { status: 'approved' });
    return recoveryDetail(id);
  });
}

function closeRecovery(id: string, reason: string, to: 'rejected' | 'cancelled', action: string, from: Recovery['status'][]) {
  requirePerm('recovery:review');
  const r = requireReason(reason);
  const x = recoveries.find((y) => y.id === id);
  if (!x) throw new ApiError('errors.notFound');
  if (!from.includes(x.status)) throw new ApiError(to === 'rejected' ? 'errors.recoveryNotPending' : 'errors.recoveryNotOpen');
  x.status = to;
  x.closedBy = currentAdmin().displayName;
  x.closeReason = r;
  x.closedAt = nowString();
  recoveryEvent(id, action, r);
  audit(`recovery.${action}`, id, true, r, null, { status: to });
  return recoveryDetail(id);
}

export function rejectRecovery(id: string, reason: string): Promise<RecoveryDetail> {
  return mutate(() => closeRecovery(id, reason, 'rejected', 'reject', ['objection', 'proof']));
}

export function revokeRecovery(id: string, reason: string): Promise<RecoveryDetail> {
  return mutate(() => closeRecovery(id, reason, 'cancelled', 'revoke', ['objection', 'proof', 'approved']));
}

export function keepRecovery(id: string, reason: string, password: string): Promise<RecoveryDetail> {
  return mutate(() => {
    requirePerm('recovery:review');
    const r = requireReason(reason);
    requireAdminPassword(password);
    const x = recoveries.find((y) => y.id === id);
    if (!x) throw new ApiError('errors.notFound');
    if ((x.status !== 'approved' && x.status !== 'proof') || !x.revokeRequested) throw new ApiError('errors.recoveryNoRevokeRequest');
    x.revokeRequested = false;
    x.revokeRequestedAt = null;
    x.keptAt = nowString();
    recoveryEvent(id, 'keep', r);
    audit('recovery.keep', id, true, r, { revokeRequested: true }, { revokeRequested: false });
    return recoveryDetail(id);
  });
}

function heldFor(id: string): [number, HeldWithdrawal] {
  for (const [cid, list] of heldWithdrawals) {
    const h = list.find((x) => x.id === id);
    if (h) return [cid, h];
  }
  throw new ApiError('errors.withdrawNotHeld');
}

export function releaseHeldWithdrawal(id: string, reason: string): Promise<Withdrawal> {
  return mutate(() => {
    requirePerm('withdrawal:review');
    const r = requireReason(reason);
    const [cid] = heldFor(id);
    if (withdrawSafety.get(cid)?.withdrawFrozen || db.customerById(cid)?.status === 'frozen') throw new ApiError('errors.customerWithdrawFrozen');
    heldWithdrawals.set(cid, (heldWithdrawals.get(cid) ?? []).filter((x) => x.id !== id));
    audit('withdrawal.release_held', id, true, r, { held: true }, { held: false });
    return { id } as unknown as Withdrawal;
  });
}

export function cancelHeldWithdrawal(id: string, reason: string): Promise<Withdrawal> {
  return mutate(() => {
    requirePerm('withdrawal:emergency_cancel');
    const r = requireReason(reason);
    const [cid, h] = heldFor(id);
    if (h.sentBefore) throw new ApiError('errors.withdrawSentBefore');
    heldWithdrawals.set(cid, (heldWithdrawals.get(cid) ?? []).filter((x) => x.id !== id));
    audit('withdrawal.cancel_held', id, true, r, { status: 'approved' }, { status: 'cancelled' });
    return { id } as unknown as Withdrawal;
  });
}

/** 第 4 批 4c 原型：解除 / 更换 Telegram 关联（只有超管） */
export function changeCustomerTelegram(id: number, telegramId: string | null, username: string | null, reason: string, password: string): Promise<CustomerDetail> {
  return mutate(() => {
    if (currentAdmin().roleIds.length && !db.roles.find((r) => r.code === 'super_admin' && currentAdmin().roleIds.includes(r.id))) throw new ApiError('errors.forbidden');
    const r = requireReason(reason);
    requireAdminPassword(password);
    const c = db.customerById(id);
    if (!c) throw new ApiError('errors.notFound');
    if (!telegramId && !c.telegramId) throw new ApiError('errors.notTgAccount');
    if (telegramId && db.customers.some((x) => x.telegramId === telegramId)) throw new ApiError('errors.telegramTaken');
    const old = c.telegramId ?? null;
    c.telegramId = telegramId;
    c.telegramUsername = telegramId ? username : null;
    const s = withdrawSafety.get(id) ?? { pinSet: false, pinLockedUntil: null, withdrawFrozen: false, frozenSource: null, frozenAt: null };
    const list = [...freezesOf(s).filter((f) => f.source !== 'admin'), { source: 'admin' as const, reason: telegramId ? '后台更换了 Telegram 关联' : '后台解除了 Telegram 关联', at: nowString() }];
    withdrawSafety.set(id, { ...s, pinSet: telegramId ? false : s.pinSet, withdrawFrozen: true, frozenSource: list[0].source, frozenAt: list[0].at, freezes: list });
    audit(telegramId ? 'customer.telegram_relink' : 'customer.telegram_unlink', String(id), true, r, { telegramId: old }, { telegramId });
  }).then(() => getCustomer(id));
}
