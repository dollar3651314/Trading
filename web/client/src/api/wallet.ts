/** 资金接口。real：/api/wallet/*（资金流水见 real/wallet.ts 的说明）；mock：原型模拟层（见 mode.ts）。 */
import * as mock from '../mocks/wallet';
import { IS_MOCK } from './mode';
import * as real from './real/wallet';
import type { MyRecovery, DepositAddress, DepositRecord, LedgerItem, LedgerQuery, Network, Page, PinInput, PinKeypad, PinStatus, WhitelistAddress, Withdrawal, WithdrawInfo, WithdrawRequest } from './types';

export const getDepositAddresses = (): Promise<DepositAddress[]> => (IS_MOCK ? mock.getDepositAddresses() : real.getDepositAddresses());
export const requestDepositAddress = (network: Network): Promise<DepositAddress> => (IS_MOCK ? mock.requestDepositAddress(network) : real.requestDepositAddress(network));
export const listDeposits = (): Promise<DepositRecord[]> => (IS_MOCK ? mock.listDeposits() : real.listDeposits());
export const listLedger = (q: LedgerQuery): Promise<Page<LedgerItem>> => (IS_MOCK ? mock.listLedger(q) : real.listLedger(q));
export const getWithdrawInfo = (): Promise<WithdrawInfo> => (IS_MOCK ? mock.getWithdrawInfo() : real.getWithdrawInfo());
export const listWhitelist = (): Promise<WhitelistAddress[]> => (IS_MOCK ? mock.listWhitelist() : real.listWhitelist());
export const addWhitelist = (f: { network: Network; address: string; label: string; pin?: PinInput }): Promise<WhitelistAddress> => (IS_MOCK ? mock.addWhitelist(f) : real.addWhitelist(f));
export const removeWhitelist = (id: string): Promise<void> => (IS_MOCK ? mock.removeWhitelist(id) : real.removeWhitelist(id));
export const listWithdrawals = (page: number, pageSize = 20): Promise<Page<Withdrawal>> => (IS_MOCK ? mock.listWithdrawals(page, pageSize) : real.listWithdrawals(page, pageSize));
export const submitWithdraw = (req: WithdrawRequest): Promise<Withdrawal> => (IS_MOCK ? mock.submitWithdraw(req) : real.submitWithdraw(req));
export const cancelWithdraw = (id: string): Promise<Withdrawal> => (IS_MOCK ? mock.cancelWithdraw(id) : real.cancelWithdraw(id));

// 提现密码（第 4 批）：原型里用一个内存里的假实现（mocks/wallet.ts），方便看界面
export const getPinStatus = (): Promise<PinStatus> => (IS_MOCK ? mock.getPinStatus() : real.getPinStatus());
export const getPinKeypad = (): Promise<PinKeypad> => (IS_MOCK ? mock.getPinKeypad() : real.getPinKeypad());
export const setPin = (pin: PinInput, confirm: PinInput): Promise<void> => (IS_MOCK ? mock.setPin(pin, confirm) : real.setPin(pin, confirm));
export const changePin = (old: PinInput, pin: PinInput, confirm: PinInput): Promise<void> => (IS_MOCK ? mock.changePin(old, pin, confirm) : real.changePin(old, pin, confirm));
export const sendPinResetCode = (): Promise<{ cooldownSeconds: number }> => (IS_MOCK ? mock.sendPinResetCode() : real.sendPinResetCode());
export const resetPin = (code: string, pin: PinInput, confirm: PinInput): Promise<void> => (IS_MOCK ? mock.resetPin(code, pin, confirm) : real.resetPin(code, pin, confirm));

// 找回申请（第 4 批 4b）
export const listMyRecoveries = (): Promise<MyRecovery[]> => (IS_MOCK ? mock.listMyRecoveries() : real.listMyRecoveries());
export const objectRecovery = (id: number, pin: PinInput): Promise<void> => (IS_MOCK ? mock.objectRecovery(id, pin) : real.objectRecovery(id, pin));
export const revokeRecovery = (id: number): Promise<{ message: string }> => (IS_MOCK ? mock.revokeRecovery(id) : real.revokeRecovery(id));
export const requestPinReset = (): Promise<{ id: number }> => (IS_MOCK ? mock.requestPinReset() : real.requestPinReset());
