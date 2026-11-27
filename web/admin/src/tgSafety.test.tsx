/**
 * TG 机器人第 4 批（资金安全）后台部分：TG 规则的提现标出"一律人工"和提交入口；没邮箱 TG 客户的首笔要勾"已人工核实"才能通过；
 * 客户详情显示提现安全（提现密码、冻结来源），提现冻结只能人工解除（高危、填原因）。页面跑模拟数据（mock）模式；真实接口的路径和请求体在 api/real.test.ts。
 */
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { approveWithdrawal, getCustomer, listWithdrawals } from './api';
import * as db from './mocks/data/db';
import { withdrawEvents, withdrawSafety } from './mocks/handlers';
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

describe('冻结的客户不能通过出金（审查 🔴4a-1）', () => {
  it('提现冻结：详情写明"提现已冻结"和最近 48 小时的安全事件，"通过"按钮不可点，接口也拒绝', async () => {
    const w = await tgPending();
    withdrawSafety.set(w.customerId, { pinSet: true, pinLockedUntil: null, withdrawFrozen: true, frozenSource: 'bot_self', frozenAt: '2026-11-27T08:00:00Z' });
    withdrawEvents.set(w.customerId, [{ type: 'pin_change', at: '2026-11-27 07:00:00' }]);
    try {
      await expect(approveWithdrawal(w.id, true)).rejects.toMatchObject({ reasonKey: 'errors.customerWithdrawFrozen' });
      renderAs(<WithdrawalsPage />);
      fireEvent.click((await screen.findAllByText(w.customerName))[0]);
      const box = await screen.findByRole('group', { name: '客户安全情况' });
      expect(box).toHaveTextContent('提现已冻结（客户在机器人里自己冻结）');
      expect(box).toHaveTextContent('改了提现密码');
      expect(screen.getByRole('button', { name: '通过' })).toBeDisabled();
    } finally {
      withdrawSafety.delete(w.customerId);
      withdrawEvents.delete(w.customerId);
      Object.assign(w, { ruleType: undefined, source: undefined, firstCheck: undefined, firstChecked: undefined });
    }
  }, 30_000);
});

describe('客户详情：多个来源的提现冻结（4b 审查 🔴R2）', () => {
  it('列出全部来源，解除时要选来源；只解除选的那个', async () => {
    const c = db.customers[2];
    const before = c.telegramId;
    c.telegramId = '700000003';
    withdrawSafety.set(c.id, {
      pinSet: true, pinLockedUntil: null, withdrawFrozen: true, frozenSource: 'pin_locks', frozenAt: '2026-11-20T08:00:00Z',
      freezes: [
        { source: 'pin_locks', reason: '30 天内锁 2 次', at: '2026-11-20T08:00:00Z' },
        { source: 'recovery', reason: '找回申请 #1 证明阶段', at: '2026-11-21T08:00:00Z' },
      ],
    });
    try {
      renderAs(
        <Routes>
          <Route path="/customers/:id" element={<CustomerDetailPage />} />
        </Routes>,
        { path: `/customers/${c.id}` },
      );
      expect(await screen.findByText('提现已冻结（30 天内提现密码锁了 2 次）')).toBeInTheDocument();
      expect(screen.getByText('提现已冻结（换绑证明阶段）')).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: /解除提现冻结/ }));
      const dialog = await screen.findByRole('dialog');
      expect(dialog).toHaveTextContent('其中有找回申请带来的冻结');
      fireEvent.mouseDown(within(dialog).getByRole('combobox', { name: '解除哪个来源' }));
      fireEvent.click(await screen.findByTitle('30 天内提现密码锁了 2 次'));
      fireEvent.change(within(dialog).getByLabelText('你的登录密码'), { target: { value: 'pw' } });
      confirmHighRisk(dialog, '视频核对本人', '确认解除');
      await waitFor(() => expect(withdrawSafety.get(c.id)?.freezes?.map((f) => f.source)).toEqual(['recovery']), { timeout: 5000 });
      expect(withdrawSafety.get(c.id)?.withdrawFrozen).toBe(true);
    } finally {
      c.telegramId = before;
      withdrawSafety.delete(c.id);
    }
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
      expect(within(dialog).getByText('请输入你的登录密码')).toBeInTheDocument();
      fireEvent.change(within(dialog).getByLabelText('你的登录密码'), { target: { value: 'my-own-password' } });
      confirmHighRisk(dialog, '视频核实本人，客户确认是自己申请的冻结', '确认解除');
      await waitFor(() => expect(screen.queryByText('提现已冻结（网页申请冻结）')).not.toBeInTheDocument(), { timeout: 5000 });
      expect((await getCustomer(c.id)).withdrawSafety?.withdrawFrozen).toBe(false);
    } finally {
      c.telegramId = before;
      withdrawSafety.delete(c.id);
    }
  }, 30_000);

  it('解除提现冻结要单独的权限 withdraw:unfreeze：运营角色没有（默认只有超管）', async () => {
    const c = db.customers[1];
    const before = c.telegramId;
    c.telegramId = '700000002';
    withdrawSafety.set(c.id, { pinSet: true, pinLockedUntil: null, withdrawFrozen: true, frozenSource: 'pin_locks', frozenAt: '2026-11-20T08:00:00Z' });
    try {
      renderAs(
        <Routes>
          <Route path="/customers/:id" element={<CustomerDetailPage />} />
        </Routes>,
        { path: `/customers/${c.id}`, adminId: 2 },
      );
      expect(await screen.findByRole('button', { name: /解除提现冻结/ })).toBeDisabled();
    } finally {
      c.telegramId = before;
      withdrawSafety.delete(c.id);
    }
  });

  it('不是 TG 客户：没有提现安全这一项', async () => {
    const c = db.customers.find((x) => !x.telegramId)!;
    expect((await getCustomer(c.id)).withdrawSafety).toBeNull();
  });
});
