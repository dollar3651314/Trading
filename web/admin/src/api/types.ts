/**
 * 后台接口的数据类型，字段和后端 `backend/src/main/java/app/trading/admin/` 返回的 record 一致。
 * 金额一律是十进制字符串。时间：真实接口是 ISO UTC 字符串（如 "2026-10-14T01:12:33Z"），模拟数据是 "YYYY-MM-DD HH:mm:ss"（UTC+8），
 * 页面统一用 utils/display 的 fullTime / shortTime 显示成 UTC+8。
 * Admin API types. Amounts are decimal strings; real timestamps are ISO UTC, mock ones "YYYY-MM-DD HH:mm:ss".
 */

export type Chain = 'ERC20' | 'TRC20' | 'BSC';
export type Side = 'buy' | 'sell';
export type Lang = 'zh-CN' | 'en';

export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}

export interface PageQuery {
  page: number;
  pageSize: number;
}

/** 后端字段错误（`{field, code}`，code 如 required、invalid、tooLong、weak） */
export interface FieldError {
  field: string;
  code: string;
}

/**
 * 接口错误：reasonKey 是翻译键，界面显示具体原因，不显示错误代码。
 * 真实接口另带 HTTP 状态、后端错误码和字段错误（字段错误由 utils/errors 翻译成"哪个字段、什么问题"）。
 */
export class ApiError extends Error {
  readonly reasonKey: string;
  readonly params?: Record<string, string | number>;
  readonly status?: number;
  readonly code?: string;
  readonly fields?: FieldError[];
  constructor(reasonKey: string, params?: Record<string, string | number>, extra?: { status?: number; code?: string; fields?: FieldError[] }) {
    super(reasonKey);
    this.name = 'ApiError';
    this.reasonKey = reasonKey;
    this.params = params;
    this.status = extra?.status;
    this.code = extra?.code;
    this.fields = extra?.fields;
  }
}

// ---------- 认证 / 身份 ----------
export type RoleCode = 'super_admin' | 'operations' | 'risk' | string;

/** 登录结果。真实接口不返回令牌（令牌只在 HttpOnly Cookie 里，后台方案第 7 节）；模拟层带一个演示用的 token */
export interface AdminSession {
  token?: string;
  adminId: number;
  username: string;
  displayName: string;
  /** 首次登录必须先改密码 */
  mustChangePassword: boolean;
}

// ---------- 仪表盘 ----------
export interface DashboardStats {
  todayRegistrations: number;
  onlineCustomers: number;
  todayDeposit: string;
  todayDepositCount: number;
  todayWithdraw: string;
  todayWithdrawCount: number;
  openPositions: number;
  platformPnl: string;
  platformNetExposure: string;
  /** 为空表示没有设置平台阈值 */
  platformThreshold: string | null;
  pendingKyc: number;
  pendingWithdrawals: number;
  failedTasks: number;
  openReconciliations: number;
  updatedAt: string;
}

/** 当前管理员（GET /api/admin/me）。menus 是后端按权限点和"是否显示"过滤后的菜单；模拟层不返回，按本地菜单配置显示 */
export interface CurrentAdmin {
  admin: AdminUser;
  roles: Role[];
  permissions: string[];
  isSuperAdmin: boolean;
  menus?: MenuNode[];
}

export interface MenuBadges {
  kyc: number;
  withdrawals: number;
}

// ---------- 客户 ----------
export type CustomerStatus = 'active' | 'frozen';
export type KycStatus = 'none' | 'pending' | 'approved' | 'rejected';

export interface CustomerSummary {
  id: number;
  name: string;
  /** 机器人开户的客户（纯 Telegram）没有邮箱（TG 机器人第 1 批） */
  email: string | null;
  /** 有 TG 账户的客户：TG 用户编号（字符串，超过 JS 安全整数也不丢精度）和用户名（可能为空）；没有 TG 账户时都为空 */
  telegramId?: string | null;
  telegramUsername?: string | null;
  nationality: string;
  status: CustomerStatus;
  kycStatus: KycStatus;
  balance: string;
  openPositions: number;
  registeredAt: string;
  lastLoginAt: string | null;
}

export interface CustomerQuery extends PageQuery {
  keyword?: string;
  status?: CustomerStatus;
  kycStatus?: KycStatus;
}

/** 资金流水类型；真实接口里没有对应的账本类型用小写原名（如 order_freeze） */
export type LedgerType = 'deposit' | 'withdraw' | 'fee' | 'pnl' | 'swap' | 'adjust' | 'refund' | string;

export interface LedgerEntry {
  id: string;
  type: LedgerType;
  amount: string;
  balanceAfter: string;
  ref: string;
  time: string;
}

export interface Position {
  id: string;
  customerId: number;
  customerName: string;
  symbol: string;
  side: Side;
  lots: string;
  openPrice: string;
  currentPrice: string;
  leverage: number;
  margin: string;
  /** 真实接口（客户详情）在没有报价时为空，页面用 Amount 显示成"—" */
  pnl: string;
  /** 保证金水平（百分比字符串，如 "182.5"）；真实接口没有报价时为空 */
  marginLevel: string;
  liquidationPrice: string;
  stopLoss: string | null;
  takeProfit: string | null;
  openedAt: string;
}

