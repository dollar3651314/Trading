/**
 * 钱包的真实接口：`/api/wallet/*`（`wallet/web/WalletController.java`、`wallet/withdraw/WithdrawController.java`）。
 * 资金流水：后端还没有客户端接口（S17 汇报的缺口），这里按约定的 `GET /api/trading/ledger` 调用，接口补上后即可使用。
 */
import { del, get, post } from '../http';
import type { DepositAddress, DepositRecord, LedgerItem, LedgerQuery, Network, Page, PinInput, PinKeypad, PinStatus, WhitelistAddress, Withdrawal, WithdrawInfo, WithdrawRequest } from '../types';

/** 开着的充值网络和已经生成的地址（不访问钱包提供方） */
export const getDepositAddresses = (): Promise<DepositAddress[]> => get<DepositAddress[]>('/api/wallet/networks');

/** 要这条网络的地址：没有就由后端向钱包提供方要（通常 5 秒内）；提供方不通时 WALLET_UNAVAILABLE */
export const requestDepositAddress = (network: Network): Promise<DepositAddress> => post<DepositAddress>(`/api/wallet/addresses/${encodeURIComponent(network)}`);

/** 最近 50 条充值记录 */
export const listDeposits = (): Promise<DepositRecord[]> => get<DepositRecord[]>('/api/wallet/deposits');

export const listLedger = (q: LedgerQuery): Promise<Page<LedgerItem>> =>
  get<Page<LedgerItem>>('/api/trading/ledger', { query: { type: q.type === 'all' ? undefined : q.type, range: q.range, page: q.page, pageSize: q.pageSize } });

export const getWithdrawInfo = (): Promise<WithdrawInfo> => get<WithdrawInfo>('/api/wallet/withdraw-info');

export const listWhitelist = (): Promise<WhitelistAddress[]> => get<WhitelistAddress[]>('/api/wallet/whitelist');

export const addWhitelist = (f: { network: Network; address: string; label: string; pin?: PinInput }): Promise<WhitelistAddress> =>
  post<WhitelistAddress>('/api/wallet/whitelist', { network: f.network, address: f.address.trim(), label: f.label.trim(), pin: f.pin });

export async function removeWhitelist(id: string): Promise<void> {
  await del<void>(`/api/wallet/whitelist/${encodeURIComponent(id)}`);
}

export const listWithdrawals = (page: number, pageSize: number): Promise<Page<Withdrawal>> => get<Page<Withdrawal>>('/api/wallet/withdrawals', { query: { page, pageSize } });

/**
 * 提现表单的字段名：后端 `VALIDATION_FAILED` 的 fields（`WithdrawService.submit`：whitelistId、amount）→ 页面表单字段。
 * 目前两边同名，写在这里是为了把对应关系固定下来：后端改字段名时只改这里，页面照样把错误显示在对应的输入框下面。
 */
export const WITHDRAW_FIELDS: Record<string, 'whitelistId' | 'amount'> = { whitelistId: 'whitelistId', amount: 'amount' };

/** 提现的 INSUFFICIENT_BALANCE 是"超过可提金额" */
export const submitWithdraw = (req: WithdrawRequest): Promise<Withdrawal> =>
  post<Withdrawal>('/api/wallet/withdrawals', { whitelistId: req.whitelistId, amount: req.amount.trim(), pin: req.pin }, { codes: { INSUFFICIENT_BALANCE: 'withdrawInsufficient' }, fieldNames: WITHDRAW_FIELDS });

export const cancelWithdraw = (id: string): Promise<Withdrawal> => post<Withdrawal>(`/api/wallet/withdrawals/${encodeURIComponent(id)}/cancel`);

// ---------- 提现密码（TG 机器人第 4 批，account/auth/WithdrawPinController.java） ----------
export const getPinStatus = (): Promise<PinStatus> => get<PinStatus>('/api/me/withdraw-pin');
/** 要一个随机键盘（只能用一次、5 分钟） */
export const getPinKeypad = (): Promise<PinKeypad> => post<PinKeypad>('/api/me/withdraw-pin/keypad');
export async function setPin(pin: PinInput, confirm: PinInput): Promise<void> {
  await post<void>('/api/me/withdraw-pin', { pin, confirm });
}
export async function changePin(old: PinInput, pin: PinInput, confirm: PinInput): Promise<void> {
  await post<void>('/api/me/withdraw-pin/change', { old, pin, confirm });
}
export const sendPinResetCode = (): Promise<{ cooldownSeconds: number }> => post<{ cooldownSeconds: number }>('/api/me/withdraw-pin/forgot/code');
export async function resetPin(code: string, pin: PinInput, confirm: PinInput): Promise<void> {
  await post<void>('/api/me/withdraw-pin/forgot', { code: code.trim(), pin, confirm });
}
