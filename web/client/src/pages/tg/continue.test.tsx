/**
 * 11/28 Amos 定的进网页方式：机器人里的按钮是普通网址（?from=tg），7 天内用过网页就直接进入；这个浏览器里没有会话时显示短页
 * 「回到机器人继续」（深链接 ?start=open，机器人发一整套新菜单，「打开交易」是一次性链接）；一次性链接过期但浏览器里本来就有会话时直接进去。
 */
import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { vi } from 'vitest';
import { RequireAuth } from '../../App';
import { renderPage } from '../../test/renderApp';
import TgHint from './TgHint';
import TgLink from './TgLink';

const tgLink = vi.hoisted(() => vi.fn());
const restoreSession = vi.hoisted(() => vi.fn());
vi.mock('../../api/auth', async (orig) => ({
  ...(await orig<typeof import('../../api/auth')>()),
  getAuthOptions: () => Promise.resolve({ telegramOnly: true, botUsername: 'TradingAssistTestBot', loginMode: 'link', emergencyLogin: false, loginBotUsername: null }),
  tgLink: (...a: unknown[]) => tgLink(...a),
  restoreSession: (...a: unknown[]) => restoreSession(...a),
}));

function routes() {
  return (
    <Routes>
      <Route path="/trade" element={<RequireAuth><div>交易页</div></RequireAuth>} />
      <Route path="/" element={<RequireAuth><div>首页</div></RequireAuth>} />
      <Route path="/tg" element={<TgHint />} />
      <Route path="/tg-link" element={<TgLink />} />
      <Route path="/login" element={<div>普通登录页</div>} />
    </Routes>
  );
}

afterEach(() => {
  localStorage.removeItem('fx.session');
  tgLink.mockReset();
  restoreSession.mockReset();
});

describe('回到机器人继续', () => {
  it('从机器人的普通按钮进来、没有会话：短页「回到机器人继续」，按钮是 ?start=open', async () => {
    renderPage(routes(), '/trade?from=tg');
    expect(await screen.findByText('回到机器人继续')).toBeInTheDocument();
    expect(screen.getByTestId('tg-reason')).toHaveTextContent('最新一条消息里的「进入交易 ›」');
    expect(await screen.findByTestId('tg-open')).toHaveAttribute('href', 'https://t.me/TradingAssistTestBot?start=open');
    expect(screen.queryByText('普通登录页')).toBeNull();
  });

  it('不是从机器人来的（没有 from=tg）：照旧去登录页', async () => {
    renderPage(routes(), '/trade');
    expect(await screen.findByText('普通登录页')).toBeInTheDocument();
  });

  it('有会话时直接进入，不提示', async () => {
    localStorage.setItem('fx.session', JSON.stringify({ token: 't', userId: '1', email: null, fromTelegram: true }));
    renderPage(routes(), '/trade?from=tg');
    expect(await screen.findByText('交易页')).toBeInTheDocument();
  });

  it('一次性链接过期了，但浏览器里本来就有会话：直接进去，不让客户感知重新登录', async () => {
    window.history.replaceState(null, '', '/tg-link#t=' + 'a'.repeat(43));
    tgLink.mockResolvedValue({ reason: 'expired' });
    restoreSession.mockResolvedValue({ userId: '1', email: null, fromTelegram: true });
    renderPage(routes(), '/tg-link');
    expect(await screen.findByText('首页')).toBeInTheDocument();
  });

  it('一次性链接过期、也没有会话：按原因显示', async () => {
    window.history.replaceState(null, '', '/tg-link#t=' + 'b'.repeat(43));
    tgLink.mockResolvedValue({ reason: 'expired' });
    restoreSession.mockResolvedValue(null);
    renderPage(routes(), '/tg-link');
    expect(await screen.findByTestId('tg-reason')).toBeInTheDocument();
  });
});
