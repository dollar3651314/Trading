/**
 * 第 4 批 4c 前端：网页"申请冻结提现"（不用登录；客户号 + 本人信息 + 图形验证码；回同一句；验证码不对单独提示并换一张）、
 * 提现冻结页（列出全部来源；机器人里自己冻结的用随机键盘输提现密码申请自助解冻）。
 */
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { ApiError } from '../../api/errors';
import type { MyFreeze } from '../../api/types';
import { renderPage } from '../../test/renderApp';
import WithdrawFreeze from '../me/WithdrawFreeze';
import TgEntry from './TgEntry';

const LAYOUT = ['7', '3', '0', '9', '1', '5', '8', '2', '6', '4'];
const requestWithdrawFreeze = vi.fn();
const getFreezeCaptcha = vi.fn();
const requestSelfUnfreeze = vi.fn();
let freezes: MyFreeze[] = [];

vi.mock('../../api/wallet', async (orig) => ({
  ...(await orig<typeof import('../../api/wallet')>()),
  getFreezeCaptcha: (...a: unknown[]) => getFreezeCaptcha(...a),
  requestWithdrawFreeze: (...a: unknown[]) => requestWithdrawFreeze(...a),
  listMyFreezes: async () => freezes,
  requestSelfUnfreeze: (...a: unknown[]) => requestSelfUnfreeze(...a),
  getPinKeypad: async () => ({ keypadId: 'kp-1', digits: LAYOUT }),
}));

beforeEach(() => {
  let n = 0;
  getFreezeCaptcha.mockImplementation(async () => ({ id: `cap-${++n}`, pngBase64: 'iVBORw0KGgo=', hard: false }));
});

afterEach(() => {
  requestWithdrawFreeze.mockReset();
  getFreezeCaptcha.mockReset();
  requestSelfUnfreeze.mockReset();
  freezes = [];
});

function renderForm() {
  renderPage(
    <Routes>
      <Route path="/tg/freeze" element={<TgEntry kind="freeze" />} />
    </Routes>,
    '/tg/freeze',
  );
}

describe('申请冻结提现（网页，不用登录）', () => {
  it('做过身份认证：填客户号、姓名、证件号后 4 位、验证码，提交后显示同一句', async () => {
    requestWithdrawFreeze.mockResolvedValue({ message: '已受理；信息正确的话会冻结提现并通知账户持有人。' });
    renderForm();
    const form = await screen.findByTestId('freeze-form');
    expect(await within(form).findByRole('img', { name: '图形验证码' })).toHaveAttribute('src', 'data:image/png;base64,iVBORw0KGgo=');
    fireEvent.click(within(form).getByRole('button', { name: '申请冻结提现' }));
    expect(within(form).getAllByText('必填').length).toBeGreaterThan(0);
    expect(requestWithdrawFreeze).not.toHaveBeenCalled();
    fireEvent.change(within(form).getByLabelText('客户号'), { target: { value: '100482' } });
    fireEvent.change(within(form).getByLabelText('身份认证时的姓名'), { target: { value: '李雷' } });
    fireEvent.change(within(form).getByLabelText('证件号后 4 位'), { target: { value: '4321' } });
    fireEvent.change(within(form).getByLabelText('验证码'), { target: { value: 'ac34' } });
    fireEvent.click(within(form).getByRole('button', { name: '申请冻结提现' }));
    expect(await screen.findByTestId('freeze-done')).toHaveTextContent('已受理；信息正确的话会冻结提现');
    expect(requestWithdrawFreeze).toHaveBeenCalledWith(expect.objectContaining({ customerNo: '100482', name: '李雷', docLast4: '4321', captchaId: 'cap-1', captcha: 'AC34' }));
    expect(requestWithdrawFreeze.mock.calls[0][0]).not.toHaveProperty('depositAmount');
  });

  it('没做身份认证：填最近一笔充值的金额和日期', async () => {
    requestWithdrawFreeze.mockResolvedValue({ message: 'ok' });
    renderForm();
    const form = await screen.findByTestId('freeze-form');
    fireEvent.click(within(form).getByRole('radio', { name: '没做身份认证' }));
    fireEvent.change(within(form).getByLabelText('客户号'), { target: { value: '100482' } });
    fireEvent.change(within(form).getByLabelText('最近一笔充值的金额'), { target: { value: '123.45' } });
    fireEvent.change(within(form).getByLabelText('最近一笔充值的日期'), { target: { value: '2026-10-01' } });
    fireEvent.change(within(form).getByLabelText('验证码'), { target: { value: 'AC34' } });
    fireEvent.click(within(form).getByRole('button', { name: '申请冻结提现' }));
    await waitFor(() => expect(requestWithdrawFreeze).toHaveBeenCalledWith(expect.objectContaining({ depositAmount: '123.45', depositDate: '2026-10-01' })));
  });

  it('验证码不对：单独提示并换一张', async () => {
    requestWithdrawFreeze.mockRejectedValue(new ApiError('captchaInvalid'));
    renderForm();
    const form = await screen.findByTestId('freeze-form');
    await within(form).findByRole('img', { name: '图形验证码' });
    fireEvent.change(within(form).getByLabelText('客户号'), { target: { value: '1' } });
    fireEvent.change(within(form).getByLabelText('身份认证时的姓名'), { target: { value: 'x' } });
    fireEvent.change(within(form).getByLabelText('证件号后 4 位'), { target: { value: '1111' } });
    fireEvent.change(within(form).getByLabelText('验证码'), { target: { value: 'ZZZZ' } });
    fireEvent.click(within(form).getByRole('button', { name: '申请冻结提现' }));
    expect(await within(form).findByText('验证码不对或已过期，请换一张再试')).toBeInTheDocument();
    await waitFor(() => expect(getFreezeCaptcha).toHaveBeenCalledTimes(2));
  });
});

describe('提现冻结页', () => {
  it('列出全部来源；机器人里自己冻结的输提现密码申请自助解冻', async () => {
    freezes = [
      { source: 'bot_self', at: '2026-11-27T08:00:00Z', releaseAt: null },
      { source: 'web_request', at: '2026-11-27T09:00:00Z', releaseAt: null },
    ];
    requestSelfUnfreeze.mockResolvedValue({ releaseAt: '2026-11-28T10:00:00Z' });
    renderPage(<WithdrawFreeze />, '/me/withdraw-freeze');
    expect(await screen.findByTestId('freeze-bot_self')).toHaveTextContent('在机器人里自己冻结');
    expect(screen.getByTestId('freeze-web_request')).toHaveTextContent('网页申请冻结');
    expect(screen.getByText(/只能人工解冻的冻结/)).toBeInTheDocument();
    await screen.findByRole('button', { name: '7' });
    for (const d of '135790') fireEvent.click(screen.getByRole('button', { name: d }));
    fireEvent.click(screen.getByRole('button', { name: '申请解冻' }));
    await waitFor(() => expect(requestSelfUnfreeze).toHaveBeenCalled());
    expect((requestSelfUnfreeze.mock.calls[0][0] as { positions: number[] }).positions).toEqual([4, 1, 5, 0, 3, 2]);
  });

  it('只有人工解冻的冻结：不显示键盘', async () => {
    freezes = [{ source: 'web_request', at: '2026-11-27T09:00:00Z', releaseAt: null }];
    renderPage(<WithdrawFreeze />, '/me/withdraw-freeze');
    await screen.findByTestId('freeze-web_request');
    expect(screen.queryByRole('button', { name: '申请解冻' })).not.toBeInTheDocument();
  });
});
