/**
 * 第 4 批 4b 前端：找回申请页（点了"不是我"以后用随机键盘输提现密码、撤销）、没邮箱忘记提现密码申请人工重设、提示页"找回 Telegram 账户"给出机器人深链。
 */
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import type { MyRecovery } from '../../api/types';
import { renderPage } from '../../test/renderApp';
import TgEntry from '../tg/TgEntry';
import Recovery from './Recovery';
import WithdrawPin from './WithdrawPin';

const LAYOUT = ['7', '3', '0', '9', '1', '5', '8', '2', '6', '4'];
const objectRecovery = vi.fn();
const revokeRecovery = vi.fn();
const requestPinReset = vi.fn();
let list: MyRecovery[] = [];
let hasEmail = false;

vi.mock('../../api/wallet', async (orig) => ({
  ...(await orig<typeof import('../../api/wallet')>()),
  listMyRecoveries: async () => list,
  getPinKeypad: async () => ({ keypadId: 'kp-' + Math.random().toString(36).slice(2), digits: LAYOUT }),
  getPinStatus: async () => ({ set: true, lockedUntil: null, withdrawFrozen: false, hasEmail }),
  objectRecovery: (...a: unknown[]) => objectRecovery(...a),
  revokeRecovery: (...a: unknown[]) => revokeRecovery(...a),
  requestPinReset: (...a: unknown[]) => requestPinReset(...a),
}));
vi.mock('../../api/auth', async (orig) => ({
  ...(await orig<typeof import('../../api/auth')>()),
  getAuthOptions: async () => ({ telegramOnly: true, botUsername: 'TradingAssistTestBot', loginMode: 'login-url' }),
}));

const rec = (p: Partial<MyRecovery>): MyRecovery => ({
  id: 7,
  kind: 'rebind',
  status: 'proof',
  disputed: true,
  disputePinOk: false,
  disputeFrozen: false,
  objectionEndsAt: null,
  executeAt: null,
  revokeRequested: false,
  createdAt: '2026-11-27T08:00:00Z',
  ...p,
});

afterEach(() => {
  objectRecovery.mockReset();
  revokeRecovery.mockReset();
  requestPinReset.mockReset();
  list = [];
  hasEmail = false;
});

describe('找回申请页', () => {
  it('点了不是我还没输密码的：用随机键盘输提现密码，交的是键位', async () => {
    list = [rec({})];
    objectRecovery.mockResolvedValue(undefined);
    renderPage(<Recovery />, '/me/recovery?id=7');
    const item = await screen.findByTestId('recovery-7');
    expect(item).toHaveTextContent('人工核对中');
    expect(item).toHaveTextContent('你点过「不是我」');
    await within(item).findByRole('button', { name: '7' });
    const ok = within(item).getByRole('button', { name: '确认是我本人' });
    expect(ok).toBeDisabled();
    for (const d of '135790') fireEvent.click(within(item).getByRole('button', { name: d }));
    fireEvent.click(ok);
    await waitFor(() => expect(objectRecovery).toHaveBeenCalled());
    expect(objectRecovery.mock.calls[0][0]).toBe(7);
    expect((objectRecovery.mock.calls[0][1] as { positions: number[] }).positions).toEqual([4, 1, 5, 0, 3, 2]);
    expect(await within(item).findByText(/已确认：不冻结提现/)).toBeInTheDocument();
  });

  it('撤销：显示服务器回的说明（争议路径转审核员）；已提出撤销的不再显示按钮', async () => {
    list = [rec({ status: 'approved', executeAt: '2026-11-28T08:00:00Z', disputePinOk: true }), rec({ id: 8, status: 'approved', revokeRequested: true, disputePinOk: true })];
    revokeRecovery.mockResolvedValue({ message: '已收到撤销请求。这份申请有争议，按规则撤销要由审核员核对后决定；决定之前不会执行换绑。' });
    renderPage(<Recovery />, '/me/recovery');
    const item = await screen.findByTestId('recovery-7');
    expect(item).toHaveTextContent('已批准，等待执行');
    expect(within(screen.getByTestId('recovery-8')).queryByRole('button', { name: '撤销这份申请' })).not.toBeInTheDocument();
    expect(screen.getByTestId('recovery-8')).toHaveTextContent('已提出撤销，等审核员核对后决定');
    fireEvent.click(within(item).getByRole('button', { name: '撤销这份申请' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: '撤销这份申请' }));
    expect(await screen.findByText(/按规则撤销要由审核员核对后决定/)).toBeInTheDocument();
    expect(revokeRecovery).toHaveBeenCalledWith(7);
  });

  it('没有申请时显示空', async () => {
    renderPage(<Recovery />, '/me/recovery');
    expect(await screen.findByText('没有找回申请。')).toBeInTheDocument();
  });
});

describe('没邮箱忘记提现密码', () => {
  it('申请人工重设，提交后给出找回申请的入口', async () => {
    requestPinReset.mockResolvedValue({ id: 12 });
    renderPage(<WithdrawPin />, '/me/withdraw-pin');
    fireEvent.click(await screen.findByRole('tab', { name: '忘记了' }));
    expect(await screen.findByText(/没有绑邮箱：忘记提现密码要人工重设/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '申请人工重设' }));
    expect(await screen.findByText(/已提交（申请 #12）/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '查看找回申请' })).toHaveAttribute('href', '/me/recovery');
  });

  it('有邮箱的还是收验证码重设', async () => {
    hasEmail = true;
    renderPage(<WithdrawPin />, '/me/withdraw-pin');
    fireEvent.click(await screen.findByRole('tab', { name: '忘记了' }));
    expect(await screen.findByRole('button', { name: '发送验证码' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '申请人工重设' })).not.toBeInTheDocument();
  });
});

describe('提示页：找回 Telegram 账户', () => {
  it('说明流程，按钮用新 Telegram 打开机器人直接进入找回', async () => {
    renderPage(
      <Routes>
        <Route path="/tg/recover" element={<TgEntry kind="recover" />} />
      </Routes>,
      '/tg/recover',
    );
    expect(await screen.findByTestId('tg-recover-steps')).toHaveTextContent('48 小时反对期');
    expect(await screen.findByRole('link', { name: '用新 Telegram 打开机器人申请' })).toHaveAttribute('href', 'https://t.me/TradingAssistTestBot?start=recover');
  });
});
