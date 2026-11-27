/**
 * 客户端接口的数据类型。按将来真实后端接口设计：
 * - 金额、价格、数量一律用十进制字符串（后端 BigDecimal 序列化），前端不做浮点累加；
 * - 时间一律用 ISO 8601 字符串（UTC）。
 */

export type Lang = 'zh-CN' | 'en';

// ---------- 账户与认证 ----------
export interface Session {
  /** 只有原型（mock 模式）有；真实环境的令牌在 HttpOnly Cookie 里，前端拿不到 */
  token?: string;
  userId: string;
  /** 机器人开户的客户（纯 Telegram，TG 机器人第 1 批）没有邮箱 */
  email: string | null;
  /** 有 TG 账户的客户的 Telegram 用户名（可能为空） */
  telegramUsername?: string | null;
}

export type EmailCodePurpose = 'register' | 'resetPassword';

export interface RegisterRequest {
  email: string;
  code: string;
  password: string;
  firstName: string;
  middleName: string;
  lastName: string;
  birthDate: string; // YYYY-MM-DD
  nationality: string; // ISO 3166-1 alpha-2
}

export interface Profile {
  userId: string;
  /** 纯 Telegram 客户没有邮箱；姓名、生日、国籍在第一次保存前也是空的（接口返回 null，这里换成空字符串） */
  email: string | null;
  firstName: string;
  middleName: string;
  lastName: string;
  birthDate: string;
  nationality: string;
  registeredAt: string;
}

export type ProfileUpdate = Pick<Profile, 'firstName' | 'middleName' | 'lastName' | 'birthDate' | 'nationality'>;

export type KycStatus = 'none' | 'pending' | 'approved' | 'rejected';
export type KycDocType = 'idCard' | 'passport';

export interface KycInfo {
  status: KycStatus;
  docType?: KycDocType;
  docNumber?: string;
  submittedAt?: string;
  reviewedAt?: string;
  /** 被驳回时的原因（后台审核员填写，中英文由后端按语言返回） */
  rejectReason?: string;
}

export interface KycSubmit {
  docType: KycDocType;
  docNumber: string;
  front: File;
  back: File;
  selfie: File;
}

export interface NotifyPrefs {
  push: boolean;
  email: boolean;
}

// ---------- 行情 ----------
export type SymbolCategory = 'fx' | 'metal' | 'index' | 'energy' | 'crypto' | 'stock';

export interface SymbolInfo {
  code: string;
  nameZh: string;
  nameEn: string;
  category: SymbolCategory;
  /** 价格小数位 */
  digits: number;
  /** 合约大小：1 手 = 多少个基础单位（A-01） */
  contractSize: string;
  /** 合约单位的类型，用于显示"100 盎司/手" */
  unit: 'baseCcy' | 'ounce' | 'barrel' | 'unit' | 'share';
  baseCcy: string;
  quoteCcy: string;
  maxLeverage: number;
  minLots: string;
  maxLots: string;
  lotStep: string;
  /** 开仓手续费率（按名义价值） */
  feeRate: string;
  /** 维持保证金率 */
  maintenanceMarginRate: string;
  hot: boolean;
  /** 暂停开仓（风控暂停，客户只看到状态，看不到原因） */
  openPaused: boolean;
  /** 当前是否在交易时段内 */
  tradingOpen: boolean;
  /**
   * 第 3 批（原型 P3）：现在的报价源有"交易用途"授权，可以开仓。false 时行情、K 线照常，标"暂不开放交易"、下单按钮灰（拒单 SYMBOL_NOT_TRADABLE）。
   * 测试环境总是 true；没有这个字段（老接口）按 true
   */
  tradable?: boolean;
}

export interface Quote {
  symbol: string;
  bid: string;
  ask: string;
  /** 今日开盘价，用于算涨跌 */
  open: string;
  high: string;
  low: string;
  /**
   * 报价对 USD 的折算率（非美元报价品种用实时汇率）。后端暂时取不到汇率时没有（接口返回 null），
   * 这时所有按 USD 折算的预估（保证金、手续费、强平价、浮动盈亏）显示"—"，用 `lib/trading.ts` 的 `usdRate` 取。
   */
  quoteToUsd?: string;
  time: string;
}

