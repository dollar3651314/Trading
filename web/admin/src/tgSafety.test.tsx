/**
 * TG 机器人第 4 批（资金安全）后台部分：TG 规则的提现标出"一律人工"和提交入口；没邮箱 TG 客户的首笔要勾"已人工核实"才能通过；
 * 客户详情显示提现安全（提现密码、冻结来源），提现冻结只能人工解除（高危、填原因）。页面跑模拟数据（mock）模式；真实接口的路径和请求体在 api/real.test.ts。
 */
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { approveWithdrawal, getCustomer, listWithdrawals } from './api';
import * as db from './mocks/data/db';
import { withdrawSafety } from './mocks/handlers';
import { renderAs } from './test/render';
import { CustomerDetailPage } from './pages/customers/CustomerDetailPage';
import { WithdrawalsPage } from './pages/funds/WithdrawalsPage';

/** 列表第一页（默认筛选）里待审核、而且名字在这一页只出现一次的一笔（按名字点得准），改成没邮箱 TG 客户的首笔 */
async function tgPending() {
  const page = (await listWithdrawals({ page: 1, pageSize: 8, status: 'open' })).items;
  const hit = page.find((x) => x.status === 'pending' && page.filter((y) => y.customerName === x.customerName).length === 1)!;
  const w = db.withdrawals.find((x) => x.id === hit.id)!;
  Object.assign(w, { ruleType: 'tg', source: 'bot', firstCheck: true, firstChecked: false });
  return w;
}

/** 高危弹窗：填原因、勾确认、点按钮 */
function confirmHighRisk(dialog: HTMLElement, reason: string, ok: string) {
  const boxes = within(dialog).getAllByRole('textbox');
  fireEvent.change(boxes[boxes.length - 1], { target: { value: reason } });
  const checks = within(dialog).getAllByRole('checkbox');
  fireEvent.click(checks[checks.length - 1]);
  fireEvent.click(within(dialog).getByRole('button', { name: ok }));
}

describe('首笔核实', () => {
  it('没勾"已核实"不能通过；勾了以后通过并记下已核实', async () => {
    const w = await tgPending();
    const saved = structuredClone(w);
    try {
      await expect(approveWithdrawal(w.id)).rejects.toMatchObject({ reasonKey: 'errors.firstCheckRequired' });
      expect(w.status).toBe('pending');
      await approveWithdrawal(w.id, true);
      expect(w.firstChecked).toBe(true);
      expect(w.status).not.toBe('pending');
    } finally {
      Object.assign(w, saved);
    }
  });

  it('详情：标出 TG 规则和提交入口，"通过"按钮要先勾核实', async () => {
    const w = await tgPending();
    renderAs(<WithdrawalsPage />);
    fireEvent.click((await screen.findAllByText(w.customerName))[0]);
    const box = await screen.findByRole('group', { name: 'TG 规则' });
    expect(box).toHaveTextContent('机器人进入提交');
    expect(box).toHaveTextContent('通过前要人工核实本人和资金来源');
    expect(screen.getByRole('button', { name: '通过' })).toBeDisabled();
    fireEvent.click(screen.getByRole('checkbox', { name: /我已人工核实本人和资金来源/ }));
    await waitFor(() => expect(screen.getByRole('button', { name: '通过' })).toBeEnabled());
    Object.assign(w, { ruleType: undefined, source: undefined, firstCheck: undefined, firstChecked: undefined });
  }, 30_000);
});

describe('客户详情：提现安全', () => {
  it('显示冻结来源，人工解除要填原因，解除后不再显示冻结', async () => {
    const c = db.customers[0];
    const before = c.telegramId;
    c.telegramId = '700000001';
    withdrawSafety.set(c.id, { pinSet: true, pinLockedUntil: null, withdrawFrozen: true, frozenSource: 'web_request', frozenAt: '2026-11-20T08:00:00Z' });
    try {
      renderAs(
        <Routes>
          <Route path="/customers/:id" element={<CustomerDetailPage />} />
        </Routes>,
        { path: `/customers/${c.id}` },
      );
      expect(await screen.findByText('提现已冻结（网页申请冻结）')).toBeInTheDocument();
      expect(screen.getByText('已设提现密码')).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: /解除提现冻结/ }));
      const dialog = await screen.findByRole('dialog');
      expect(dialog).toHaveTextContent('冻结来源：网页申请冻结');
      fireEvent.click(within(dialog).getByRole('button', { name: '确认解除' }));
      expect(await within(dialog).findByText('请填写操作原因', undefined, { timeout: 5000 })).toBeInTheDocument();
      confirmHighRisk(dialog, '视频核实本人，客户确认是自己申请的冻结', '确认解除');
      await waitFor(() => expect(screen.queryByText('提现已冻结（网页申请冻结）')).not.toBeInTheDocument(), { timeout: 5000 });
      expect((await getCustomer(c.id)).withdrawSafety?.withdrawFrozen).toBe(false);
    } finally {
      c.telegramId = before;
      withdrawSafety.delete(c.id);
    }
  }, 30_000);

  it('不是 TG 客户：没有提现安全这一项', async () => {
    const c = db.customers.find((x) => !x.telegramId)!;
    expect((await getCustomer(c.id)).withdrawSafety).toBeNull();
  });
});
