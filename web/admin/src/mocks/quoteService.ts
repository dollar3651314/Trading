/**
 * 第 3 批报价服务（原型 P8～P11）的模拟层：外部客户和 Key、限额和用量、授权登记和总开关、报价源状态。
 * 和原型一样的示例数据；"今天" = 2026-10-14。总开关默认关；Secret 只在建 Key、轮换的返回里出现一次。
 */
import { ApiError } from '../api/types';
import type {
  QuoteClient,
  QuoteKey,
  QuoteKeyCreated,
  QuoteLicenseInput,
  QuoteLicenseRow,
  QuoteLicenses,
  QuoteLimits,
  QuoteOverview,
  QuoteSources,
  QuoteUsage,
  QuoteUsageQuery,
  QuoteGrant,
} from '../api/types';
import { mockGuards as g } from './handlers';
import { mutate, respond } from './runtime';

const TODAY = '2026-10-14';
const DEFAULT: QuoteLimits = { restPerSecond: 10, restPerDay: 100_000, wsConnections: 2, wsSymbols: 50 };

let seq = 3;
const rnd = () => Math.random().toString(36).slice(2, 10);

const state = {
  gate: { open: false, updatedBy: null as string | null, reason: null as string | null, updatedAt: null as string | null },
  defaults: { ...DEFAULT },
  clients: [
    { id: 1, code: 'QC-0001', name: 'Trading 内部测试', email: null, active: true, note: '内部压测、对接测试用', createdAt: '2026-10-14T02:12:05Z', keyCount: 0 },
    { id: 2, code: 'QC-0002', name: '示例量化工作室', email: 'dev@example.com', active: true, note: 'TM-1 目标客户（示例）', createdAt: '2026-10-14T03:40:22Z', keyCount: 0 },
  ] as QuoteClient[],
  keys: [] as QuoteKey[],
  licenses: [] as QuoteLicenseRow[],
  // 授权检查：和后端默认一致，所有环境默认关（Amos 2026-11-17）
  enforced: false,
};

function key(keyId: string, clientId: number, kind: QuoteKey['kind'], extra: Partial<QuoteKey> = {}): QuoteKey {
  return {
    keyId,
    clientId,
    clientName: state.clients.find((c) => c.id === clientId)?.name ?? null,
    kind,
    active: true,
    usableNow: true,
    symbols: [],
    limits: { ...state.defaults },
    createdAt: '2026-10-14T02:12:05Z',
    disabledAt: null,
    lastUsedAt: null,
    rotatedFrom: null,
    usedToday: 0,
    rejectedToday: 0,
    connections: 0,
    ...extra,
  };
}

state.keys = [
  key('qk_7Hc2Lm4Pq8Rs2Tv6a91F', 1, 'INTERNAL_TEST', { usedToday: 83_400, connections: 1, lastUsedAt: '2026-10-14T07:19:58Z' }),
  key('qk_P2c5Wx7Yz3Ab9Cd19d1B', 1, 'INTERNAL_TEST', { usedToday: 20_000, rejectedToday: 1206, connections: 2, limits: { ...DEFAULT, restPerDay: 20_000 } }),
  key('qk_K3p9Fg5Hj2Kl8Mn4e02D', 2, 'EXTERNAL', { symbols: ['EURUSD', 'GBPUSD', 'XAUUSD', 'XAGUSD'], createdAt: '2026-10-14T03:40:22Z' }),
  key('qk_Zq81Np3Qr6St9Uv277bc', 2, 'EXTERNAL', { active: false, disabledAt: '2026-10-13T11:30:12Z', createdAt: '2026-10-13T10:02:40Z' }),
];

function grant(granted: boolean, from: string | null, until: string | null): QuoteGrant {
  const left = until ? Math.round((Date.parse(until) - Date.parse(TODAY)) / 86_400_000) : null;
  const st: QuoteGrant['state'] = !granted ? 'none' : left !== null && left < 0 ? 'expired' : left !== null && left <= 30 ? 'soon' : 'ok';
  return { granted, from, until, state: st, daysLeft: left };
}

