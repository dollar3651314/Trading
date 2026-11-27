/**
 * TG 机器人第 6 批后台加固（TB-37、TB-38）：找回申请页的"暂停受理人工申请"（只有 Amos 的账号能开关，后台密码 + 原因）；
 * 告警邮件里的撤销链接页（不用登录，令牌在 # 后面、打开时从地址栏去掉；先看是哪一份申请、点了才撤销；只能用一次）；告警邮件模板不能在后台改。
 * 页面跑模拟数据；真实接口的请求体单独核对。
 */
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import * as real from './api/real';
import type { Recovery } from './api/types';
import * as db from './mocks/data/db';
import { intakeLog, MOCK_REVOKE_TOKEN, recoveries, usedRevokeTokens } from './mocks/handlers';
import { RecoveriesPage } from './pages/customers/RecoveriesPage';
import { TemplatesPage } from './pages/notify/TemplatesPage';
import { RecoveryRevokePage, revokeTokenFromHash } from './pages/RecoveryRevokePage';
import { fakeFetch } from './test/fakeFetch';
import { renderAs } from './test/render';

const rec = (p: Partial<Recovery>): Recovery => ({
  id: '41', kind: 'rebind', customerId: db.customers[0].id, customerName: db.customers[0].name, customerTelegram: null, kycStatus: 'approved',
  applicantTgId: '800000041', applicantTgUsername: 'x', status: 'approved', disputed: false, disputePinOk: false, disputeFrozen: false, objectionEndsAt: null,
  proofNote: 'ok', approvedBy: '负责人', approvedAt: '2026-10-14 09:00:00', executeAt: '2026-10-15T09:00:00Z', revokeRequested: false, revokeRequestedAt: null,
  closedBy: null, closeReason: null, closedAt: null, createdAt: '2026-10-10 00:00:00', ...p,
});

function confirmHighRisk(dialog: HTMLElement, reason: string, ok: string) {
  const boxes = within(dialog).getAllByRole('textbox');
  fireEvent.change(boxes[boxes.length - 1], { target: { value: reason } });
  const checks = within(dialog).getAllByRole('checkbox');
  fireEvent.click(checks[checks.length - 1]);
  fireEvent.click(within(dialog).getByRole('button', { name: ok }));
}

afterEach(() => {
  recoveries.splice(0);
  intakeLog.splice(0);
  usedRevokeTokens.clear();
  db.session.adminId = 1;
});

describe('暂停受理人工申请（TB-38）', () => {
  it('Amos：后台密码 + 原因后暂停，再恢复', async () => {
    renderAs(<RecoveriesPage />, { path: '/recoveries' });
    expect(await screen.findByTestId('intake-state')).toHaveTextContent('正常受理');
    fireEvent.click(screen.getByTestId('intake-toggle'));
    let dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('已提交的留在队列');
    fireEvent.change(within(dialog).getByLabelText('你的后台登录密码'), { target: { value: 'pw' } });
    confirmHighRisk(dialog, '出差一周', '确认暂停');
    await waitFor(() => expect(screen.getByTestId('intake-state')).toHaveTextContent('暂停受理人工申请中'), { timeout: 5000 });
    expect(intakeLog.at(-1)).toMatchObject({ paused: true, reason: '出差一周' });
    fireEvent.click(screen.getByTestId('intake-toggle'));
    dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('你的后台登录密码'), { target: { value: 'pw' } });
    confirmHighRisk(dialog, '回来了', '确认恢复');
    await waitFor(() => expect(screen.getByTestId('intake-state')).toHaveTextContent('正常受理'), { timeout: 5000 });
  }, 30_000);

  it('别的管理员：按钮不能点，提示只有 Amos 能操作', async () => {
    const ops = db.roles.find((r) => r.id === 'R-2')!;
    const before = [...ops.permissions];
    ops.permissions.push('recovery:review');
    db.session.adminId = 2;
    try {
      renderAs(<RecoveriesPage />, { path: '/recoveries', adminId: 2 });
      expect(await screen.findByTestId('intake-card')).toHaveTextContent('只有 Amos 的后台账号');
      expect(screen.getByTestId('intake-toggle')).toBeDisabled();
    } finally {
      ops.permissions.splice(0, ops.permissions.length, ...before);
    }
  }, 30_000);
});