/** 平仓原因：客户平仓 / 止盈 / 止损 / 强平 */
export type CloseReason = 'closed' | 'tp' | 'sl' | 'liquidated';

/**
 * 已平仓记录（GET /api/admin/positions/closed，体验优化 1021 UX-6 起）。
 * partial 为 true 表示部分平仓拆出来的记录，parentId 是它来自的原持仓编号；整笔平仓 parentId 为空。
 */
export interface ClosedPosition {
  id: string;
  parentId: string | null;
  partial: boolean;
  customerId: number;
  customerName: string;
  symbol: string;
  side: Side;
  lots: string;
  openPrice: string;
  closePrice: string;
  leverage: number;
  margin: string;
  fee: string;
  swap: string;
  pnl: string;
  reason: CloseReason;
  openedAt: string;
  closedAt: string;
}

export interface CustomerDetail extends CustomerSummary {
  /** 最近一次登录的 IP 和时间（v2 E6，取最近一个登录会话）；从没登录过为空 */
  lastLoginIp?: string | null;
  lastLoginIpAt?: string | null;
  firstName: string | null;
  middleName: string | null;
  lastName: string | null;
  birthDate: string | null;
  frozenReason: string | null;
  kyc: {
    status: KycStatus;
    docType: DocType | null;
    docNumber: string | null;
    reviewedAt: string | null;
    rejectReason: string | null;
  };
  account: {
    balance: string;
    usedMargin: string;
    available: string;
    floatingPnl: string;
    frozenWithdraw: string;
  };
  positions: Position[];
  ledger: LedgerEntry[];
  /** 最近 20 条已平仓记录（部分平仓的标出原持仓编号） */
  closedPositions: ClosedPosition[];
  /** 第 4 批：提现安全（提现密码、提现冻结） */
  withdrawSafety?: WithdrawSafety | null;
  /** 第 4 批 4b：冻结时暂停的已审核待放款提现（解除冻结后不自动继续，逐笔选"继续放款"或"取消并退回"） */
  heldWithdrawals?: HeldWithdrawal[];
}

export interface HeldWithdrawal {
  id: string;
  amount: string;
  chain: Chain;
  address: string;
  heldAt: string;
  reason: string;
  /** 交过钱包提供方（可能已在付款）：不能直接取消退回 */
  sentBefore: boolean;
}

/** 冻结来源：30 天内提现密码锁 2 次 / 网页申请冻结 / 机器人里自己冻结 / 换绑争议 / 换绑证明阶段 */
export type WithdrawFreezeSource = 'pin_locks' | 'web_request' | 'bot_self' | 'dispute' | 'recovery' | 'admin';

export interface WithdrawSafety {
  pinSet: boolean;
  pinLockedUntil: string | null;
  withdrawFrozen: boolean;
  frozenSource: WithdrawFreezeSource | null;
  frozenAt: string | null;
  /** 4b 审查 🔴R2：全部来源的冻结（一个来源一条；有任何一条就是冻结），最早的在前 */
  freezes?: { source: WithdrawFreezeSource; reason: string | null; at: string }[];
}

/** 冻结的全部来源（后端给了 freezes 就用它，没有时用最早的那个来源） */
export function freezesOf(s: WithdrawSafety): { source: WithdrawFreezeSource; reason: string | null; at: string }[] {
  if (s.freezes) return s.freezes;
  return s.withdrawFrozen && s.frozenSource ? [{ source: s.frozenSource, reason: null, at: s.frozenAt ?? '' }] : [];
}

/** 后台编辑客户资料（v2 E1）：只有这 5 项；邮箱不能改，状态只能用冻结 / 解冻改 */
export interface CustomerProfileInput {
  firstName: string;
  middleName: string;
  lastName: string;
  /** YYYY-MM-DD */
  birthDate: string;
  /** ISO 3166-1 两位大写字母 */
  nationality: string;
}

export interface AdjustLimits {
  singleLimit: string;
  dailyLimit: string;
  usedToday: string;
}

// ---------- KYC ----------
export type DocType = 'id_card' | 'passport';

export interface KycApplication {
  id: string;
  customerId: number;
  name: string;
  /** 纯 Telegram 客户为空 */
  email: string | null;
  nationality: string;
  birthDate: string;
  docType: DocType;
  docNumber: string;
  status: Exclude<KycStatus, 'none'>;
  submittedAt: string;
  reviewer: string | null;
  reviewedAt: string | null;
  rejectReason: string | null;
}

export interface KycQuery extends PageQuery {
  status?: KycApplication['status'];
  keyword?: string;
}

// ---------- 资金 ----------
export type DepositStatus = 'confirming' | 'credited' | 'ignored' | 'reversed';