function refresh(): void {
  for (const k of state.keys) {
    k.usableNow = k.active && (k.kind === 'INTERNAL_TEST' || state.gate.open);
    k.clientName = state.clients.find((c) => c.id === k.clientId)?.name ?? null;
  }
  for (const c of state.clients) c.keyCount = state.keys.filter((k) => k.clientId === c.id).length;
}

function created(k: QuoteKey): QuoteKeyCreated {
  return { key: structuredClone(k), secret: `qs_${rnd()}${rnd()}${rnd()}${rnd()}` };
}

function newKeyId(): string {
  return `qk_${rnd()}${rnd()}`.slice(0, 23);
}

// ---- P8 ----
export function getQuoteOverview(): Promise<QuoteOverview> {
  refresh();
  const data = (): QuoteOverview => ({ gate: { ...state.gate }, defaults: { ...state.defaults }, keyEncReady: true, clients: state.clients, keys: state.keys });
  return respond(data, { empty: () => ({ ...data(), clients: [], keys: [] }) });
}

export function createQuoteClient(name: string, email: string, note: string, reason: string): Promise<unknown> {
  return mutate(() => {
    g.requirePerm('quote_key:operate');
    const r = g.requireReason(reason);
    if (!name.trim()) throw new ApiError('errors.quoteServiceRejected', { detail: '名称必填' });
    if (state.clients.some((c) => c.name === name.trim())) throw new ApiError('errors.quoteServiceRejected', { detail: '同名客户已存在' });
    const c: QuoteClient = { id: seq, code: `QC-${String(seq++).padStart(4, '0')}`, name: name.trim(), email: email.trim() || null, active: true, note: note.trim() || null, createdAt: new Date().toISOString(), keyCount: 0 };
    state.clients.push(c);
    g.audit('quote.client_create', c.name, true, r, {}, { code: c.code });
    return c;
  });
}

export function createQuoteKey(clientId: number, kind: string, symbols: string[], reason: string): Promise<QuoteKeyCreated> {
  return mutate(() => {
    g.requirePerm('quote_key:operate');
    const r = g.requireReason(reason);
    if (!state.clients.some((c) => c.id === clientId)) throw new ApiError('errors.quoteServiceRejected', { detail: '没有这个客户' });
    const k = key(newKeyId(), clientId, kind === 'EXTERNAL' ? 'EXTERNAL' : 'INTERNAL_TEST', { symbols, createdAt: new Date().toISOString() });
    state.keys.unshift(k);
    refresh();
    g.audit('quote.key_create', `client ${clientId}`, true, r, {}, { keyId: k.keyId });
    return created(k);
  });
}

export function rotateQuoteKey(keyId: string, reason: string): Promise<QuoteKeyCreated> {
  return mutate(() => {
    g.requirePerm('quote_key:operate');
    const r = g.requireReason(reason);
    const old = state.keys.find((k) => k.keyId === keyId);
    if (!old || !old.active) throw new ApiError('errors.quoteServiceRejected', { detail: 'Key 已停用，不能轮换' });
    old.active = false;
    old.disabledAt = new Date().toISOString();
    const k = key(newKeyId(), old.clientId, old.kind, { symbols: old.symbols, limits: { ...old.limits }, rotatedFrom: keyId, createdAt: new Date().toISOString() });
    state.keys.unshift(k);
    refresh();
    g.audit('quote.key_rotate', keyId, true, r, { keyId }, { keyId: k.keyId });
    return created(k);
  });
}

export function disableQuoteKey(keyId: string, reason: string): Promise<void> {
  return mutate(() => {
    g.requirePerm('quote_key:operate');
    const r = g.requireReason(reason);
    const k = state.keys.find((x) => x.keyId === keyId);
    if (!k) throw new ApiError('errors.notFound');
    k.active = false;
    k.disabledAt = new Date().toISOString();
    k.connections = 0;
    refresh();
    g.audit('quote.key_disable', keyId, true, r, { active: true }, { active: false });
  });
}