describe('告警邮件里的撤销链接（TB-37 ②）', () => {
  it('打开时先从地址栏去掉令牌、显示是哪一份申请；点了才撤销；第二次用不了', async () => {
    recoveries.push(rec({}));
    window.history.replaceState(null, '', `/recovery-revoke#t=${MOCK_REVOKE_TOKEN}`);
    const r1 = renderAs(<Routes><Route path="/recovery-revoke" element={<RecoveryRevokePage />} /></Routes>, { path: '/recovery-revoke' });
    expect(await screen.findByTestId('revoke-confirm')).toBeInTheDocument();
    expect(window.location.hash).toBe('');
    expect(screen.getByText('#41')).toBeInTheDocument();
    expect(screen.getByText(/一旦执行/)).toBeInTheDocument();
    expect(recoveries[0].status).toBe('approved');
    fireEvent.click(screen.getByTestId('revoke-confirm'));
    expect(await screen.findByTestId('revoke-result')).toHaveTextContent('找回申请 #41 已撤销');
    expect(recoveries[0].status).toBe('cancelled');
    r1.unmount();
    window.history.replaceState(null, '', `/recovery-revoke#t=${MOCK_REVOKE_TOKEN}`);
    renderAs(<Routes><Route path="/recovery-revoke" element={<RecoveryRevokePage />} /></Routes>, { path: '/recovery-revoke' });
    expect(await screen.findByTestId('revoke-invalid')).toHaveTextContent('已经用过');
    expect(screen.queryByTestId('revoke-confirm')).toBeNull();
  }, 30_000);

  it('申请已经执行：不显示撤销按钮，说明链接不能用了、改用后台或服务器命令', async () => {
    recoveries.push(rec({ status: 'executed' }));
    window.history.replaceState(null, '', `/recovery-revoke#t=${MOCK_REVOKE_TOKEN}`);
    renderAs(<Routes><Route path="/recovery-revoke" element={<RecoveryRevokePage />} /></Routes>, { path: '/recovery-revoke' });
    const closed = await screen.findByTestId('revoke-closed');
    expect(closed).toHaveTextContent('已经结束');
    expect(closed).toHaveTextContent('一旦执行');
    expect(closed).toHaveTextContent('sudo trading cancel-recovery');
    expect(screen.queryByTestId('revoke-confirm')).toBeNull();
  }, 30_000);

  it('令牌只认 # 后面的 t=', () => {
    expect(revokeTokenFromHash('#t=abcdefghijklmnopqrstuvwx')).toBe('abcdefghijklmnopqrstuvwx');
    expect(revokeTokenFromHash('#t=short')).toBeNull();
    expect(revokeTokenFromHash('')).toBeNull();
  });

  it('真实接口：先看（不用掉）再撤销；暂停受理的请求体', async () => {
    const { calls } = fakeFetch({
      'POST /api/admin/public/recovery-revoke/peek': { body: { valid: true, id: 9, kind: 'rebind', customerId: 1, status: 'approved', executeAt: null } },
      'POST /api/admin/public/recovery-revoke': { body: { result: 'cancelled' } },
      'POST /api/admin/recovery-intake': { body: { paused: true, changedAt: null, changedBy: null, reason: null, canOperate: true, ownerConfigured: true, history: [] } },
    });
    expect((await real.peekRecoveryRevoke('tok')).id).toBe(9);
    expect(await real.revokeRecoveryByLink('tok')).toBe('cancelled');
    expect((await real.setRecoveryIntake(true, '出差', 'pw')).paused).toBe(true);
    expect(calls.map((c) => c.body)).toEqual([{ token: 'tok' }, { token: 'tok' }, { paused: true, reason: '出差', password: 'pw' }]);
  });
});

describe('告警邮件模板不能在后台改（TB-37）', () => {
  it('locked 的模板编辑按钮不能点', async () => {
    const tpl = db.templates[0];
    tpl.locked = true;
    try {
      renderAs(<TemplatesPage />, { path: '/notify/templates' });
      // 登录信息（权限）到了以后，只有 locked 的那一个不能点
      await waitFor(() => {
        const rows = screen.getAllByRole('button', { name: /编辑/ });
        expect(rows.length).toBeGreaterThan(1);
        expect(rows.filter((b) => (b as HTMLButtonElement).disabled)).toHaveLength(1);
      }, { timeout: 5000 });
    } finally {
      delete tpl.locked;
    }
  }, 30_000);
});

describe('客户安全提醒模板（#46 审查）', () => {
  it('编辑时提示只能改文案、会发告警邮件；不能停用；不能删原来的变量；改文案可以', async () => {
    const { listTemplates, updateTemplate } = await import('./mocks/handlers');
    const tpl = db.templates.find((x) => x.code.toUpperCase().startsWith('WITHDRAW_'))!;
    const before = { ...tpl };
    try {
      const listed = (await listTemplates()).find((x) => x.id === tpl.id)!;
      expect(listed.security).toBe(true);
      await expect(updateTemplate(tpl.id, { enabled: false })).rejects.toMatchObject({ reasonKey: expect.stringMatching(/CannotDisable/) });
      await expect(updateTemplate(tpl.id, { bodyZh: '状态变了' })).rejects.toMatchObject({ reasonKey: 'errors.validationFailed' });
      await updateTemplate(tpl.id, { bodyZh: `${tpl.bodyZh}（请留意）` });
      expect(tpl.bodyZh).toContain('（请留意）');
      renderAs(<TemplatesPage />, { path: '/notify/templates' });
      await waitFor(() => expect(screen.getAllByRole('button', { name: /编辑/ }).some((b) => !(b as HTMLButtonElement).disabled)).toBe(true), { timeout: 5000 });
      const rows = screen.getAllByRole('row');
      const row = rows.find((r) => r.textContent?.includes(tpl.nameZh))!;
      fireEvent.click(within(row).getByRole('button', { name: /编辑/ }));
      expect(await screen.findByTestId('template-security')).toHaveTextContent('只能改文案');
    } finally {
      Object.assign(tpl, before);
    }
  }, 30_000);
});
