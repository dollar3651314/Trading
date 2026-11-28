/**
 * TG 机器人验收反馈 1b：设了提现密码这类安全事件以后，要去 /tg?r=security（"回到 Telegram 机器人，点「重新授权」，再点「打开交易」"），
 * 不能被路由守卫抢先送到 /login（只走 Telegram 时就是普通的"请在 Telegram 打开"）。真机上 Amos 看到的就是后者：
 * 路由开了 v7_startTransition，跳转是过渡更新、清会话是普通更新，守卫先看到"没会话"。另外后端回 401 SECURITY_LOGOUT 时也去这一页。
 */
import { act, fireEvent, screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { vi } from 'vitest';
import { RequireAuth } from '../App';
import { get } from '../api/http';
import TgHint from '../pages/tg/TgHint';
import { renderPage } from '../test/renderApp';
import { useSecurityRelogin } from './useSecurityRelogin';

vi.mock('../api/auth', async (orig) => ({
  ...(await orig<typeof import('../api/auth')>()),
  getAuthOptions: () => Promise.resolve({ telegramOnly: true, botUsername: 'TradingAssistTestBot', loginMode: 'login-url', emergencyLogin: false, loginBotUsername: null }),
}));

function SetPinDone() {
  const relogin = useSecurityRelogin();
  return (
    <button type="button" onClick={relogin}>
      设好了
    </button>
  );
}

function routes() {
  return (
    <Routes>
      <Route path="/me/withdraw-pin" element={<RequireAuth><SetPinDone /></RequireAuth>} />
      <Route path="/positions" element={<RequireAuth><div>持仓页</div></RequireAuth>} />
      <Route path="/tg" element={<TgHint />} />
      <Route path="/login" element={<div>普通登录页</div>} />
    </Routes>
  );
}

beforeEach(() => {
  localStorage.setItem('fx.session', JSON.stringify({ token: 't', userId: '1', email: null }));
});
afterEach(() => {
  localStorage.removeItem('fx.session');
  vi.unstubAllGlobals();
});

describe('安全事件以后去「重新授权」提示页', () => {
  it('设好提现密码：去 /tg?r=security，显示回机器人点「重新授权」再点「打开交易」，不是普通登录页', async () => {
    renderPage(routes(), '/me/withdraw-pin');
    fireEvent.click(await screen.findByRole('button', { name: '设好了' }));
    const reason = await screen.findByTestId('tg-reason');
    expect(reason).toHaveTextContent('请回到 Telegram 机器人，点「重新授权」，再点「打开交易」');
    expect(screen.queryByText('普通登录页')).toBeNull();
    // 深链接（验收反馈 1c）
    expect(await screen.findByTestId('tg-open')).toHaveAttribute('href', 'https://t.me/TradingAssistTestBot?start=reauth');
  });

  it('别的请求先收到 401 SECURITY_LOGOUT：同样去 /tg?r=security；普通 401 照旧去登录页', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ code: 'SECURITY_LOGOUT', message: 'x', fields: [] }), { status: 401 })));
    const r = renderPage(routes(), '/positions');
    expect(await screen.findByText('持仓页')).toBeInTheDocument();
    await act(async () => {
      await get('/api/me/x').catch(() => undefined);
    });
    expect(await screen.findByTestId('tg-reason')).toHaveTextContent('「重新授权」');
    r.unmount();

    localStorage.setItem('fx.session', JSON.stringify({ token: 't', userId: '1', email: null }));
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ code: 'UNAUTHORIZED', message: 'x', fields: [] }), { status: 401 })));
    renderPage(routes(), '/positions');
    expect(await screen.findByText('持仓页')).toBeInTheDocument();
    await act(async () => {
      await get('/api/me/x').catch(() => undefined);
    });
    expect(await screen.findByText('普通登录页')).toBeInTheDocument();
  });
});