function checkLimits(l: QuoteLimits): void {
  if (![l.restPerSecond, l.restPerDay, l.wsConnections, l.wsSymbols].every((v) => Number.isInteger(v) && v > 0)) {
    throw new ApiError('errors.quoteServiceRejected', { detail: '每项要是正整数' });
  }
}

export function setQuoteKeyLimits(keyId: string, l: QuoteLimits, reason: string): Promise<void> {
  return mutate(() => {
    g.requirePerm('quote_limit:edit');
    const r = g.requireReason(reason);
    checkLimits(l);
    const k = state.keys.find((x) => x.keyId === keyId);
    if (!k) throw new ApiError('errors.notFound');
    const before = { ...k.limits };
    k.limits = { ...l };
    g.audit('quote.key_limits', keyId, true, r, before, { ...l });
  });
}

export function setQuoteDefaults(l: QuoteLimits, reason: string): Promise<void> {
  return mutate(() => {
    g.requirePerm('quote_limit:edit');
    const r = g.requireReason(reason);
    checkLimits(l);
    const before = { ...state.defaults };
    state.defaults = { ...l };
    g.audit('quote.default_limits', 'default', true, r, before, { ...l });
  });
}

// ---- P9 ----
export function getQuoteUsage(q: QuoteUsageQuery): Promise<QuoteUsage> {
  const data = (): QuoteUsage => {
    const keys = state.keys.filter((k) => (!q.keyId || k.keyId === q.keyId) && (!q.clientId || k.clientId === q.clientId));
    const rows = keys.flatMap((k) => [
      { day: TODAY, keyId: k.keyId, endpoint: 'quotes', symbol: '', restCalls: k.usedToday, restRejected: k.rejectedToday, wsPushes: 0, wsSeconds: 0, wsPeak: 0 },
      ...(k.connections ? ['EURUSD', 'XAUUSD'].map((s) => ({ day: TODAY, keyId: k.keyId, endpoint: '', symbol: s, restCalls: 0, restRejected: 0, wsPushes: 642_000, wsSeconds: 0, wsPeak: 0 })) : []),
    ]);
    const filtered = q.symbol ? rows.filter((r) => r.symbol === q.symbol) : rows;
    const days = keys.map((k) => ({ day: TODAY, keyId: k.keyId, restCalls: k.usedToday, restRejected: k.rejectedToday, wsPushes: k.connections ? 1_284_000 : 0, wsSeconds: k.connections ? 33_120 : 0, wsPeak: k.connections }));
    return { from: q.from ?? TODAY, to: q.to ?? TODAY, rows: filtered, days };
  };
  return respond(data, { empty: () => ({ from: TODAY, to: TODAY, rows: [], days: [] }) });
}

export const quoteUsageExportUrl = (): string => 'data:text/csv;charset=utf-8,%EF%BB%BF%E6%97%A5%E6%9C%9F%2CKey%0A';

// ---- P10 ----
export function getQuoteLicenses(): Promise<QuoteLicenses> {
  const data = (): QuoteLicenses => {
    const tradable = new Set(state.licenses.filter((l) => l.trading.granted && l.trading.state !== 'expired').map((l) => l.symbol));
    const all = ['EURUSD', 'GBPUSD', 'USDJPY', 'XAUUSD', 'XAGUSD', 'US30', 'NAS100', 'BTCUSD', 'ETHUSD', 'AAPL'];
    return { gate: { ...state.gate }, enforced: state.enforced, today: TODAY, items: state.licenses, notTradable: all.filter((s) => !tradable.has(s)) };
  };
  return respond(data, { empty: () => ({ ...data(), items: [], notTradable: ['全部品种'] }) });
}

