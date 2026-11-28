/**
 * TG 机器人第 2 批：第一阶段只走 Telegram 时注册 / 登录 / 忘记密码换成提示页；提示页显示机器人按钮、二维码和
 * 「申请冻结提现」「找回 Telegram 账户」两个入口；从机器人进来没成功时按原因显示；一次性链接从 # 后面取令牌。
 */
import { screen, waitFor } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { vi } from 'vitest';
import { renderPage } from '../../test/renderApp';
import { TelegramOnly } from './TelegramOnly';
import TgHint from './TgHint';
import { botLink, botStartLink, tokenFromHash } from './tgLib';

const options = vi.hoisted(() => ({ telegramOnly: true, botUsername: 'TradingAssistTestBot' as string | null, loginMode: 'login-url' as const }));
vi.mock('../../api/auth', async (orig) => ({
  ...(await orig<typeof import('../../api/auth')>()),
  getAuthOptions: () => Promise.resolve({ ...options }),
}));

describe('第一阶段只走 Telegram', () => {
  it('telegramOnly 时登录页换成提示页：机器人按钮、二维码、冻结和找回两个入口', async () => {
    options.telegramOnly = true;
    renderPage(
      <Routes>
        <Route path="/login" element={<TelegramOnly><div>邮箱登录表单</div></TelegramOnly>} />
      </Routes>,
      '/login',
    );
    // 深链接（11/28 起普通提示页是 ?start=open：机器人发一整套带一次性链接的新菜单；安全事件后是 ?start=reauth）
    expect(await screen.findByTestId('tg-open')).toHaveAttribute('href', 'https://t.me/TradingAssistTestBot?start=open');
    expect(screen.queryByText('邮箱登录表单')).toBeNull();
    expect(screen.getByRole('img', { name: '打开机器人的二维码' })).toBeInTheDocument();
    expect(screen.getByTestId('tg-freeze')).toHaveAttribute('href', '/tg/freeze');
    expect(screen.getByTestId('tg-recover')).toHaveAttribute('href', '/tg/recover');
  });

  it('不限制时照常显示原页面', async () => {
    options.telegramOnly = false;
    renderPage(
      <Routes>
        <Route path="/login" element={<TelegramOnly><div>邮箱登录表单</div></TelegramOnly>} />
      </Routes>,
      '/login',
    );
    expect(await screen.findByText('邮箱登录表单')).toBeInTheDocument();
  });

  it('从机器人进来没成功：按原因显示（拒绝授权、过期、冻结……），不认识的原因不显示', async () => {
    options.telegramOnly = true;
    const { unmount } = renderPage(<Routes><Route path="/tg" element={<TgHint />} /></Routes>, '/tg?r=refused');
    expect(await screen.findByTestId('tg-reason')).toHaveTextContent('点了取消');
    unmount();
    const r2 = renderPage(<Routes><Route path="/tg" element={<TgHint />} /></Routes>, '/tg?r=frozen');
    expect(await screen.findByTestId('tg-reason')).toHaveTextContent('账户已冻结，请联系客服。');
    r2.unmount();
    renderPage(<Routes><Route path="/tg" element={<TgHint />} /></Routes>, '/tg?r=<script>');
    await waitFor(() => expect(screen.getByTestId('tg-freeze')).toBeInTheDocument());
    expect(screen.queryByTestId('tg-reason')).toBeNull();
  });

  it('机器人用户名只认合法的，拼不出别的网址', () => {
    expect(botLink('TradingAssistBot')).toBe('https://t.me/TradingAssistBot');
    expect(botLink(null)).toBeNull();
    expect(botLink('evil.com/x')).toBeNull();
    expect(botLink('a')).toBeNull();
    expect(botStartLink('TradingAssistBot')).toBe('https://t.me/TradingAssistBot?start=open');
    expect(botStartLink('TradingAssistBot', 'reauth')).toBe('https://t.me/TradingAssistBot?start=reauth');
    expect(botStartLink('evil.com/x')).toBeNull();
  });

  it('一次性链接：令牌从 # 后面取', () => {
    const tok = 'A'.repeat(43);
    expect(tokenFromHash(`#t=${tok}`)).toBe(tok);
    expect(tokenFromHash(`#x=1&t=${tok}`)).toBe(tok);
    expect(tokenFromHash('#t=short')).toBeNull();
    expect(tokenFromHash('')).toBeNull();
  });
});
