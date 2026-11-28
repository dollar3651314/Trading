/**
 * TG 机器人第 5 批应急开关（TB-34）：开关开着时登录页 / 提示页上方出现「用 Telegram 登录」（Telegram 官方登录小部件，挂在只管登录的机器人上）；
 * 小部件给的身份原样交给后端；成功后先说"24 小时后才能提现"，点「进入交易」再进去；失败按原因显示；关着、用户名不合法时不显示。
 */
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { vi } from 'vitest';
import { renderPage } from '../../test/renderApp';
import Login from '../auth/Login';
import { WIDGET_SRC } from './EmergencyLogin';
import TgHint from './TgHint';

const options = vi.hoisted(() => ({
  telegramOnly: true,
  botUsername: 'TradingAssistTestBot' as string | null,
  loginMode: 'login-url' as const,
  emergencyLogin: true,
  loginBotUsername: 'TradingLoginTestBot' as string | null,
}));
const tgEmergencyLogin = vi.hoisted(() => vi.fn());
/** 登录方式接口已经回应的次数（关着时的用例要等页面真正拿到配置再断言"不显示"） */
const optionsServed = vi.hoisted(() => ({ n: 0 }));
const restoreSession = vi.hoisted(() => vi.fn());
vi.mock('../../api/auth', async (orig) => ({
  ...(await orig<typeof import('../../api/auth')>()),
  getAuthOptions: () =>
    Promise.resolve({ ...options }).then((o) => {
      optionsServed.n++;
      return o;
    }),
  tgEmergencyLogin: (...a: unknown[]) => tgEmergencyLogin(...a),
  restoreSession: (...a: unknown[]) => restoreSession(...a),
}));

type Cb = (user: Record<string, string | number>) => void;
const callback = () => (window as unknown as Record<string, Cb | undefined>).onTrEmergencyTelegramAuth;

/**
 * 等小部件真正挂好：findBy 只说明方框渲染出来了，挂脚本、设回调在之后的副作用里（React 提交后才跑），
 * 直接调 callback() 可能还是 undefined（偶发失败的原因）。这里等到回调和脚本都在，不放宽任何断言
 */
async function widgetReady() {
  const box = await screen.findByTestId('tg-emergency');
  await waitFor(() => {
    expect(callback()).toBeTypeOf('function');
    expect(box.querySelector('script')).not.toBeNull();
  });
  return box;
}

/** 等这次渲染的页面（提示页和应急登录各请求一次）拿到登录方式，并让随后的状态更新落地，再断言"不显示" */
async function optionsSettled(before: number) {
  await waitFor(() => expect(optionsServed.n).toBeGreaterThanOrEqual(before + 2));
  await act(async () => {});
}

function hint() {
  return renderPage(
    <Routes>
      <Route path="/tg" element={<TgHint />} />
      <Route path="/" element={<div>交易首页</div>} />
    </Routes>,
    '/tg',
  );
}

describe('应急开关：用 Telegram 登录', () => {
  beforeEach(() => {
    options.emergencyLogin = true;
    options.loginBotUsername = 'TradingLoginTestBot';
    tgEmergencyLogin.mockReset();
    restoreSession.mockReset();
  });

  it('开着时提示页上方有应急提示和登录小部件（登录机器人、回调），冻结和找回入口照旧', async () => {
    hint();
    const box = await widgetReady();
    expect(box).toHaveTextContent('24 小时内不能提现');
    expect(box).toHaveTextContent('只保留最后登录的一台设备');
    const s = box.querySelector('script');
    expect(s).not.toBeNull();
    expect(s!.getAttribute('src')).toBe(WIDGET_SRC);
    expect(s!.getAttribute('data-telegram-login')).toBe('TradingLoginTestBot');
    expect(s!.getAttribute('data-onauth')).toBe('onTrEmergencyTelegramAuth(user)');
    expect(callback()).toBeTypeOf('function');
    expect(screen.getByTestId('tg-freeze')).toBeInTheDocument();
    expect(screen.getByTestId('tg-recover')).toBeInTheDocument();
  });

  it('小部件给的身份原样交给后端；成功后先说 24 小时后才能提现，点进入交易才进去', async () => {
    tgEmergencyLogin.mockResolvedValue({ withdrawAt: '2026-11-28T14:05:00Z' });
    restoreSession.mockResolvedValue({ userId: 1, email: null });
    hint();
    await widgetReady();
    const user = { id: 6100000001, first_name: 'Li', username: 'li', auth_date: 1790000000, hash: 'ab'.repeat(32) };
    await act(async () => callback()!(user));
    expect(tgEmergencyLogin).toHaveBeenCalledWith(user);
    const done = await screen.findByTestId('tg-emergency-done');
    expect(done).toHaveTextContent('你刚用 Telegram 登录');
    expect(done).toHaveTextContent('24 小时后');
    expect(done).toHaveTextContent('别的设备上的登录已经退出');
    expect(restoreSession).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId('tg-emergency-enter'));
    expect(await screen.findByText('交易首页')).toBeInTheDocument();
    expect(restoreSession).toHaveBeenCalled();
  });

  it('失败按原因显示（冻结、过期），不认识的原因按校验没通过', async () => {
    tgEmergencyLogin.mockResolvedValueOnce({ reason: 'frozen' }).mockResolvedValueOnce({ reason: 'expired' }).mockResolvedValueOnce({ reason: '<x>' });
    hint();
    await widgetReady();
    await act(async () => callback()!({ id: 1, hash: 'x' }));
    expect(await screen.findByTestId('tg-emergency-error')).toHaveTextContent('账户已冻结，请联系客服。');
    await act(async () => callback()!({ id: 1, hash: 'x' }));
    await waitFor(() => expect(screen.getByTestId('tg-emergency-error')).toHaveTextContent('授权已经过期，请重新授权'));
    await act(async () => callback()!({ id: 1, hash: 'x' }));
    await waitFor(() => expect(screen.getByTestId('tg-emergency-error')).toHaveTextContent('校验没有通过'));
  });

  it('开关关着、登录机器人用户名不合法时不显示', async () => {
    options.emergencyLogin = false;
    let before = optionsServed.n;
    const r = hint();
    await screen.findByTestId('tg-freeze');
    await optionsSettled(before);
    expect(screen.queryByTestId('tg-emergency')).toBeNull();
    r.unmount();
    options.emergencyLogin = true;
    options.loginBotUsername = 'bad"name';
    before = optionsServed.n;
    hint();
    await screen.findByTestId('tg-freeze');
    await optionsSettled(before);
    expect(screen.queryByTestId('tg-emergency')).toBeNull();
    expect(callback()).toBeUndefined();
  });

  it('没开"只走 Telegram"时，邮箱登录页上方也有「用 Telegram 登录」', async () => {
    options.telegramOnly = false;
    renderPage(<Routes><Route path="/login" element={<Login />} /></Routes>, '/login');
    expect(await screen.findByTestId('tg-emergency')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /登录/ })).toBeInTheDocument();
    options.telegramOnly = true;
  });
});
