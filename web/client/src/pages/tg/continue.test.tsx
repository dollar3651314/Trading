/**
 * 11/28 Amos 定的进网页方式：机器人里的按钮是普通网址（?from=tg），7 天内用过网页就直接进入；这个浏览器里没有会话时显示短页
 * 「回到机器人继续」（深链接 ?start=open_<页面>，机器人发一整套新菜单，每个进网页的按钮都带一次性令牌，那一页放第一个；验收修复 5）；一次性链接过期但浏览器里本来就有会话时直接进去。
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
      <Route path="/positions" element={<RequireAuth><div>持仓页</div></RequireAuth>} />
      <Route path="/me/settings" element={<RequireAuth><div>设置页</div></RequireAuth>} />
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
  it('从机器人的普通按钮进来、没有会话：短页「回到机器人继续」，按钮是 ?start=open_trade', async () => {
    renderPage(routes(), '/trade?from=tg');
    expect(await screen.findByText('回到机器人继续')).toBeInTheDocument();
    expect(screen.getByTestId('tg-reason')).toHaveTextContent('回到机器人后，点最新一条消息里的按钮（如果没有新消息，发 /start）');
    expect(await screen.findByTestId('tg-open')).toHaveAttribute('href', 'https://t.me/TradingAssistTestBot?start=open_trade');
    expect(screen.queryByText('普通登录页')).toBeNull();
    // #49 审查：说明换了设备或开了"桌面版网站"要重新进入
    expect(screen.getByTestId('tg-device-note')).toHaveTextContent('切换了设备或"桌面版网站"时需要重新进入');
  });

  it('死循环场景（网页一侧）：退出后点普通按钮「持仓」→ 回到机器人继续，深链接带上持仓页 ?start=open_positions', async () => {
    renderPage(routes(), '/positions?from=tg');
    expect(await screen.findByText('回到机器人继续')).toBeInTheDocument();
    expect(await screen.findByTestId('tg-open')).toHaveAttribute('href', 'https://t.me/TradingAssistTestBot?start=open_positions');
    expect(screen.queryByText('持仓页')).toBeNull();
  });

  it('不在白名单里的页面：深链接退回 ?start=open', async () => {
    renderPage(routes(), '/me/settings?from=tg');
    expect(await screen.findByTestId('tg-open')).toHaveAttribute('href', 'https://t.me/TradingAssistTestBot?start=open');
  });

  it('网址里手写的 p 不在白名单：不拼进深链接', async () => {
    renderPage(routes(), '/tg?r=continue&p=evil%26x');
    expect(await screen.findByTestId('tg-open')).toHaveAttribute('href', 'https://t.me/TradingAssistTestBot?start=open');
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

  it('一次性链接过期、也没有会话：去「回到机器人继续」（旧链接没有页面键时深链接是 ?start=open）', async () => {
    window.history.replaceState(null, '', '/tg-link#t=' + 'b'.repeat(43));
    tgLink.mockResolvedValue({ reason: 'expired' });
    restoreSession.mockResolvedValue(null);
    renderPage(routes(), '/tg-link');
    expect(await screen.findByText('回到机器人继续')).toBeInTheDocument();
    expect(await screen.findByTestId('tg-open')).toHaveAttribute('href', 'https://t.me/TradingAssistTestBot?start=open');
  });

  it('#51 审查：旧菜单的「持仓」令牌过期、浏览器里有会话：进持仓页，不是首页', async () => {
    window.history.replaceState(null, '', '/tg-link?p=positions#t=' + 'c'.repeat(43));
    tgLink.mockResolvedValue({ reason: 'expired' });
    restoreSession.mockResolvedValue({ userId: '1', email: null, fromTelegram: true });
    renderPage(routes(), '/tg-link?p=positions');
    expect(await screen.findByText('持仓页')).toBeInTheDocument();
    expect(window.location.hash).toBe('');
  });

  it('#51 审查：旧菜单的「持仓」令牌过期、没有会话：「回到机器人继续」带上持仓页 ?start=open_positions', async () => {
    window.history.replaceState(null, '', '/tg-link?p=positions#t=' + 'd'.repeat(43));
    tgLink.mockResolvedValue({ reason: 'used' });
    restoreSession.mockResolvedValue(null);
    renderPage(routes(), '/tg-link?p=positions');
    expect(await screen.findByTestId('tg-open')).toHaveAttribute('href', 'https://t.me/TradingAssistTestBot?start=open_positions');
  });

  it('页面键不在白名单：没有会话时深链接不带页面', async () => {
    window.history.replaceState(null, '', '/tg-link?p=evil#t=' + 'e'.repeat(43));
    tgLink.mockResolvedValue({ reason: 'expired' });
    restoreSession.mockResolvedValue(null);
    renderPage(routes(), '/tg-link?p=evil');
    expect(await screen.findByTestId('tg-open')).toHaveAttribute('href', 'https://t.me/TradingAssistTestBot?start=open');
  });

  it('冻结这类别的原因照旧按原因显示', async () => {
    window.history.replaceState(null, '', '/tg-link?p=positions#t=' + 'f'.repeat(43));
    tgLink.mockResolvedValue({ reason: 'frozen' });
    restoreSession.mockResolvedValue(null);
    renderPage(routes(), '/tg-link?p=positions');
    expect(await screen.findByTestId('tg-reason')).toHaveTextContent('账户已冻结');
  });
});