export type Timeframe = '1m' | '5m' | '15m' | '1h' | '4h' | '1d';
/** 图表周期：K 线的 6 个周期，加上 Tick 图（逐笔价格） */
export type ChartPeriod = Timeframe | 'tick';

/** 一笔报价（Tick 图用）：GET /api/market/ticks 返回最近 500 笔，之后用推送的报价往后接 */
export interface Tick {
  /** 毫秒级 Unix 时间戳 */
  time: number;
  bid: string;
  ask: string;
}

export interface Kline {
  /** 秒级 Unix 时间戳（UTC） */
  time: number;
  open: string;
  high: string;
  low: string;
  close: string;
  volume: string;
}

// ---------- 交易 ----------
export type Side = 'buy' | 'sell';
export type OrderType = 'market' | 'limit';

export interface Account {
  currency: 'USDT';
  /** 余额（不含提现冻结） */
  balance: string;
  /** 提现冻结 */
  withdrawFrozen: string;
  /** 挂单冻结保证金 */
  orderFrozen: string;
}

export interface Position {
  id: string;
  symbol: string;
  side: Side;
  lots: string;
  leverage: number;
  openPrice: string;
  margin: string;
  /** 开仓时的维持保证金率（追加保证金后按它重算强平价） */
  maintenanceMarginRate: string;
  liquidationPrice: string;
  takeProfit?: string;
  stopLoss?: string;
  openedAt: string;
}

export interface PendingOrder {
  id: string;
  symbol: string;
  side: Side;
  lots: string;
  leverage: number;
  limitPrice: string;
  takeProfit?: string;
  stopLoss?: string;
  frozenMargin: string;
  createdAt: string;
}

export type HistoryKind = 'closed' | 'tp' | 'sl' | 'liquidated' | 'cancelled';

export interface HistoryItem {
  id: string;
  symbol: string;
  side: Side;
  lots: string;
  openPrice: string;
  closePrice?: string;
  pnl?: string;
  fee: string;
  kind: HistoryKind;
  closedAt: string;
  /**
   * 部分平仓拆出来的记录（体验优化 1021 UX-6）：原持仓的编号（对外编号，字符串）；整笔平仓、撤单没有。
   * 页面据此标"部分平仓"。
   */
  parentId?: string;
}

/** 平仓结果（`POST /api/trading/positions/{id}/close`）：closedLots 平掉的手数，remainingLots 剩下的手数（全部平仓时 "0.00"） */
export interface CloseResult {
  pnl: string;
  price: string;
  closedLots: string;
  remainingLots: string;
}

export interface PlaceOrderRequest {
  symbol: string;
  type: OrderType;
  side: Side;
  lots: string;
  leverage: number;
  limitPrice?: string;
  takeProfit?: string;
  stopLoss?: string;
  /** 幂等键：同一次提交重试时不变 */
  clientOrderId: string;
}

export interface PlaceOrderResult {
  kind: 'filled' | 'pending';
  id: string;
  price?: string;
}

/** 下单被拒原因（AC-12） */
export type OrderRejectReason =
  | 'tradingClosed'
  | 'symbolNotTradable'
  | 'symbolPaused'
  | 'globalPaused'
  | 'rateStale'
  | 'leverageExceeded'
  | 'positionLimit'
  | 'insufficientBalance';

// ---------- 资金 ----------
export type Network = 'ERC20' | 'TRC20' | 'BSC';

/**
 * 一条开着的充值网络（第 1 批：`GET /api/wallet/networks`）。address 为空表示还没生成过：客户第一次选这条网络时才向钱包提供方要
 * （`POST /api/wallet/addresses/{network}`，AC-W1）。后台关掉的网络不会出现。确认数由 TopUP 决定，不再给客户显示。
 */
