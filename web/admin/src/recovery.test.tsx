/**
 * TG 机器人第 4 批 4b 后台：找回申请（Amos 一人审核：批准要证明核对记录 + 后台密码、驳回 / 撤销要原因、争议路径的撤销请求选维持）、
 * 客户详情里冻结时暂停的提现（逐笔继续放款 / 取消并退回，交过钱包提供方的不能退回）。页面跑模拟数据（mock）模式；真实接口的路径和请求体在 api/real.test.ts。
 */
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import type { Recovery } from './api/types';
import * as db from './mocks/data/db';
import { heldWithdrawals, recoveries } from './mocks/handlers';
import { renderAs } from './test/render';
import { CustomerDetailPage } from './pages/customers/CustomerDetailPage';
import { RecoveriesPage } from './pages/customers/RecoveriesPage';

const base = (p: Partial<Recovery>): Recovery => ({
  id: '31',
  kind: 'rebind',
  customerId: db.customers[0].id,
  customerName: db.customers[0].name,
  customerTelegram: '@orig · 700000001',
  kycStatus: 'approved',
  applicantTgId: '800000001',
  applicantTgUsername: 'newme',
  status: 'proof',
  disputed: false,
  disputePinOk: false,
  disputeFrozen: false,
  objectionEndsAt: '2026-10-12 00:00:00',
  proofNote: null,
  approvedBy: null,
  approvedAt: null,
  executeAt: null,
  revokeRequested: false,
  revokeRequestedAt: null,
  closedBy: null,
  closeReason: null,
  closedAt: null,
  createdAt: '2026-10-10 00:00:00',
  ...p,
});

afterEach(() => {
  recoveries.splice(0);
  heldWithdrawals.clear();
});

/** 高危弹窗：填原因（最后一个文本框）、勾确认、点按钮 */
function confirmHighRisk(dialog: HTMLElement, reason: string, ok: string) {
  const boxes = within(dialog).getAllByRole('textbox');
  fireEvent.change(boxes[boxes.length - 1], { target: { value: reason } });
  const checks = within(dialog).getAllByRole('checkbox');
  fireEvent.click(checks[checks.length - 1]);
  fireEvent.click(within(dialog).getByRole('button', { name: ok }));
}

describe('找回申请', () => {
  it('批准：写证明核对记录、再输后台密码；之后显示执行时间', async () => {
    recoveries.push(base({}));
    renderAs(<RecoveriesPage />);
    fireEvent.click(await screen.findByText('31'));
    const det = await screen.findByRole('region', { name: '找回申请详情' });
    expect(det).toHaveTextContent('@newme');
    fireEvent.click(within(det).getByRole('button', { name: '批准' }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('批准后 24 小时换绑');
    expect(within(dialog).getByText('证明核对记录（来源地址 / 视频共享 App / P2P 等）')).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: '确认批准' }));
    expect(await within(dialog).findByText('请输入你的后台登录密码', undefined, { timeout: 5000 })).toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText('你的后台登录密码'), { target: { value: 'my-own-password' } });
    confirmHighRisk(dialog, '来源地址小额 1.37 + 视频', '确认批准');
    await waitFor(() => expect(recoveries[0].status).toBe('approved'), { timeout: 5000 });
    expect(recoveries[0].proofNote).toBe('来源地址小额 1.37 + 视频');
    expect(await screen.findByText(/证明核对记录：来源地址小额 1.37/, undefined, { timeout: 5000 })).toBeInTheDocument();
  }, 30_000);

  it('争议路径收到撤销请求：显示规则和"维持批准"；本人撤销直接生效', async () => {
    recoveries.push(base({ status: 'approved', disputed: true, disputePinOk: true, revokeRequested: true, revokeRequestedAt: '2026-10-14 09:00:00', executeAt: '2026-10-15T09:00:00Z' }));
    renderAs(<RecoveriesPage />);
    fireEvent.mouseDown(await screen.findByRole('combobox', { name: '状态' }));
    fireEvent.click(await screen.findByTitle('已批准等执行'));
    fireEvent.click(await screen.findByText('31'));
    const det = await screen.findByRole('region', { name: '找回申请详情' });
    expect(within(det).getByRole('group', { name: '争议' })).toHaveTextContent('原 TG 点过"不是我"');
    expect(within(det).getByRole('alert')).toHaveTextContent('换绑申请的撤销一律交你决定');
    expect(within(det).getByRole('button', { name: '维持批准' })).toBeEnabled();
    fireEvent.click(within(det).getByRole('button', { name: '撤销' }));
    const dialog = await screen.findByRole('dialog');
    confirmHighRisk(dialog, '视频核对，申请人不是本人', '确认撤销');
    await waitFor(() => expect(recoveries[0].status).toBe('cancelled'), { timeout: 5000 });
  }, 30_000);

  it('反对期中只能驳回或撤销，不能批准', async () => {
    recoveries.push(base({ status: 'objection' }));
    renderAs(<RecoveriesPage />);
    fireEvent.mouseDown(await screen.findByRole('combobox', { name: '状态' }));
    fireEvent.click(await screen.findByTitle('反对期中'));
    fireEvent.click(await screen.findByText('31'));
    const det = await screen.findByRole('region', { name: '找回申请详情' });
    expect(within(det).queryByRole('button', { name: '批准' })).not.toBeInTheDocument();
    expect(within(det).getByRole('button', { name: '驳回' })).toBeInTheDocument();
  }, 30_000);

  it('证明阶段收到原 TG 的撤销请求（没有争议）：不能批准，只能维持或撤销', async () => {
    recoveries.push(base({ status: 'proof', revokeRequested: true, revokeRequestedAt: '2026-10-14 09:00:00' }));
    renderAs(<RecoveriesPage />);
    fireEvent.click(await screen.findByText('31'));
    const det = await screen.findByRole('region', { name: '找回申请详情' });
    expect(within(det).getByRole('alert')).toHaveTextContent('批准和执行都暂停、提现冻结保留');
    expect(within(det).getByRole('button', { name: '批准' })).toBeDisabled();
    expect(within(det).getByRole('button', { name: '维持批准' })).toBeEnabled();
  }, 30_000);
});

