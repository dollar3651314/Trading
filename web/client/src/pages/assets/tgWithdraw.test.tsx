/**
 * 第 4 批"资金安全（网页侧）"的前端测试：随机键盘只交位置、TG 客户的提现页（额度、提现密码、被挡住的情况）、加白名单要输提现密码、
 * 提现密码页（设置成功后提示会话已作废）、TG 额度校验（正好用满可以）。接口用原型模拟数据，只替换要观察的几个。
 */
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import type { WithdrawInfo, WithdrawTgInfo } from '../../api/types';
import { resetDb } from '../../mocks/db';
import { renderPage } from '../../test/renderApp';
import { validateWithdraw } from '../../lib/validators';
import WithdrawPin from '../me/WithdrawPin';
import Whitelist from './Whitelist';
import Withdraw from './Withdraw';
import TgHint from '../tg/TgHint';
import { Route, Routes } from 'react-router-dom';

const LAYOUT = ['7', '3', '0', '9', '1', '5', '8', '2', '6', '4'];
const submitWithdraw = vi.fn();
const addWhitelist = vi.fn();
const setPin = vi.fn();
let tg: WithdrawTgInfo | undefined;

vi.mock('../../api/wallet', async (orig) => {
  const o = await orig<typeof import('../../api/wallet')>();
  return {
    ...o,
    // TG 客户今天已用 100（网页和机器人合并），额度 500
    getWithdrawInfo: async () => ({ ...(await o.getWithdrawInfo()), ...(tg ? { tg, usedToday: '100.00' } : {}) }),
    getPinKeypad: async () => ({ keypadId: 'kp-' + Math.random().toString(36).slice(2), digits: LAYOUT }),
    getPinStatus: async () => ({ set: false, lockedUntil: null, withdrawFrozen: false, hasEmail: true }),
    submitWithdraw: (...a: unknown[]) => submitWithdraw(...a),
    addWhitelist: (...a: unknown[]) => addWhitelist(...a),
    setPin: (...a: unknown[]) => setPin(...a),
  };
});

const baseTg = (p: Partial<WithdrawTgInfo> = {}): WithdrawTgInfo => ({ dailyLimit: '500.00', hasEmail: false, pinSet: true, pinLockedUntil: null, withdrawFrozen: false, holdUntil: null, enabled: true, ...p });

afterEach(() => {
  submitWithdraw.mockReset();
  addWhitelist.mockReset();
  setPin.mockReset();
  tg = undefined;
  resetDb();
});

/** 按数字找键盘上的键（位置每次不同，测试按显示的数字点） */
function pressDigits(scope: HTMLElement, digits: string) {
  for (const d of digits) fireEvent.click(within(scope).getByRole('button', { name: d }));
}

describe('TG 客户的提现页', () => {
  it('显示 TG 额度（没邮箱 500）、确认框里要输满 6 位提现密码，提交的是键盘位置不是数字', async () => {
    tg = baseTg();
    submitWithdraw.mockResolvedValue({ id: 'W1' });
    renderPage(<Withdraw />, '/assets/withdraw');
    expect(await screen.findByTestId('tg-box')).toHaveTextContent('每天最多提 500 USDT（网页和机器人合并计算）；绑定邮箱后提高到 2,000 USDT');
    fireEvent.change(await screen.findByLabelText('提现金额'), { target: { value: '100' } });
    fireEvent.click(screen.getByRole('button', { name: '提交提现' }));
    const dialog = await screen.findByRole('dialog');
    const ok = within(dialog).getByRole('button', { name: '确认提交' });
    expect(ok).toBeDisabled();
    await within(dialog).findByRole('button', { name: '7' });
    pressDigits(dialog, '12345');
    expect(ok).toBeDisabled();
    pressDigits(dialog, '6');
    expect(ok).toBeEnabled();
    fireEvent.click(ok);
    await waitFor(() => expect(submitWithdraw).toHaveBeenCalled());
    const req = submitWithdraw.mock.calls[0][0] as { pin: { keypadId: string; positions: number[] } };
    // 1→第 5 个键(4)，2→7，3→1，4→9，5→5，6→8
    expect(req.pin.positions).toEqual([4, 7, 1, 9, 5, 8]);
    expect(JSON.stringify(req)).not.toContain('123456');
  });

  it('删除一位、清空都只改本地位置', async () => {
    tg = baseTg();
    renderPage(<Withdraw />, '/assets/withdraw');
    fireEvent.change(await screen.findByLabelText('提现金额'), { target: { value: '100' } });
    fireEvent.click(screen.getByRole('button', { name: '提交提现' }));
    const dialog = await screen.findByRole('dialog');
    await within(dialog).findByRole('button', { name: '7' });
    pressDigits(dialog, '123');
    expect(within(dialog).getByTestId('wd-pin-dots').querySelectorAll('i.on')).toHaveLength(3);
    fireEvent.click(within(dialog).getByRole('button', { name: '删除一位' }));
    expect(within(dialog).getByTestId('wd-pin-dots').querySelectorAll('i.on')).toHaveLength(2);
    fireEvent.click(within(dialog).getByRole('button', { name: '清空' }));
    expect(within(dialog).getByTestId('wd-pin-dots').querySelectorAll('i.on')).toHaveLength(0);
  });

  it.each([
    ['没设提现密码', baseTg({ pinSet: false }), '还没有设置提现密码'],
    ['总开关关着', baseTg({ enabled: false }), '提现暂未开放'],
    ['提现已冻结', baseTg({ withdrawFrozen: true }), '提现已冻结'],
    ['安全等待期', baseTg({ holdUntil: new Date(Date.now() + 3_600_000).toISOString() }), '以后才能提现'],
    ['提现密码锁定', baseTg({ pinLockedUntil: new Date(Date.now() + 3_600_000).toISOString() }), '已锁到'],
  ])('%s时提交按钮不可点、写明原因', async (_n, g, text) => {
    tg = g;
    renderPage(<Withdraw />, '/assets/withdraw');
    expect(await screen.findByTestId('tg-box')).toHaveTextContent(text);
    expect(screen.getByRole('button', { name: '提交提现' })).toBeDisabled();
  });

  it('不是 TG 客户：没有 TG 规则框、确认框里没有键盘', async () => {
    submitWithdraw.mockResolvedValue({ id: 'W1' });
    renderPage(<Withdraw />, '/assets/withdraw');
    fireEvent.change(await screen.findByLabelText('提现金额'), { target: { value: '100' } });
    expect(screen.queryByTestId('tg-box')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '提交提现' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).queryByText('提现密码')).not.toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: '确认提交' }));
    await waitFor(() => expect(submitWithdraw).toHaveBeenCalledWith({ whitelistId: expect.any(String), amount: '100' }));
  });
});

