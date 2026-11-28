/**
 * 方案 B 门禁（11/28 Amos）：客户端网页只在 Telegram 内置浏览器里能用。用正式的 App（全部路由和守卫）测：
 * 每个 Telegram 标记单独放行、没有标记只显示引导页、开关关着 / 应急开关开着不拦、门禁外的页面任何浏览器都能打开、
 * /tg-link 没有标记时不去换会话（不消耗令牌）、计数请求只带平台大类。
 */
import { render, screen, waitFor } from '@testing-library/react';
import InAppSpy from 'inapp-spy';
import { vi } from 'vitest';
import App from '../../App';
import { guideHitRequest } from '../../api/tgGate';
import { isTelegramInApp, NEUTRAL_UA, platformFamily } from '../../lib/telegramInApp';

const options = vi.hoisted(() => ({ telegramOnly: true, emergencyLogin: false }));
const tgLink = vi.hoisted(() => vi.fn());
const reportGuideShown = vi.hoisted(() => vi.fn());
vi.mock('../../api/auth', async (orig) => ({
  ...(await orig<typeof import('../../api/auth')>()),
  getAuthOptions: () =>
    Promise.resolve({ telegramOnly: options.telegramOnly, botUsername: 'TradingAssistTestBot', loginMode: 'link', emergencyLogin: options.emergencyLogin, loginBotUsername: options.emergencyLogin ? 'TradingLoginTestBot' : null }),
  restoreSession: () => Promise.resolve(null),
  tgLink: (...a: unknown[]) => tgLink(...a),
}));
vi.mock('../../api/tgGate', async (orig) => ({
  ...(await orig<typeof import('../../api/tgGate')>()),
  reportGuideShown: (...a: unknown[]) => reportGuideShown(...a),
}));

const MARKERS = ['TelegramWebview', 'TelegramWebviewProxy', 'TelegramWebviewProxyProto'] as const;
const w = window as unknown as Record<string, unknown>;
const realUa = navigator.userAgent;

function setUa(ua: string) {
  Object.defineProperty(window.navigator, 'userAgent', { value: ua, configurable: true });
}

function open(path: string) {
  window.history.replaceState(null, '', path);
  return render(<App />);
}

beforeEach(() => {
  options.telegramOnly = true;
  options.emergencyLogin = false;
  localStorage.removeItem('fx.session');
  tgLink.mockReset();
  reportGuideShown.mockReset();
});
afterEach(() => {
  for (const m of MARKERS) delete w[m];
  setUa(realUa);
  window.history.replaceState(null, '', '/');
});