describe('找回申请：维持过一次', () => {
  it('显示"已维持过一次，之后的撤销不再暂停"，经过里有再次撤销', async () => {
    recoveries.push(base({ status: 'approved', executeAt: '2026-10-15T09:00:00Z', keptAt: '2026-10-14 10:00:00' }));
    renderAs(<RecoveriesPage />);
    fireEvent.mouseDown(await screen.findByRole('combobox', { name: '状态' }));
    fireEvent.click(await screen.findByTitle('已批准等执行'));
    fireEvent.click(await screen.findByText('31'));
    const det = await screen.findByRole('region', { name: '找回申请详情' });
    expect(within(det).getByRole('status', { name: '已维持过' })).toHaveTextContent('不再暂停批准和执行');
    expect(within(det).queryByRole('button', { name: '维持批准' })).not.toBeInTheDocument();
  }, 30_000);
});

describe('客户详情：冻结时暂停的提现', () => {
  it('列出暂停的提现；交过钱包提供方的不能取消退回；继续放款要原因', async () => {
    const c = db.customers[0];
    heldWithdrawals.set(c.id, [
      { id: 'W-1', amount: '100.00', chain: 'TRC20', address: 'TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t', heldAt: '2026-10-14T09:00:00Z', reason: '提现已冻结', sentBefore: false },
      { id: 'W-2', amount: '50.00', chain: 'TRC20', address: 'TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t', heldAt: '2026-10-14T09:00:00Z', reason: '账户已冻结', sentBefore: true },
    ]);
    renderAs(
      <Routes>
        <Route path="/customers/:id" element={<CustomerDetailPage />} />
      </Routes>,
      { path: `/customers/${c.id}` },
    );
    const box = await screen.findByRole('group', { name: '冻结时暂停的提现' });
    expect(box).toHaveTextContent('解除冻结后不会自动继续');
    expect(within(screen.getByTestId('held-W-2')).getByRole('button', { name: '取消并退回' })).toBeDisabled();
    fireEvent.click(within(screen.getByTestId('held-W-1')).getByRole('button', { name: '继续放款' }));
    const dialog = await screen.findByRole('dialog');
    confirmHighRisk(dialog, '视频核对本人后继续', '确认继续放款');
    await waitFor(() => expect(heldWithdrawals.get(c.id)?.map((h) => h.id)).toEqual(['W-2']), { timeout: 5000 });
  }, 30_000);
});
