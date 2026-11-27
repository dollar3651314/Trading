/**
 * TG 机器人第 4 批 4c 后台：客户详情"更换 Telegram 关联 / 解除关联"（只有超管；要新 TG 编号、后台密码、原因；之后提现冻结来源"客服操作"）；
 * 找回申请详情列出最近充值和区块浏览器链接。页面跑模拟数据（mock）模式；真实接口的请求体在 api/real.test.ts。
 */
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import * as real from './api/real';
import * as db from './mocks/data/db';
import { recoveries, withdrawSafety } from './mocks/handlers';
import { renderAs } from './test/render';
import { fakeFetch } from './test/fakeFetch';
import { CustomerDetailPage } from './pages/customers/CustomerDetailPage';

function confirmHighRisk(dialog: HTMLElement, reason: string, ok: string) {
  const boxes = within(dialog).getAllByRole('textbox');
  fireEvent.change(boxes[boxes.length - 1], { target: { value: reason } });
  const checks = within(dialog).getAllByRole('checkbox');
  fireEvent.click(checks[checks.length - 1]);
  fireEvent.click(within(dialog).getByRole('button', { name: ok }));
}

afterEach(() => {
  recoveries.splice(0);
});

describe('客户详情：更换 / 解除 Telegram 关联', () => {
  it('超管：填新 TG 编号、后台密码、原因后更换；之后提现冻结来源是客服操作', async () => {
    const c = db.customers[3];
    const before = { id: c.telegramId, u: c.telegramUsername };
    c.telegramId = '710000001';
    c.telegramUsername = 'oldone';
    try {
      renderAs(
        <Routes>
          <Route path="/customers/:id" element={<CustomerDetailPage />} />
        </Routes>,
        { path: `/customers/${c.id}` },
      );
      fireEvent.click(await screen.findByRole('button', { name: '更换 Telegram 关联' }));
      const dialog = await screen.findByRole('dialog');
      expect(dialog).toHaveTextContent('冻结提现');
      fireEvent.change(within(dialog).getByLabelText('新的 Telegram 用户编号'), { target: { value: '710000002' } });
      fireEvent.change(within(dialog).getByLabelText('你的登录密码'), { target: { value: 'pw' } });
      confirmHighRisk(dialog, '视频核对后换绑', '确认更换');
      await waitFor(() => expect(c.telegramId).toBe('710000002'), { timeout: 5000 });
      expect(withdrawSafety.get(c.id)?.freezes?.map((f) => f.source)).toContain('admin');
    } finally {
      c.telegramId = before.id;
      c.telegramUsername = before.u;
      withdrawSafety.delete(c.id);
    }
  }, 30_000);

  it('不是超管：看不到按钮', async () => {
    const c = db.customers[4];
    const before = c.telegramId;
    c.telegramId = '710000003';
    try {
      renderAs(
        <Routes>
          <Route path="/customers/:id" element={<CustomerDetailPage />} />
        </Routes>,
        { path: `/customers/${c.id}`, adminId: 2 },
      );
      expect(await screen.findByText(/710000003/)).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: '更换 Telegram 关联' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: '解除关联' })).not.toBeInTheDocument();
    } finally {
      c.telegramId = before;
    }
  }, 30_000);

  it('真实接口：POST /customers/{id}/telegram 的请求体', async () => {
    const { calls } = fakeFetch({ 'POST /api/admin/customers/7/telegram': { body: { id: 7 } } });
    await real.changeCustomerTelegram(7, '710000009', 'newme', '换绑', 'pw');
    await real.changeCustomerTelegram(7, null, null, '解除', 'pw');
    expect(calls[0].body).toEqual({ telegramId: '710000009', username: 'newme', reason: '换绑', password: 'pw' });
    expect(calls[1].body).toEqual({ telegramId: null, username: null, reason: '解除', password: 'pw' });
  });
});
