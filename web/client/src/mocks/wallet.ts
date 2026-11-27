/**
 * 模拟钱包：充值地址、资金流水、白名单、提现。
 * 规则（需求 4.4，第 1 批 TD-15 A）：最小 10、单笔 ≤5,000、当日累计 <30,000；2 小时冷静期；≥3,000 审核后延迟 6 小时；
 * 白名单最多 10 个，新地址 24 小时后生效。
 */
import { ApiError } from '../api/errors';
import type {
  PinInput,
  PinKeypad,
  PinStatus,
  MyRecovery,
  FreezeCaptcha,
  FreezeRequest,
  MyFreeze,
  DepositAddress,
  DepositRecord,
  LedgerItem,
  LedgerQuery,
  Network,
  Page,
  WhitelistAddress,
  WithdrawInfo,
  Withdrawal,
  WithdrawRequest,
  WithdrawStatus,
} from '../api/types';
import { add, cmp, dec, sub, sum, toFixed, ZERO } from '../lib/decimal';
import { formatAmount } from '../lib/format';
import { hasErrors, validateWhitelist, validateWithdraw } from '../lib/validators';
import { emit } from './bus';
import { db } from './db';
import { mutate, respond } from './delay';
import { nextId, snowflakeAt, snowflakeId } from './ids';
import { availableNow } from './trading';
import { pushNotification } from './user';

const CONFIRMATIONS: Record<Network, { n: number; eta: number }> = {
  ERC20: { n: 12, eta: 3 },
  TRC20: { n: 20, eta: 1 },
  BSC: { n: 15, eta: 1 },
};

/** 最小充值 1 USDT（负责人 确认，充值方案 D4） */
const MIN_DEPOSIT = '1';

/** 第 1 批：第一次选某条网络时才生成地址（AC-W1）。原型里 ERC20、TRC20 已经生成过，BSC 没有（点了才生成，db.depositGenerated） */
export function getDepositAddresses(): Promise<DepositAddress[]> {
  return respond(() =>
    (['ERC20', 'TRC20', 'BSC'] as Network[]).map((network) => ({ network, address: db.depositGenerated.includes(network) ? db.deposit[network] : null, minDeposit: MIN_DEPOSIT })),
  );
}

export function requestDepositAddress(network: Network): Promise<DepositAddress> {
  return mutate(() => {
    if (!db.depositGenerated.includes(network)) db.depositGenerated.push(network);
    return { network, address: db.deposit[network], minDeposit: MIN_DEPOSIT };
  });
}

const MOCK_TX = (i: number): string => `0x${(0x7f3a00 + i).toString(16)}${'0'.repeat(52)}e21b${i}`;

/** 充值记录：资金流水里的充值都算已到账，另加一条待确认、一条低于最小充值金额，演示各种状态 */
export function listDeposits(): Promise<DepositRecord[]> {
  return respond(
    () => {
      const credited: DepositRecord[] = db.ledger
        .filter((l) => l.type === 'deposit')
        .map((l, i) => {
          const network = (['ERC20', 'TRC20', 'BSC'].includes(l.ref) ? l.ref : 'TRC20') as Network;
          const n = CONFIRMATIONS[network].n;
          return { id: snowflakeAt(new Date(l.time).getTime(), 300 + i), network, txHash: MOCK_TX(i), amount: l.amount, status: 'credited', confirmations: n, requiredConfirmations: n, time: l.time };
        });
      const now = Date.now();
      const extra: DepositRecord[] = [
        { id: snowflakeAt(now - 60_000, 400), network: 'TRC20', txHash: MOCK_TX(90), amount: '500', status: 'pending', confirmations: 7, requiredConfirmations: CONFIRMATIONS.TRC20.n, time: new Date(now - 60_000).toISOString() },
        { id: snowflakeAt(now - 86_400_000, 401), network: 'BSC', txHash: MOCK_TX(91), amount: '0.5', status: 'belowMin', confirmations: CONFIRMATIONS.BSC.n, requiredConfirmations: CONFIRMATIONS.BSC.n, time: new Date(now - 86_400_000).toISOString() },
      ];
      return [...extra, ...credited].sort((a, b) => b.time.localeCompare(a.time)).slice(0, 50);
    },
    () => [],
  );
}