describe('方案 B 门禁', () => {
  it('不在 Telegram 内置浏览器里：只显示引导页，没有任何应用界面；计一次数，只报平台大类', async () => {
    open('/markets');
    expect(await screen.findByTestId('tg-guide')).toBeInTheDocument();
    expect(screen.getByText('请在 Telegram 里打开')).toBeInTheDocument();
    expect(await screen.findByTestId('tg-open')).toHaveAttribute('href', 'https://t.me/TradingAssistTestBot?start=open');
    expect(screen.getByTestId('tg-guide-unsupported')).toHaveTextContent('部分手机暂不支持');
    expect(screen.queryByRole('navigation')).toBeNull();
    await waitFor(() => expect(reportGuideShown).toHaveBeenCalledTimes(1));
    expect(reportGuideShown).toHaveBeenCalledWith('OTHER');
  });

  it.each(MARKERS)('有 Telegram 标记 %s：放行（没有会话时照旧是"只走 Telegram"的提示页，不是引导页）', async (m) => {
    w[m] = {};
    open('/login');
    expect(await screen.findByTestId('tg-open')).toBeInTheDocument();
    expect(screen.queryByTestId('tg-guide')).toBeNull();
    expect(reportGuideShown).not.toHaveBeenCalled();
  });

  it('"只走 Telegram"关着：不拦（e2e、原型、生产默认都是这样）', async () => {
    options.telegramOnly = false;
    open('/login');
    expect(await screen.findByRole('button', { name: /登录/ })).toBeInTheDocument();
    expect(screen.queryByTestId('tg-guide')).toBeNull();
  });

  it('应急开关开着：整个门禁不起作用（机器人可能被封，应急登录要在任意浏览器里能用）', async () => {
    options.emergencyLogin = true;
    open('/tg');
    expect(await screen.findByTestId('tg-emergency')).toBeInTheDocument();
    expect(screen.queryByTestId('tg-guide')).toBeNull();
  });

  it.each([
    ['/tg/freeze', 'freeze-form'],
    ['/tg/recover', 'tg-recover-steps'],
    ['/tg-check', 'tg-check'],
  ])('门禁外的页面 %s：没有标记也能打开', async (path, testId) => {
    open(path);
    expect(await screen.findByTestId(testId)).toBeInTheDocument();
    expect(screen.queryByTestId('tg-guide')).toBeNull();
  });

  it('/tg-link 没有标记：显示引导页，不去换会话（令牌不消耗），令牌从地址栏去掉', async () => {
    open('/tg-link?p=positions#t=' + 'a'.repeat(43));
    expect(await screen.findByTestId('tg-guide')).toBeInTheDocument();
    await waitFor(() => expect(window.location.hash).toBe(''));
    expect(window.location.search).toBe('?p=positions');
    expect(tgLink).not.toHaveBeenCalled();
  });

  it('/tg-link 有标记：照常换会话', async () => {
    w.TelegramWebviewProxy = {};
    tgLink.mockResolvedValue({ reason: 'expired' });
    open('/tg-link?p=positions#t=' + 'b'.repeat(43));
    await waitFor(() => expect(tgLink).toHaveBeenCalledWith('b'.repeat(43)));
  });

  it('iPhone：引导页给 iOS 的步骤（设置 → 数据与存储 → 在应用内打开链接）', async () => {
    setUa('Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.7 Mobile/15E148 Safari/604.1');
    open('/markets');
    expect(await screen.findByTestId('tg-guide-step1')).toHaveTextContent('数据与存储');
    expect(screen.getByTestId('tg-guide')).toHaveAttribute('data-platform', 'IOS');
    await waitFor(() => expect(reportGuideShown).toHaveBeenCalledWith('IOS'));
  });
});

describe('判断函数和计数请求', () => {
  it('isTelegramInApp：安卓 WebView 的 UA（带 wv)）加 TelegramWebview 标记也认得出；没有标记不认', () => {
    setUa('Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0 Mobile Safari/537.36');
    expect(isTelegramInApp()).toBe(false);
    w.TelegramWebview = {};
    expect(isTelegramInApp()).toBe(true);
    // 中性 UA 不命中 inapp-spy 的任何 UA 规则（否则它不看 Telegram 标记）
    delete w.TelegramWebview;
    expect(InAppSpy({ ua: NEUTRAL_UA }).isInApp).toBe(false);
  });

  it('platformFamily：iPhone、iPadOS 桌面 UA 加触屏、鸿蒙的安卓 UA、电脑', () => {
    expect(platformFamily('Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X)', false)).toBe('IOS');
    expect(platformFamily('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Version/18.0 Safari/605.1.15', true)).toBe('IOS');
    expect(platformFamily('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Version/18.0 Safari/605.1.15', false)).toBe('OTHER');
    expect(platformFamily('Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Mobile Safari/537.36', false)).toBe('ANDROID');
    expect(platformFamily('Mozilla/5.0 (Windows NT 10.0; Win64; x64)', false)).toBe('OTHER');
  });

  it('计数请求：请求体只有平台大类，不带 Cookie', () => {
    const r = guideHitRequest('ANDROID');
    expect(JSON.parse(String(r.body))).toEqual({ platform: 'ANDROID' });
    expect(r.credentials).toBe('omit');
    expect(r.method).toBe('POST');
  });
});