export interface DepositAddress {
  network: Network;
  address: string | null;
  /** 最小充值金额（低于它不入账，AC-41 / AC-W11） */
  minDeposit: string;
}

/** 充值记录状态：待确认 / 已到账 / 已作废（链上回滚）/ 低于最小充值金额 / 已冲正 */
export type DepositStatus = 'pending' | 'credited' | 'dropped' | 'belowMin' | 'reversed';

export interface DepositRecord {
  id: string;
  network: Network;
  txHash: string;
  amount: string;
  status: DepositStatus;
  /** 当前确认数（待确认时显示进度） */
  confirmations: number;
  requiredConfirmations: number;
  time: string;
}

export type LedgerType = 'deposit' | 'withdraw' | 'withdrawRefund' | 'margin' | 'pnl' | 'fee' | 'swap' | 'adjust';

export interface LedgerItem {
  id: string;
  type: LedgerType;
  amount: string;
  /** 附加说明，如品种代码或网络 */
  ref: string;
  time: string;
}

export interface LedgerQuery {
  type: LedgerType | 'all';
  range: '7d' | '30d' | '90d' | 'all';
  page: number;
  pageSize: number;
}

export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}

export interface WhitelistAddress {
  id: string;
  network: Network;
  address: string;
  label: string;
  createdAt: string;
  /** 新地址 24 小时后生效 */
  activeAt: string;
}

export type WithdrawStatus =
  | 'cooling'
  | 'reviewing'
  | 'approved'
  | 'delayed'
  | 'broadcasting'
  | 'completed'
  | 'failedRefunded'
  | 'rejected'
  | 'cancelled';

export interface Withdrawal {
  id: string;
  amount: string;
  fee: string;
  network: Network;
  address: string;
  status: WithdrawStatus;
  createdAt: string;
  /** 冷静期结束时间（冷静期内可撤销） */
  coolingEndsAt: string;
  /** 大额延迟上链的结束时间 */
  delayEndsAt?: string;
  txHash?: string;
  rejectReason?: string;
  /** 钱包提供方（TopUP）的付款编号：交给 TopUP 之后才有 */
  payoutNo?: string | null;
  /** 两道审的第二道（放款中时）：等待放款审核（人工）/ 放款中（提供方那边"未知"的客户也看到放款中） */
  payoutStatus?: 'awaitingRelease' | 'paying' | null;
  /** 每一步进入的时间（资金记录展开看，到秒） */
  steps?: { status: WithdrawStatus; at: string }[];
}

export interface WithdrawInfo {
  kycStatus: KycStatus;
  available: string;
  usedToday: string;
  minAmount: string;
  maxPerTx: string;
  dailyLimit: string;
  /** ≥ 该金额审核后延迟上链 */
  largeAmount: string;
  largeDelayHours: number;
  coolingHours: number;
  fees: Record<Network, string>;
  maxWhitelist: number;
}

export interface WithdrawRequest {
  whitelistId: string;
  amount: string;
}

// ---------- 通知 ----------
export type NotificationKind = 'deposit' | 'withdraw' | 'fill' | 'tpsl' | 'marginCall' | 'liquidation' | 'kyc' | 'system';

export interface NotificationItem {
  id: string;
  kind: NotificationKind;
  title: string;
  body: string;
  read: boolean;
  createdAt: string;
}

/** 服务端推送事件（将来走 WebSocket / Web Push） */
export type ServerEvent =
  | { type: 'account' }
  | { type: 'positions' }
  | { type: 'orders' }
  | { type: 'notification'; item: NotificationItem }
  | { type: 'trade'; kind: 'fill' | 'tp' | 'sl' | 'liquidation'; symbol: string; pnl?: string; price?: string }
  | { type: 'withdraw'; status: WithdrawStatus; amount: string }
  /** 充值记录变了（发现待确认、确认数变化、到账、作废、冲正），客户端重新拉取充值记录（E2E-2） */
  | { type: 'deposit'; status: DepositStatus; network: Network; amount: string; confirmations?: number };
