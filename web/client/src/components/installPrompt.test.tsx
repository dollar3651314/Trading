/**
 * TG 机器人验收反馈 A：从 Telegram 打开时行情页顶部不显示"添加到主屏幕"引导（Telegram 内置浏览器里加不了）。
 * 主要看会话种类（后端 /api/me/session 的 fromTelegram：这次是从机器人进来的 BOT 会话）；User-Agent 带 Telegram 时也不显示。
 * 手机浏览器打开（不是从机器人来）照常显示。
 */
import { screen } from '@testing-library/react';
import { vi } from 'vitest';
import { isTelegramUa } from '../lib/telegramUa';
import { renderPage } from '../test/renderApp';
import { InstallPrompt } from './InstallPrompt';

afterEach(() => {
  localStorage.removeItem('fx.session');
  localStorage.removeItem('fx.installPrompt.dismissed');
  vi.unstubAllGlobals();
});

function session(fromTelegram: boolean) {
  localStorage.setItem('fx.session', JSON.stringify({ token: 't', userId: '1', email: null, ...(fromTelegram ? { fromTelegram: true } : {}) }));
}

describe('添加到主屏幕引导', () => {
  it('手机浏览器打开（不是从机器人来）：显示', async () => {
    session(false);
    renderPage(<InstallPrompt />);
    expect(await screen.findByText('添加到主屏幕，接收提醒')).toBeInTheDocument();
  });

  it('从机器人进来（BOT 会话）：不显示', () => {
    session(true);
    renderPage(<InstallPrompt />);
    expect(screen.queryByText('添加到主屏幕，接收提醒')).toBeNull();
  });

  it('User-Agent 带 Telegram：不显示', () => {
    session(false);
    vi.stubGlobal('navigator', { ...navigator, userAgent: 'Mozilla/5.0 (Linux; Android 14) Chrome/130 Mobile Telegram-Android/11.4' });
    renderPage(<InstallPrompt />);
    expect(screen.queryByText('添加到主屏幕，接收提醒')).toBeNull();
    expect(isTelegramUa('Mozilla/5.0 (iPhone; CPU iPhone OS 18_0) Safari/604.1')).toBe(false);
  });
});