const RANGE_DAYS: Record<LedgerQuery['range'], number> = { '7d': 7, '30d': 30, '90d': 90, all: 100000 };

export function listLedger(q: LedgerQuery): Promise<Page<LedgerItem>> {
  return respond(
    () => {
      const since = Date.now() - RANGE_DAYS[q.range] * 86_400_000;
      const rows = db.ledger
        .filter((l) => (q.type === 'all' || l.type === q.type) && new Date(l.time).getTime() >= since)
        .sort((a, b) => b.time.localeCompare(a.time));
      return { items: rows.slice((q.page - 1) * q.pageSize, q.page * q.pageSize), total: rows.length, page: q.page, pageSize: q.pageSize };
    },
    () => ({ items: [], total: 0, page: q.page, pageSize: q.pageSize }),
  );
}

const COUNTS_TOWARDS_DAILY: WithdrawStatus[] = ['cooling', 'reviewing', 'approved', 'delayed', 'broadcasting', 'completed'];

function usedToday(): string {
  const start = new Date();
  start.setHours(0, 0, 0, 0);
  const today = db.withdrawals.filter((w) => COUNTS_TOWARDS_DAILY.includes(w.status) && new Date(w.createdAt).getTime() >= start.getTime());
  return toFixed(sum(today.map((w) => dec(w.amount))), 2);
}

function withdrawable(): string {
  const a = availableNow();
  return toFixed(cmp(a, ZERO) < 0 ? ZERO : a, 2);
}

function info(): WithdrawInfo {
  return {
    kycStatus: db.kyc.status,
    available: withdrawable(),
    usedToday: usedToday(),
    minAmount: '10',
    maxPerTx: '5000',
    dailyLimit: '30000',
    largeAmount: '3000',
    largeDelayHours: 6,
    coolingHours: 2,
    fees: { ERC20: '5.00', TRC20: '1.00', BSC: '0.50' },
    maxWhitelist: 10,
  };
}

export function getWithdrawInfo(): Promise<WithdrawInfo> {
  return respond(info, () => ({ ...info(), kycStatus: 'none', available: '0.00', usedToday: '0.00' }));
}

export function listWhitelist(): Promise<WhitelistAddress[]> {
  return respond(() => db.whitelist.map((w) => ({ ...w })), () => []);
}

export function addWhitelist(f: { network: Network; address: string; label: string; pin?: PinInput }): Promise<WhitelistAddress> {
  return mutate(() => {
    const errs = validateWhitelist(f, db.whitelist, 10);
    if (errs.form) throw new ApiError('whitelistFull');
    if (errs.address?.includes('validation.addressDuplicate')) throw new ApiError('whitelistDuplicate');
    const now = Date.now();
    const w: WhitelistAddress = {
      id: nextId('A'),
      network: f.network,
      address: f.address.trim(),
      label: f.label.trim(),
      createdAt: new Date(now).toISOString(),
      activeAt: new Date(now + 24 * 3_600_000).toISOString(),
    };
    db.whitelist.push(w);
    return { ...w };
  });
}

export function removeWhitelist(id: string): Promise<void> {
  return mutate(() => {
    db.whitelist = db.whitelist.filter((w) => w.id !== id);
  });
}

export function listWithdrawals(page: number, pageSize: number): Promise<Page<Withdrawal>> {
  return respond(
    () => {
      const sorted = [...db.withdrawals].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
      return { items: sorted.slice((page - 1) * pageSize, page * pageSize).map((w) => ({ ...w })), total: sorted.length, page, pageSize };
    },
    () => ({ items: [], total: 0, page, pageSize }),
  );
}