export interface Deposit {
  id: string;
  customerId: number;
  customerName: string;
  /** 纯 Telegram 客户为空 */
  email: string | null;
  chain: Chain;
  txHash: string;
  amount: string;
  confirmations: number;
  requiredConfirmations: number;
  status: DepositStatus;
  detectedAt: string;
  creditedAt: string | null;
  /** 第 1 批：哪个钱包提供方通知的到账（TOPUP）、提供方到账编号（原型 P5）；自带钱包时代的旧数据为空 */
  provider?: string | null;
  providerRef?: string | null;
}

export interface DepositQuery extends PageQuery {
  chain?: Chain;
  status?: DepositStatus;
  keyword?: string;
  /** 第 1 批：提供方到账编号、到账时间范围（到秒，本地时间 "YYYY-MM-DD HH:mm:ss"） */
  providerRef?: string;
  from?: string;
  to?: string;
}

export type WithdrawalStatus =
  | 'cooling'
  | 'pending'
  | 'delayed'
  | 'broadcasting'
  | 'completed'
  | 'rejected'
  | 'cancelled'
  | 'failed';

export interface Withdrawal {
  id: string;
  customerId: number;
  customerName: string;
  /** 纯 Telegram 客户为空 */
  email: string | null;
  amount: string;
  fee: string;
  chain: Chain;
  address: string;
  firstUse: boolean;
  whitelistAddedAt: string;
  status: WithdrawalStatus;
  /** 延迟期剩余分钟数（仅 delayed） */
  delayRemainingMinutes: number | null;
  submittedAt: string;
  reviewer: string | null;
  reason: string | null;
  txHash: string | null;
  /** v2 自动审批（内部文档）：最近一次规则判定的摘要；没有判定记录（功能上线前的提现）时为空 */
  autoReview?: AutoReviewSummary | null;
  /** 第 1 批两道审：钱包提供方、付款编号、付款状态（unknown 标红、不能退款）、原因、进入当前状态的时间（到秒） */
  provider?: string | null;
  payoutNo?: string | null;
  payoutStatus?: PayoutStatus | null;
  payoutReason?: string | null;
  statusAt?: string | null;
  /** 第 4 批：tg 按 TG 规则（一律人工审核）；web 原规则 */
  ruleType?: 'web' | 'tg';
  /** 提交入口：web 网页登录的会话 / bot 从机器人进入的会话 */
  source?: 'web' | 'bot';
  /** 没邮箱的 TG 客户的首笔：通过前要人工核实（勾"已核实首笔"） */
  firstCheck?: boolean;
  firstChecked?: boolean;
}

/** 钱包提供方那边的付款状态：提交中 / 待放款审核 / 已批准、已广播（放款中）/ 成功 / 失败、驳回、取消 / 未知（查不清，不退款） */
export type PayoutStatus = 'submitting' | 'pending' | 'approved' | 'broadcast' | 'completed' | 'failed' | 'rejected' | 'cancelled' | 'unknown';

/** 第 1 批：钱包提供方页（GET /api/admin/payment，原型 P7） */
export interface PaymentProvider {
  code: string;
  name: string;
  connected: boolean;
  current: boolean;
  up: boolean;
  lastOkAt: string | null;
  lastCallbackAt: string | null;
  available: string | null;
  balanceAt: string | null;
  error: string | null;
}

export interface PaymentOverview {
  providers: PaymentProvider[];
  networks: { network: Chain; open: boolean }[];
  audits: { id: string; admin: string; action: 'SWITCH_PROVIDER' | 'NETWORK_ON' | 'NETWORK_OFF' | string; detail: string; reason: string; at: string }[];
}

/** 最近一次对账（GET /api/admin/recon/status）：checked 和提供方逐笔核对的笔数；providerOk = false 时提供方不通、按单号核对没跑成 */
export interface ReconStatus {
  lastRunAt: string | null;
  checked: number;
  providerOk: boolean;
  providerError: string | null;
  open: number;
}

// ---------- v2 提现自动审批（内部文档 第 7.2 节；后端 admin/ops/AutoApproveOps.java） ----------
export type AutoRuleCode = 'R1' | 'R2' | 'R3' | 'R4' | 'R5' | 'R6' | 'R7' | 'R8' | 'R9' | 'R10' | 'R11' | 'R12' | 'R13';
/** 判定结果：自动通过 / 转人工（有规则没通过）/ 总开关关闭（全部人工，规则照常判定做参考）/ 判定出错（按转人工处理） */
export type AutoReviewOutcome = 'auto_approved' | 'manual' | 'master_off' | 'error';
/** 判定时机：提交时预判（只做参考，不占额度）/ 冷静期结束时复判（以这次为准） */
export type AutoReviewPhase = 'precheck' | 'final';

export interface AutoReviewSummary {
  phase: AutoReviewPhase;
  outcome: AutoReviewOutcome;
  /** 没通过的规则（含无法判定的），按编号排序；全部通过时为空 */
  failed: AutoRuleCode[];
  decidedAt: string;
}

/** 一条规则的判定结果。values 是判定时刻的实际数值和阈值（快照），前端按规则的模板显示，以后改规则不影响 */
export interface AutoRuleResult {
  code: AutoRuleCode;
  status: 'passed' | 'failed' | 'disabled' | 'unknown';
  values: Record<string, string>;
}

