/** Telegram 内置浏览器识别诊断页 /tg-check：不登录就显示；浏览器信息、服务器看到的请求头（请求不带 Cookie） */
import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { vi } from 'vitest';
import { renderPage } from '../../test/renderApp';
import TgCheck from './TgCheck';
import { collectClientInfo, INAPP_UA, isTelegramWebview, SAFARI_UA } from './tgCheckLib';

const w = window as unknown as Record<string, unknown>;

afterEach(() => {
  delete w.TelegramWebviewProxy;
  delete w.myTelegramBridge;
  vi.unstubAllGlobals();
});

describe('浏览器环境检查 /tg-check', () => {
  it('列出 Telegram 相关的全局对象（只列名字）、Cookie 和 localStorage 能不能用', () => {
    w.TelegramWebviewProxy = { postEvent: () => undefined };
    w.myTelegramBridge = 1;
    const rows = Object.fromEntries(collectClientInfo());
    expect(rows['typeof TelegramWebviewProxy']).toBe('object');
    expect(rows['typeof Telegram']).toBe('undefined');
    expect(rows['名字带 telegram 的 window 属性']).toBe('TelegramWebviewProxy, myTelegramBridge');
    expect(rows['navigator.userAgent']).toBe(navigator.userAgent);
    expect(rows['Cookie']).toBe('能用');
    expect(rows['localStorage']).toBe('能用');
    expect(localStorage.getItem('fx.tgcheck')).toBeNull();
    expect(document.cookie).not.toContain('fx_tgcheck=1');
    // 社区已知的 Telegram 内置浏览器标记用 in 判断（不可枚举的也认得出）
    expect(rows['iOS Telegram 标记："TelegramWebviewProxy" in window']).toBe('有');
    expect(rows['安卓 Telegram 标记："TelegramWebview" in window']).toBe('没有');
  });

  it('不可枚举的 TelegramWebviewProxyProto 也认得出；UA 的普通 Safari 规则和安卓 wv) 标记', () => {
    Object.defineProperty(window, 'TelegramWebviewProxyProto', { value: {}, enumerable: false, configurable: true });
    try {
      expect(Object.fromEntries(collectClientInfo())['iOS Telegram 标记："TelegramWebviewProxyProto" in window']).toBe('有');
    } finally {
      delete w.TelegramWebviewProxyProto;
    }
    const safari = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1';
    const iosWebview = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148';
    const androidWv = 'Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0 Mobile Safari/537.36';
    expect(SAFARI_UA.test(safari)).toBe(true);
    expect(SAFARI_UA.test(iosWebview)).toBe(false);
    expect(INAPP_UA.test(safari)).toBe(false);
    expect(INAPP_UA.test(iosWebview)).toBe(true);
    expect(INAPP_UA.test(androidWv)).toBe(true);
    expect(isTelegramWebview(window)).toBe(false);
  });

  it('不登录直接显示；服务器看到的请求头来自 /api/public/tg-check，请求不带 Cookie', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ 'User-Agent': 'UA-from-server Telegram-Android/11.4', 'Sec-CH-UA': null, 'Sec-CH-UA-Mobile': '?1', 'Sec-CH-UA-Platform': '"Android"', 'X-Requested-With': 'org.telegram.messenger' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);
    renderPage(
      <Routes>
        <Route path="/tg-check" element={<TgCheck />} />
      </Routes>,
      '/tg-check',
    );
    expect(await screen.findByText('浏览器环境检查')).toBeInTheDocument();
    expect(await screen.findByTestId('s-X-Requested-With')).toHaveTextContent('org.telegram.messenger');
    expect(screen.getByTestId('s-Sec-CH-UA')).toHaveTextContent('（没有）');
    expect(screen.getByTestId('c-navigator.userAgent')).toHaveTextContent(navigator.userAgent);
    expect(fetchMock).toHaveBeenCalledWith('/api/public/tg-check', expect.objectContaining({ credentials: 'omit' }));
  });

  it('接口失败（例如限流）：写明没取到，浏览器这边照常显示', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 429 })));
    renderPage(
      <Routes>
        <Route path="/tg-check" element={<TgCheck />} />
      </Routes>,
      '/tg-check',
    );
    expect(await screen.findByTestId('s-error')).toHaveTextContent('没取到：HTTP 429');
    expect(screen.getByTestId('c-localStorage')).toHaveTextContent('能用');
  });
});