export function submitWithdraw(req: WithdrawRequest): Promise<Withdrawal> {
  return mutate(() => {
    const i = info();
    if (i.kycStatus !== 'approved') throw new ApiError('kycRequired');
    const errs = validateWithdraw(req, i, db.whitelist);
    if (hasErrors(errs)) throw new ApiError('withdrawInvalid', errs as Record<string, string[]>);
    const addr = db.whitelist.find((w) => w.id === req.whitelistId)!;
    const now = Date.now();
    const w: Withdrawal = {
      id: snowflakeId(),
      amount: toFixed(dec(req.amount), 2),
      fee: i.fees[addr.network],
      network: addr.network,
      address: addr.address,
      status: 'cooling',
      createdAt: new Date(now).toISOString(),
      coolingEndsAt: new Date(now + i.coolingHours * 3_600_000).toISOString(),
    };
    db.withdrawals.unshift(w);
    db.account.balance = toFixed(sub(dec(db.account.balance), dec(w.amount)), 2);
    db.account.withdrawFrozen = toFixed(add(dec(db.account.withdrawFrozen), dec(w.amount)), 2);
    db.ledger.unshift({ id: nextId('L'), type: 'withdraw', amount: toFixed(dec(`-${w.amount}`), 2), ref: w.network, time: w.createdAt });
    pushNotification(
      'withdraw',
      ['提现已进入冷静期', 'Withdrawal in cooling-off period'],
      [`${formatAmount(w.amount)} USDT 提现已提交，${i.coolingHours} 小时内可撤销。`, `Your ${formatAmount(w.amount)} USDT withdrawal was submitted and can be cancelled within ${i.coolingHours} hours.`],
    );
    emit({ type: 'account' });
    return { ...w };
  });
}

export function cancelWithdraw(id: string): Promise<Withdrawal> {
  return mutate(() => {
    const w = db.withdrawals.find((x) => x.id === id);
    if (!w) throw new ApiError('notFound');
    if (w.status !== 'cooling' || new Date(w.coolingEndsAt).getTime() < Date.now()) throw new ApiError('withdrawNotCancellable');
    w.status = 'cancelled';
    // 冷静期内撤销：冻结金额立即释放（AC-18）
    db.account.balance = toFixed(add(dec(db.account.balance), dec(w.amount)), 2);
    db.account.withdrawFrozen = toFixed(sub(dec(db.account.withdrawFrozen), dec(w.amount)), 2);
    db.ledger.unshift({ id: nextId('L'), type: 'withdrawRefund', amount: w.amount, ref: w.network, time: new Date().toISOString() });
    emit({ type: 'account' });
    emit({ type: 'withdraw', status: 'cancelled', amount: w.amount });
    return { ...w };
  });
}

// ---------- 提现密码（原型：内存里的假实现；真实的规则在后端 WithdrawPins） ----------
const pinState: { pin: string | null; failed: number; keypads: Map<string, number[]> } = { pin: null, failed: 0, keypads: new Map() };

export function getPinStatus(): Promise<PinStatus> {
  return respond(() => ({ set: pinState.pin !== null, lockedUntil: null, withdrawFrozen: false, hasEmail: true }));
}

export function getPinKeypad(): Promise<PinKeypad> {
  return mutate(() => {
    const d = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9];
    for (let i = d.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [d[i], d[j]] = [d[j], d[i]];
    }
    const id = nextId('K');
    pinState.keypads.set(id, d);
    return { keypadId: id, digits: d };
  });
}

function decode(p: PinInput): string {
  const d = pinState.keypads.get(p.keypadId);
  pinState.keypads.delete(p.keypadId);
  if (!d) throw new ApiError('pinKeypadExpired');
  return p.positions.map((i) => d[i]).join('');
}

export function setPin(pin: PinInput, confirm: PinInput): Promise<void> {
  return mutate(() => {
    const a = decode(pin);
    if (a !== decode(confirm)) throw new ApiError('validationFailed', { confirm: ['validation.pinMismatch'] });
    pinState.pin = a;
  });
}