export interface AutoReviewDetail extends AutoReviewSummary {
  /** 判定时总开关的状态（快照） */
  masterEnabled: boolean;
  rules: AutoRuleResult[];
  /** 判定时用的规则配置版本和这一版的修改人、时间 */
  configVersion: number;
  configChangedAt: string | null;
  configChangedBy: string | null;
  /** 复判记录上附带提交时的预判摘要（冷静期中的提现只有预判） */
  precheck: AutoReviewSummary | null;
}

/** 出金列表的"规则判定"筛选：判定结果，或触发了某条规则 */
export type AutoReviewFilter = 'auto_approved' | 'manual' | 'master_off' | 'none' | AutoRuleCode;

export interface AutoRuleSetting {
  code: AutoRuleCode;
  enabled: boolean;
  /** 数值参数（字符串，和系统配置一样）；没有数值的规则为空对象 */
  params: Record<string, string>;
}

export interface AutoApproveSettings {
  /** 总开关，默认关闭（= 全部人工审核） */
  masterEnabled: boolean;
  rules: AutoRuleSetting[];
  /** 配置版本：每次保存 +1，判定记录里记这个号 */
  version: number;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface AutoApproveSettingsInput {
  masterEnabled: boolean;
  rules: AutoRuleSetting[];
  reason: string;
  /** 页面打开时读到的版本；后端发现版本变了返回 409 CONFIG_CHANGED（防止两个人同时改互相覆盖） */
  expectedVersion: number;
}

/** 一次保存的修改记录（同时写审计日志 withdrawal.auto_rule_update） */
export interface AutoApproveChange {
  id: string;
  version: number;
  time: string;
  adminName: string;
  reason: string;
  /** field：master，或 R1.enabled、R1.maxAmount 这样的"规则.参数" */
  changes: { field: string; before: string; after: string }[];
}

export interface WithdrawalDetail extends Withdrawal {
  /** v2 自动审批：规则判定卡片的数据（逐条结果；判定时有几条就是几条，R13 上线前的记录只有 12 条） */
  autoReviewDetail?: AutoReviewDetail | null;
  kycStatus: KycStatus;
  registeredDays: number;
  totalDeposit: string;
  balance: string;
  openPositions: number;
  /** txHash、explorerUrl（第 4 批 4c，KYC 核来源）：交易哈希和区块浏览器链接，审核员按它查付款地址 */
  recentDeposits: { time: string; chain: Chain; amount: string; txHash?: string | null; explorerUrl?: string | null }[];
  recentWithdrawals: { time: string; chain: Chain; amount: string }[];
  /** 第 4 批审查：账户冻结、提现冻结（冻结时后端拒绝通过）、最近 48 小时的安全事件 */
  safety?: WithdrawalSafety | null;
}

export interface WithdrawalSafety {
  accountFrozen: boolean;
  withdrawFrozen: boolean;
  withdrawFrozenSource: WithdrawFreezeSource | null;
  /** 4b 审查 🔴R2：全部冻结来源 */
  withdrawFrozenSources?: WithdrawFreezeSource[];
  /** 类型小写（password_change、pin_set、kyc_first、freeze 等），最新的在前 */
  securityEvents48h: { type: string; at: string }[];
}

/** 筛选：open = 待处理（全部：冷静期 + 待审核 + 延迟期） */
export interface WithdrawalQuery extends PageQuery {
  status?: WithdrawalStatus | 'open';
  chain?: Chain;
  minAmount?: string;
  maxAmount?: string;
  keyword?: string;
  /** v2 自动审批：按最近一次判定的结果或触发的规则筛选 */
  autoReview?: AutoReviewFilter;
}

export interface WithdrawalSummary {
  pending: { count: number; amount: string };
  delayed: { count: number; amount: string };
  cooling: { count: number; amount: string };
  completedToday: { count: number; amount: string };
}

/** 对账问题类型：钱包 ↔ 账本 7 种（数据库设计第 16 节、后台方案第 8 节）；第 1 批和钱包提供方按单号逐笔核对 6 种（归集的 3 种随自带钱包淘汰） */
export type ReconType =
  | 'deposit_ledger_mismatch'
  | 'deposit_unknown_credit'
  | 'deposit_reversal_mismatch'
  | 'deposit_not_on_chain'
  | 'suspense_open'
  | 'withdraw_ledger_mismatch'
  | 'withdraw_frozen_mismatch'
  | 'deposit_provider_missing'
  | 'deposit_provider_mismatch'
  | 'deposit_trading_missing'
  | 'payout_provider_missing'
  | 'payout_amount_mismatch'
  | 'payout_status_mismatch';

/** 对账问题（后台方案第 8 节）：ref 是关联的业务编号，expected 应有、actual 实际、diff = actual - expected */
export interface ReconItem {
  id: string;
  type: ReconType | string;
  ref: string;
  customerId: number | null;
  customerName: string | null;
  expected: string | null;
  actual: string | null;
  diff: string | null;
  /** 后端生成的说明（中文） */
  detail: string | null;
  status: 'open' | 'resolved';
  note: string | null;
  handler: string | null;
  detectedAt: string;
  resolvedAt: string | null;
}

/**
 * 热钱包余额（对账页上方，GET /api/admin/hot-wallets）。status：ok 正常、low 低于告警下限、off 下限为 0 不告警、unknown 还没查到余额。
 * threshold 是系统配置页的下限（hotWallet.*）；error 是最近一次查询失败的原因
 */
export interface HotWalletBalance {
  chain: 'ERC20' | 'BSC' | 'TRC20';
  asset: 'USDT' | 'ETH' | 'BNB' | 'TRX';
  gas: boolean;
  address: string;
  balance: string | null;
  threshold: string;
  status: 'ok' | 'low' | 'off' | 'unknown';
  checkedAt: string | null;
  error: string | null;
}

export interface ReconQuery extends PageQuery {
  status?: ReconItem['status'];
}

/** 任务类型：只有邮件和推送（提现的广播和确认不走任务表，后台方案第 8 节） */
export type TaskType = 'email' | 'push';
export type TaskStatus = 'pending' | 'processing' | 'success' | 'failed';

export interface TaskItem {
  id: string;
  type: TaskType;
  bizRef: string;
  status: TaskStatus;
  attempts: number;
  maxAttempts: number;
  nextRetryAt: string | null;
  lastError: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TaskQuery extends PageQuery {
  type?: TaskType;
  status?: TaskStatus;
}

// ---------- 交易监控 ----------
export type OrderType = 'market' | 'limit';
/** 订单状态：被拒绝的下单请求后端不保存，所以没有 rejected（后台方案第 9 节） */
export type OrderStatus = 'filled' | 'pending' | 'cancelled';
export type RejectReason =
  | 'outside_session'
  | 'symbol_paused'
  | 'global_paused'
  | 'fx_stale'
  | 'leverage_exceeded'
  | 'position_limit'
  | 'insufficient_balance';

export interface Order {
  id: string;
  customerId: number;
  customerName: string;
  symbol: string;
  side: Side;
  type: OrderType;
  lots: string;
  price: string;
  leverage: number;
  fee: string;
  status: OrderStatus;
  rejectReason: RejectReason | null;
  createdAt: string;
}

export interface TradeQuery extends PageQuery {
  symbol?: string;
  side?: Side;
  status?: OrderStatus;
  keyword?: string;
}

export interface PendingOrder {
  id: string;
  customerId: number;
  customerName: string;
  symbol: string;
  side: Side;
  lots: string;
  limitPrice: string;
  currentPrice: string;
  leverage: number;
  frozenMargin: string;
  stopLoss: string | null;
  takeProfit: string | null;
  createdAt: string;
}

// ---------- 风控 ----------
export type ExposureStatus = 'normal' | 'near' | 'auto_blocked' | 'manual_paused';

export interface SymbolExposure {
  symbol: string;
  nameZh: string;
  nameEn: string;
  longUsd: string;
  shortUsd: string;
  netUsd: string;
  /** 为空表示这个品种不监控净敞口 */
  thresholdUsd: string | null;
  status: ExposureStatus;
}

export interface RiskSettings {
  globalPause: boolean;
  autoLiquidation: boolean;
  platformNetUsd: string;
  /** 为空表示不监控平台总净敞口 */
  platformThresholdUsd: string | null;
  /** 平台总净敞口超过阈值后自动禁止全部新开仓 */
  platformBlocked: boolean;
  perUserLimitUsd: string;
  updatedAt: string;
}

export type RiskEventType =
  | 'auto_block_symbol'
  | 'auto_block_platform'
  | 'manual_pause'
  | 'manual_resume'
  | 'global_pause_on'
  | 'global_pause_off'
  | 'auto_liq_on'
  | 'auto_liq_off'
  | 'threshold_change'
  | 'fx_stale_block';

export interface RiskEvent {
  id: string;
  time: string;
  actorType: 'system' | 'admin';
  actorName: string | null;
  type: RiskEventType;
  symbol: string | null;
  value: string | null;
  threshold: string | null;
  reason: string | null;
}

/** 风控看板（后端字段是 symbols，这里叫 exposures，在 api/real.ts 转换） */
export interface RiskOverview {
  settings: RiskSettings;
  exposures: SymbolExposure[];
  events: RiskEvent[];
}

// ---------- 品种与行情 ----------
/** 原型用细分的外汇类别；后端（真实接口）外汇只有一个 fx */
export type SymbolCategory = 'fx' | 'forex_major' | 'forex_cross' | 'cnh' | 'metal' | 'index' | 'energy' | 'crypto' | 'stock';

/**
 * 品种（字段按后端，后台方案第 9 节）：没有原型的 lpSymbol、spreadPoints、sessionId；
 * 有 markup（统一加点，价格单位）、maxLots、lotStep、openPaused（风控暂停开仓）、sortOrder。
 */
export interface TradingSymbol {
  code: string;
  nameZh: string;
  nameEn: string;
  category: SymbolCategory;
  quoteCurrency: string;
  contractSize: string;
  minLots: string;
  maxLots: string;
  lotStep: string;
  maxLeverage: number;
  maintenanceMarginRate: string;
  openFeeRate: string;
  /** 隔夜费率（每日，按名义价值），null 表示未配置：不收隔夜费，后台标红（真实接口里多空都是 0 即未配置） */
  swapLong: string | null;
  swapShort: string | null;
  /** 统一加点（价格单位），只在行情入口加一次 */
  markup: string;
  digits: number;
  hot: boolean;
  enabled: boolean;
  /** 风控暂停开仓（在风控页操作） */
  openPaused: boolean;
  sortOrder: number;
  updatedAt: string | null;
  /** 最后一条正常报价的时间（v2 E6）；从没收到过为空（显示"暂无报价"） */
  lastQuoteAt?: string | null;
  /** 现在是否在交易时段内：休市时没有报价是正常的，不标红 */
  tradingOpen?: boolean;
  /** 模拟起始价（只在模拟报价源下使用，见 SymbolPage.simulatedQuotes）；没填为空 */
  simStartPrice?: string | null;
  /** 当前报价源：alltick / itick / binance / simulated；没有报价源为空（内部文档 第 5.5 节） */
  quoteSource?: string | null;
  /** primary 主源 / backup 正在用备源 / deviation 两家偏差过大、暂停开仓（平仓照常，用主源价格）；模拟为空 */
  quoteSourceState?: 'primary' | 'backup' | 'deviation' | null;
}

/** 品种列表：多一个 simulatedQuotes（当前报价源是不是模拟源，是的话新增和编辑里显示"模拟起始价"） */
export interface SymbolPage extends Page<TradingSymbol> {
  simulatedQuotes: boolean;
}

/**
 * 改品种参数：合约大小、报价货币、小数位不能改；openPaused 是品种自己的"暂停开仓"（新增的品种默认暂停，在这里打开）；
 * simStartPrice 给已建好的品种补模拟起始价（只在模拟报价源下生效，否则后端忽略；不传表示不改）。
 */
export type SymbolPatch = Partial<
  Pick<
    TradingSymbol,
    'nameZh' | 'nameEn' | 'maxLeverage' | 'minLots' | 'maxLots' | 'lotStep' | 'openFeeRate' | 'maintenanceMarginRate' | 'markup' | 'swapLong' | 'swapShort' | 'hot' | 'sortOrder' | 'openPaused'
  >
> & { simStartPrice?: string };

/** 新增品种的交易时段模板（UTC）：fx 周日 22:00–周五 21:00；crypto 全天候；us_stock 周一至周五 13:30–20:00 */
export type SessionTemplate = 'fx' | 'crypto' | 'us_stock';

/**
 * 新增品种（v2 E3）。新增后默认暂停开仓；隔夜费率未配置（标红）。
 * 交易时段二选一：copySessionsFrom（从已有品种复制）或 sessionTemplate（模板）。
 */
export interface SymbolCreateInput {
  code: string;
  nameZh: string;
  nameEn: string;
  category: SymbolCategory;
  digits: number;
  contractSize: string;
  quoteCurrency: string;
  maxLeverage: number;
  maintenanceMarginRate: string;
  openFeeRate: string;
  minLots: string;
  maxLots: string;
  lotStep: string;
  markup: string;
  hot: boolean;
  copySessionsFrom?: string;
  sessionTemplate?: SessionTemplate;
  /** 模拟起始价（可选，正数，小数位不超过 digits）：只在模拟报价源下生效，否则后端忽略 */
  simStartPrice?: string;
}

export interface SymbolQuery extends PageQuery {
  category?: SymbolCategory;
  keyword?: string;
  enabled?: boolean;
  missingSwap?: boolean;
}

/** 一个品种一周的交易时段（UTC，只读，后台方案第 9 节）；day 1 = 周一 … 7 = 周日 */
export interface TradingSession {
  symbol: string;
  ranges: { day: number; open: string; close: string }[];
}

/** 休市日：按品种（"*" 表示全部品种），只有全天休市 */
export interface Holiday {
  id: string;
  date: string;
  symbol: string;
  reason: string | null;
}

export interface HolidayInput {
  symbol: string;
  date: string;
  reason?: string;
}

export interface FxRate {
  /** 后端另带货币代码 */
  currency?: string;
  pair: string;
  rate: string;
  source: string;
  updatedAt: string | null;
  ageSeconds: number | null;
  status: 'ok' | 'stale';
  affectedSymbols: string[];
}

// ---------- 交易参数 ----------
/** 交易参数：追保线和追保通知间隔；强平按品种维持保证金率逐仓处理，没有账户级强平线（G6），liquidationLevel 总是空 */
export interface TradingParams {
  /** 追保线（保证金水平 %） */
  marginCallLevel: string;
  liquidationLevel: string | null;
  marginCallThrottleMinutes: number;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface TradingParamsInput {
  marginCallLevel: string;
  marginCallThrottleMinutes: number;
}

// ---------- 通知 ----------
/** telegram：TG 机器人第 1 批（只发给有 TG 账户的客户） */
export type NotifyChannel = 'inbox' | 'push' | 'email' | 'telegram';

export interface NotifyTemplate {
  id: string;
  code: string;
  nameZh: string;
  nameEn: string;
  builtIn: boolean;
  enabled: boolean;
  channels: NotifyChannel[];
  titleZh: string;
  bodyZh: string;
  titleEn: string;
  bodyEn: string;
  variables: string[];
  updatedAt: string;
}

export interface NotifyRecord {
  id: string;
  /** 按邮箱发的（风控人员等）没有客户号，customerName 是收件人 */
  customerId: number | null;
  customerName: string;
  source: 'template' | 'manual';
  templateCode: string | null;
  channel: NotifyChannel;
  title: string;
  status: 'pending' | 'sent' | 'failed' | 'skipped';
  error: string | null;
  operator: string | null;
  time: string;
}

export interface NotifyRecordQuery extends PageQuery {
  channel?: NotifyChannel;
  status?: NotifyRecord['status'];
  keyword?: string;
}

export interface ManualNotifyInput {
  customerId: number;
  channels: NotifyChannel[];
  title: string;
  body: string;
  reason: string;
}

// ---------- 系统 ----------
export interface AdminUser {
  id: number;
  username: string;
  displayName: string;
  email: string;
  roleIds: string[];
  status: 'active' | 'disabled';
  mustChangePassword: boolean;
  lastLoginAt: string | null;
  createdAt: string;
}

/** 新建管理员、重置密码的结果：临时密码只返回这一次（后台方案第 7 节），页面在弹窗里显示一次 */
export interface TemporaryPassword {
  id: number;
  temporaryPassword: string;
}

export interface AdminInput {
  username: string;
  displayName: string;
  email: string;
  roleIds: string[];
}

export interface Role {
  id: string;
  code: RoleCode;
  nameZh: string;
  nameEn: string;
  description: string;
  permissions: string[];
  builtIn: boolean;
  memberCount: number;
}

export interface RoleInput {
  code: string;
  nameZh: string;
  nameEn: string;
  description: string;
  permissions: string[];
}

export interface MenuNode {
  key: string;
  path: string | null;
  labelKey: string;
  permission: string | null;
  order: number;
  visible: boolean;
  children?: MenuNode[];
}

export interface PermissionDef {
  code: string;
  module: string;
  highRisk: boolean;
}

export type AuditValue = string | number | boolean | null | string[];

export interface AuditLog {
  id: string;
  time: string;
  adminName: string;
  ip: string;
  action: string;
  target: string;
  highRisk: boolean;
  result: 'success' | 'failure';
  reason: string | null;
  before: Record<string, AuditValue> | null;
  after: Record<string, AuditValue> | null;
}

export interface AuditQuery extends PageQuery {
  action?: string;
  admin?: string;
  highRiskOnly?: boolean;
  keyword?: string;
}

export interface SystemConfig {
  key: string;
  value: string;
  /** eth / bnb / trx：热钱包手续费币的告警下限 */
  unit: 'usdt' | 'hours' | 'minutes' | 'count' | 'percent' | 'text' | 'bool' | 'eth' | 'bnb' | 'trx';
  /** hotWallet：热钱包余额告警下限（0 = 不告警，原型）；payment：钱包提供方可用余额告警下限（0 = 不告警，生产方案 v1 07 页 R-5） */
  group: 'withdraw' | 'deposit' | 'security' | 'adjust' | 'notify' | 'hotWallet' | 'payment' | 'telegram';
  /** 从没改过时为空 */
  updatedAt: string | null;
}



// ---- 第 3 批：报价服务（原型 P8～P11；数据在报价服务里，后台转发，只计量不收费） ----
export interface QuoteLimits {
  restPerSecond: number;
  restPerDay: number;
  wsConnections: number;
  /** 每条 WebSocket 连接最多订阅几个品种 */
  wsSymbols: number;
}
/** 对外开放总开关（默认关；只有超管能改） */
export interface QuoteGate {
  open: boolean;
  updatedBy: string | null;
  reason: string | null;
  updatedAt: string | null;
}
export interface QuoteClient {
  id: number;
  code: string;
  name: string;
  email: string | null;
  active: boolean;
  note: string | null;
  createdAt: string;
  keyCount: number;
}
export type QuoteKeyKind = 'INTERNAL_TEST' | 'EXTERNAL';
export interface QuoteKey {
  keyId: string;
  clientId: number;
  clientName: string | null;
  kind: QuoteKeyKind;
  active: boolean;
  /** 现在能不能用：没停用，并且（内部测试 Key，或总开关开着） */
  usableNow: boolean;
  /** 可用品种；空 = 全部已授权品种 */
  symbols: string[];
  limits: QuoteLimits;
  createdAt: string;
  disabledAt: string | null;
  lastUsedAt: string | null;
  rotatedFrom: string | null;
  usedToday: number;
  rejectedToday: number;
  connections: number;
}
export interface QuoteOverview {
  gate: QuoteGate;
  defaults: QuoteLimits;
  /** 服务器配好了加密 Secret 的密钥（FX_QUOTE_KEY_ENC_KEY）；没配不能建 Key */
  keyEncReady: boolean;
  clients: QuoteClient[];
  keys: QuoteKey[];
}
/** 建 Key、轮换的返回：secret 只在这里出现这一次 */
export interface QuoteKeyCreated {
  key: QuoteKey;
  secret: string;
}
export interface QuoteUsageDay {
  day: string;
  keyId: string;
  restCalls: number;
  restRejected: number;
  wsPushes: number;
  wsSeconds: number;
  wsPeak: number;
}
export interface QuoteUsageRow extends QuoteUsageDay {
  /** 空 = WebSocket */
  endpoint: string;
  /** 空 = 不分品种 */
  symbol: string;
}
export interface QuoteUsage {
  from: string;
  to: string;
  rows: QuoteUsageRow[];
  days: QuoteUsageDay[];
}
export interface QuoteUsageQuery {
  from?: string;
  to?: string;
  keyId?: string;
  clientId?: number;
  symbol?: string;
}
export type QuoteGrantState = 'ok' | 'soon' | 'expired' | 'none' | 'notYet';
export interface QuoteGrant {
  granted: boolean;
  from: string | null;
  until: string | null;
  state: QuoteGrantState;
  daysLeft: number | null;
}
export interface QuoteLicenseRow {
  feed: string;
  symbol: string;
  trading: QuoteGrant;
  display: QuoteGrant;
  resale: QuoteGrant;
  note: string | null;
}
export interface QuoteLicenses {
  gate: QuoteGate;
  /** 授权检查是否启用（生产启用；测试环境不受限制） */
  enforced: boolean;
  today: string;
  items: QuoteLicenseRow[];
  /** 没有任何报价源登记"交易用途"的品种：生产里只显示、不开仓 */
  notTradable: string[];
}
export interface QuoteLicenseInput {
  feed: string;
  symbol: string;
  trading: { granted: boolean; from: string | null; until: string | null };
  display: { granted: boolean; from: string | null; until: string | null };
  resale: { granted: boolean; from: string | null; until: string | null };
  note: string;
  reason: string;
}
export type QuoteSourceState = 'ok' | 'backup' | 'deviation' | 'down' | 'closed';
export interface QuoteSourceRow {
  symbol: string;
  primary: string | null;
  backup: string | null;
  current: string | null;
  lastQuoteAt: string | null;
  switches24h: number;
  deviations24h: number;
  lastSwitchAt: string | null;
  state: QuoteSourceState;
}
export interface QuoteSources {
  mode: string;
  feeds: { feed: string; configured: boolean; ok: boolean; detail: string | null }[];
  rows: QuoteSourceRow[];
}

/** 第 4 批 4b：找回申请（人工换绑 / 没邮箱人工重设提现密码），Amos 一人审核 */
export type RecoveryStatus = 'objection' | 'proof' | 'approved' | 'executed' | 'rejected' | 'cancelled';

export interface Recovery {
  id: string;
  kind: 'rebind' | 'pin_reset';
  customerId: number;
  customerName: string | null;
  /** 当前绑的 TG（@用户名 · 编号） */
  customerTelegram: string | null;
  kycStatus: string | null;
  applicantTgId: string | null;
  applicantTgUsername: string | null;
  status: RecoveryStatus;
  disputed: boolean;
  disputePinOk: boolean;
  disputeFrozen: boolean;
  objectionEndsAt: string | null;
  proofNote: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  executeAt: string | null;
  revokeRequested: boolean;
  revokeRequestedAt: string | null;
  /** Amos 选过"维持"的时间：之后原 TG 再撤销只记经过、发告警，不再暂停 */
  keptAt?: string | null;
  closedBy: string | null;
  closeReason: string | null;
  closedAt: string | null;
  createdAt: string;
}

export interface RecoveryDetail {
  recovery: Recovery;
  events: { action: string; actor: string; note: string | null; at: string }[];
  /** 第 4 批 4c：最近 10 笔充值（交易哈希、区块浏览器链接），按 08 页第 0 节判断"合格来源地址" */
  deposits?: { time: string; chain: Chain; amount: string; txHash?: string | null; explorerUrl?: string | null }[];
}

// ---------- 第 5 批：应急开关（TB-34） ----------
/** 一条开关记录：actor 是 admin:编号 或 server（服务器终端 sudo trading set TR_TG_EMERGENCY_LOGIN） */
export interface EmergencyChange {
  id: number;
  on: boolean;
  actor: string;
  actorName: string;
  reason: string;
  at: string;
}

/** GET /api/admin/emergency：现在的状态、我能不能开关（只有 Amos 的后台账号）、开关记录（新的在前） */
export interface EmergencyState {
  on: boolean;
  changedAt: string | null;
  changedBy: string | null;
  reason: string | null;
  canOperate: boolean;
  ownerConfigured: boolean;
  history: EmergencyChange[];
}