export function saveQuoteLicense(input: QuoteLicenseInput): Promise<void> {
  return mutate(() => {
    g.requirePerm('quote_license:edit');
    const r = g.requireReason(input.reason);
    for (const x of [input.trading, input.display, input.resale]) {
      if (x.from && x.until && x.until < x.from) throw new ApiError('errors.quoteServiceRejected', { detail: '到期日不能早于起始日' });
    }
    const row: QuoteLicenseRow = {
      feed: input.feed,
      symbol: input.symbol,
      trading: grant(input.trading.granted, input.trading.from, input.trading.until),
      display: grant(input.display.granted, input.display.from, input.display.until),
      resale: grant(input.resale.granted, input.resale.from, input.resale.until),
      note: input.note || null,
    };
    state.licenses = [...state.licenses.filter((l) => !(l.feed === row.feed && l.symbol === row.symbol)), row];
    g.audit('quote.license_save', `${row.feed} × ${row.symbol}`, true, r, {}, { trading: row.trading.granted, display: row.display.granted, resale: row.resale.granted });
  });
}

export function setQuoteGate(open: boolean, amosApproved: boolean, reason: string): Promise<void> {
  return mutate(() => {
    if (!g.isSuper()) throw new ApiError('errors.quoteGateSuperOnly');
    const r = g.requireReason(reason);
    if (open && !amosApproved) throw new ApiError('errors.quoteGateAmos');
    const before = state.gate.open;
    state.gate = { open, updatedBy: g.adminName(), reason: r, updatedAt: new Date().toISOString() };
    refresh();
    g.audit(open ? 'quote.gate_on' : 'quote.gate_off', 'gate', true, r, { open: before }, { open, amosApproved });
  });
}

/** 原型演示：把授权登记换成"假设谈下来后"（示例）的几条，含一条快到期、一条已到期 */
/** 测试用：切换授权检查开关（后端由 FX_QUOTE_LICENSE_ENFORCE 决定） */
export function setQuoteLicenseEnforcedForTest(on: boolean): void {
  state.enforced = on;
}

export function seedQuoteLicensesForDemo(): void {
  state.licenses = [
    { feed: 'alltick', symbol: 'XAUUSD', trading: grant(true, '2026-01-05', '2026-11-01'), display: grant(true, '2026-01-05', '2026-11-01'), resale: grant(true, '2026-01-05', '2026-11-01'), note: '（示例）' },
    { feed: 'alltick', symbol: 'EURUSD', trading: grant(true, '2025-11-01', '2026-10-13'), display: grant(true, '2025-11-01', '2026-10-13'), resale: grant(false, null, null), note: '（示例）' },
    { feed: 'alltick', symbol: 'GBPUSD', trading: grant(true, '2026-01-05', '2027-01-04'), display: grant(true, '2026-01-05', '2027-01-04'), resale: grant(true, '2026-01-05', '2027-01-04'), note: '（示例）' },
  ];
}

// ---- P11 ----
export function getQuoteSources(): Promise<QuoteSources> {
  const R = (symbol: string, primary: string, backup: string | null, current: string | null, state2: QuoteSources['rows'][number]['state'], sw = 0, dv = 0) => ({
    symbol,
    primary,
    backup,
    current,
    lastQuoteAt: state2 === 'down' ? '2026-10-14T07:12:44Z' : '2026-10-14T08:20:07Z',
    switches24h: sw,
    deviations24h: dv,
    lastSwitchAt: sw ? '2026-10-14T07:13:01Z' : null,
    state: state2,
  });
  const data = (): QuoteSources => ({
    mode: 'live',
    feeds: [
      { feed: 'alltick', configured: true, ok: true, detail: '正常' },
      { feed: 'itick', configured: true, ok: true, detail: '正常' },
      { feed: 'binance', configured: false, ok: true, detail: null },
    ],
    rows: [
      R('EURUSD', 'alltick', 'itick', 'alltick', 'ok'),
      R('GBPUSD', 'alltick', 'itick', 'alltick', 'ok', 1),
      R('XAUUSD', 'alltick', 'itick', 'alltick', 'deviation', 0, 5),
      R('NAS100', 'alltick', 'itick', 'itick', 'backup', 1),
      R('BTCUSD', 'alltick', 'itick', null, 'down'),
    ],
  });
  return respond(data, { empty: () => ({ mode: 'simulated', feeds: data().feeds.map((f) => ({ ...f, configured: false })), rows: [] }) });
}