export function changePin(old: PinInput, pin: PinInput, confirm: PinInput): Promise<void> {
  return mutate(() => {
    if (decode(old) !== pinState.pin) throw new ApiError('pinWrong');
    const a = decode(pin);
    if (a !== decode(confirm)) throw new ApiError('validationFailed', { confirm: ['validation.pinMismatch'] });
    pinState.pin = a;
  });
}

export function sendPinResetCode(): Promise<{ cooldownSeconds: number }> {
  return mutate(() => ({ cooldownSeconds: 60 }));
}

export function resetPin(_code: string, pin: PinInput, confirm: PinInput): Promise<void> {
  return mutate(() => {
    const a = decode(pin);
    if (a !== decode(confirm)) throw new ApiError('validationFailed', { confirm: ['validation.pinMismatch'] });
    pinState.pin = a;
  });
}

// ---------- 找回申请（第 4 批 4b，原型：只在内存里） ----------
const recoveries: MyRecovery[] = [];

export function listMyRecoveries(): Promise<MyRecovery[]> {
  return respond(() => recoveries.map((r) => ({ ...r })));
}

export function objectRecovery(id: number, pin: PinInput): Promise<void> {
  return mutate(() => {
    const r = recoveries.find((x) => x.id === id);
    if (!r) throw new ApiError('notFound');
    if (decode(pin) !== pinState.pin) {
      r.disputeFrozen = true;
      throw new ApiError('pinWrong');
    }
    r.disputePinOk = true;
    r.disputeFrozen = false;
  });
}

export function revokeRecovery(id: number): Promise<{ message: string }> {
  return mutate(() => {
    const r = recoveries.find((x) => x.id === id);
    if (!r) throw new ApiError('notFound');
    r.status = 'cancelled';
    return { message: '已撤销这份申请。' };
  });
}

export function requestPinReset(): Promise<{ id: number }> {
  return mutate(() => {
    if (recoveries.some((r) => ['objection', 'proof', 'approved'].includes(r.status))) throw new ApiError('recoveryOpen');
    const id = recoveries.length + 1;
    recoveries.unshift({ id, kind: 'pin_reset', status: 'proof', disputed: false, disputePinOk: false, disputeFrozen: false, objectionEndsAt: null, executeAt: null, revokeRequested: false, createdAt: new Date().toISOString() });
    return { id };
  });
}

// ---------- 申请冻结提现、自助解冻（第 4 批 4c，原型：内存里；验证码答案固定 AC34） ----------
const freezes: MyFreeze[] = [];
/** 1×1 的透明 PNG（原型不画字，答案固定 AC34） */
const PNG_1X1 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=';

export function getFreezeCaptcha(): Promise<FreezeCaptcha> {
  return respond(() => ({ id: 'proto', pngBase64: PNG_1X1, hard: false }));
}

export function requestWithdrawFreeze(req: FreezeRequest): Promise<{ message: string }> {
  return mutate(() => {
    if (req.captcha.trim().toUpperCase() !== 'AC34') throw new ApiError('captchaInvalid');
    return { message: '已受理；信息正确的话会冻结提现并通知账户持有人。' };
  });
}

export function listMyFreezes(): Promise<MyFreeze[]> {
  return respond(() => freezes.map((f) => ({ ...f })));
}

export function requestSelfUnfreeze(pin: PinInput): Promise<{ releaseAt: string }> {
  return mutate(() => {
    const f = freezes.find((x) => x.source === 'bot_self');
    if (!f) throw new ApiError('notSelfFrozen');
    if (decode(pin) !== pinState.pin) throw new ApiError('pinWrong');
    f.releaseAt ??= new Date(Date.now() + 24 * 3_600_000).toISOString();
    return { releaseAt: f.releaseAt };
  });
}

/** 原型面板 / 测试：设置自己的冻结 */
export function protoSetFreezes(list: MyFreeze[]): void {
  freezes.splice(0, freezes.length, ...list);
}
