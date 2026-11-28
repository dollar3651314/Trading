/**
 * 验收修复 6（真机：iOS Safari 没有会话时打开 /tg-check 看到的是"请在 Telegram 里打开"）：用正式的 App（BrowserRouter + 全部守卫），
 * 复现"没有会话 + 第一阶段只走 Telegram 打开"，/tg-check 必须显示诊断表格。
 * 对照：同样条件下不认识的地址会被守卫送到 /login → "请在 Telegram 里打开"——#51 之前的版本里还没有 /tg-check 这条路由，
 * Service Worker 缓存着旧版本时打开 /tg-check 就是这个结果（推断的真机原因，修法见 vite.config.ts 的 navigateFallbackDenylist；验收修复 7 起 /tg、/tg-link 也不走缓存，并认查询串）。
 */
import { readFileSync } from 'node:fs';
import { render, screen } from '@testing-library/react';
import { vi } from 'vitest';
import App from '../../App';

vi.mock('../../api/auth', async (orig) => ({
  ...(await orig<typeof import('../../api/auth')>()),
  getAuthOptions: () => Promise.resolve({ telegramOnly: true, botUsername: 'TradingAssistTestBot', loginMode: 'link', emergencyLogin: false, loginBotUsername: null }),
  restoreSession: () => Promise.resolve(null),
}));

beforeEach(() => {
  localStorage.removeItem('fx.session');
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ 'User-Agent': 'UA', 'Sec-CH-UA': null, 'Sec-CH-UA-Mobile': null, 'Sec-CH-UA-Platform': null, 'X-Requested-With': null }), { status: 200, headers: { 'Content-Type': 'application/json' } })));
});
afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, '', '/');
});

describe('没有会话 + 第一阶段只走 Telegram', () => {
  it('/tg-check 显示诊断表格，不是"请在 Telegram 里打开"', async () => {
    window.history.replaceState(null, '', '/tg-check');
    render(<App />);
    expect(await screen.findByTestId('tg-check')).toBeInTheDocument();
    expect(await screen.findByText('浏览器环境检查')).toBeInTheDocument();
    expect(screen.getAllByRole('table').length).toBeGreaterThan(0);
    expect(await screen.findByTestId('s-User-Agent')).toHaveTextContent('UA');
    expect(screen.queryByTestId('tg-open')).toBeNull();
    expect(window.location.pathname).toBe('/tg-check');
  });

  it('对照：不认识的地址被守卫送到 /login，显示"请在 Telegram 里打开"（旧版本没有 /tg-check 路由时就是这样）', async () => {
    window.history.replaceState(null, '', '/no-such-page');
    render(<App />);
    expect(await screen.findByTestId('tg-open')).toBeInTheDocument();
    expect(window.location.pathname).toBe('/login');
  });

  it('Service Worker 不接管 /tg-check、/tg、/tg-link 的页面请求（含查询串；Workbox 拿路径 + 查询串去比）', () => {
    const cfg = readFileSync(`${process.cwd()}/vite.config.ts`, 'utf8');
    const line = cfg.split('\n').find((l) => l.includes('navigateFallbackDenylist:')) ?? '';
    const check = /^\/tg-check(\/|\?|$)/;
    const entry = /^\/tg(-link)?(\/|\?|$)/;
    expect(line).toContain(String.raw`/^\/tg-check(\/|\?|$)/`);
    expect(line).toContain(String.raw`/^\/tg(-link)?(\/|\?|$)/`);
    const denied = (u: string) => check.test(u) || entry.test(u);
    for (const u of ['/tg-check', '/tg-check/', '/tg-check?x=1', '/tg', '/tg?r=continue&p=positions', '/tg/freeze', '/tg/recover', '/tg-link', '/tg-link?p=positions'])
      expect(denied(u), u).toBe(true);
    for (const u of ['/tg-checkout', '/tgx', '/tg-linkx', '/trade', '/positions?from=tg', '/']) expect(denied(u), u).toBe(false);
  });
});