describe('TG 客户加白名单', () => {
  it('要输提现密码，提交带键盘位置；规则写明至少 24 小时', async () => {
    tg = baseTg();
    addWhitelist.mockResolvedValue({});
    renderPage(<Whitelist />, '/assets/whitelist');
    await waitFor(() => expect(screen.getByRole('button', { name: '添加地址' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: '添加地址' }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('至少 24 小时后才生效');
    fireEvent.change(within(dialog).getByLabelText(/地址/), { target: { value: 'TLa2f6VPqDgRE67v1736s7bJ8Ray5wYjU7' } });
    fireEvent.change(within(dialog).getByLabelText(/备注/), { target: { value: '我的钱包' } });
    await within(dialog).findByRole('button', { name: '7' });
    pressDigits(dialog, '000000');
    fireEvent.click(within(dialog).getByRole('button', { name: '确认添加' }));
    await waitFor(() => expect(addWhitelist).toHaveBeenCalled());
    expect((addWhitelist.mock.calls[0][0] as { pin: { positions: number[] } }).pin.positions).toEqual([2, 2, 2, 2, 2, 2]);
  });

  it('没设提现密码时不能加，并给出去设置的链接', async () => {
    tg = baseTg({ pinSet: false });
    renderPage(<Whitelist />, '/assets/whitelist');
    expect(await screen.findByRole('link', { name: '去设置' })).toHaveAttribute('href', '/me/withdraw-pin');
    expect(screen.getByRole('button', { name: '添加地址' })).toBeDisabled();
  });
});

describe('提现密码页', () => {
  it('第一次设置：两次输入一致后提交，成功后去提示页：网页已全部退出，回机器人重新授权、打开交易', async () => {
    setPin.mockResolvedValue(undefined);
    renderPage(
      <Routes>
        <Route path="/me/withdraw-pin" element={<WithdrawPin />} />
        <Route path="/tg" element={<TgHint />} />
      </Routes>,
      '/me/withdraw-pin',
    );
    const groups = await screen.findAllByRole('group');
    expect(groups).toHaveLength(2);
    for (const g of groups) {
      await within(g).findByRole('button', { name: '7' });
      pressDigits(g, '258014');
    }
    fireEvent.click(screen.getByRole('button', { name: '设置提现密码' }));
    await waitFor(() => expect(setPin).toHaveBeenCalled());
    expect(await screen.findByTestId('tg-reason')).toHaveTextContent('这个账户的网页已全部退出');
    expect(screen.getByTestId('tg-reason')).toHaveTextContent('点「重新授权」，再点「打开交易」');
  });
});

describe('TG 额度校验', () => {
  const info = { kycStatus: 'approved', available: '10000.00', usedToday: '300.00', minAmount: '10.00', maxPerTx: '5000.00', dailyLimit: '30000.00', largeAmount: '3000.00', largeDelayHours: 6, coolingHours: 2, fees: { TRC20: '1.00', ERC20: '5.00', BSC: '1.00' }, maxWhitelist: 10, tg: baseTg() } as WithdrawInfo;
  const wl = [{ id: 'A1', network: 'TRC20', address: 'T', label: 'x', createdAt: '2026-01-01T00:00:00Z', activeAt: '2026-01-02T00:00:00Z' }] as never;
  it('正好用满（300 + 200 = 500）可以，多 0.01 不行', () => {
    expect(validateWithdraw({ whitelistId: 'A1', amount: '200' }, info, wl).amount).toBeUndefined();
    expect(validateWithdraw({ whitelistId: 'A1', amount: '200.01' }, info, wl).amount).toEqual(['validation.withdrawTgDaily']);
  });
});
