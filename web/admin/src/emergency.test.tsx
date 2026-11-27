/**
 * TG 机器人第 5 批后台"应急开关"（TB-34）：只有 Amos 的后台账号（原型里是 1 号负责人）能开关；打开、关闭都要再输后台密码和原因、写审计；
 * 有开关记录（含服务器终端的）；别人能看（有 emergency:view 时）但按钮不能点；没有权限看不到。页面跑模拟数据；真实接口的请求体单独核对。
 */
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import * as real from './api/real';
import * as db from './mocks/data/db';
import { getEmergency } from './mocks/handlers';
import { EmergencyPage } from './pages/system/EmergencyPage';
import { fakeFetch } from './test/fakeFetch';
import { renderAs } from './test/render';
import { MENU } from './config/menu';

function confirmHighRisk(dialog: HTMLElement, reason: string, ok: string) {
  const boxes = within(dialog).getAllByRole('textbox');
  fireEvent.change(boxes[boxes.length - 1], { target: { value: reason } });
  const checks = within(dialog).getAllByRole('checkbox');
  fireEvent.click(checks[checks.length - 1]);
  fireEvent.click(within(dialog).getByRole('button', { name: ok }));
}

describe('应急开关', () => {
  it('Amos：再输后台密码、写原因后打开，记录里多一条；再关上', async () => {
    renderAs(<EmergencyPage />, { path: '/system/emergency' });
    expect(await screen.findByTestId('emergency-state')).toHaveTextContent('关闭');
    expect(screen.getByText('演练结束（服务器终端关）')).toBeInTheDocument();
    expect(screen.getByText(/server:root（服务器终端）/)).toBeInTheDocument();
    expect(screen.getByText(/sudo trading set TR_TG_EMERGENCY_LOGIN on/)).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('emergency-toggle'));
    let dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('24 小时不能提现');
    fireEvent.change(within(dialog).getByLabelText('再输一次你的后台密码'), { target: { value: 'pw' } });
    confirmHighRisk(dialog, '机器人被封', '确认打开');
    await waitFor(() => expect(screen.getByTestId('emergency-state')).toHaveTextContent('已打开'), { timeout: 5000 });
    expect(await screen.findByText('机器人被封')).toBeInTheDocument();
    expect((await getEmergency()).on).toBe(true);

    fireEvent.click(screen.getByTestId('emergency-toggle'));
    dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('会话全部退出');
    fireEvent.change(within(dialog).getByLabelText('再输一次你的后台密码'), { target: { value: 'pw' } });
    confirmHighRisk(dialog, '机器人恢复了', '确认关闭');
    await waitFor(() => expect(screen.getByTestId('emergency-state')).toHaveTextContent('关闭'), { timeout: 5000 });
    expect((await getEmergency()).on).toBe(false);
  }, 30_000);

  it('别的管理员有查看权限：能看状态和记录，按钮不能点，提示只有 Amos 能操作', async () => {
    const ops = db.roles.find((r) => r.id === 'R-2')!;
    const before = [...ops.permissions];
    ops.permissions.push('emergency:view');
    // 模拟数据按 db.session 认当前管理员；页面取数可能比登录信息先到，先切过去
    db.session.adminId = 2;
    try {
      renderAs(<EmergencyPage />, { path: '/system/emergency', adminId: 2 });
      expect(await screen.findByTestId('emergency-not-owner')).toHaveTextContent('只有 Amos 的后台账号');
      expect(screen.getByTestId('emergency-toggle')).toBeDisabled();
    } finally {
      db.session.adminId = 1;
      ops.permissions.splice(0, ops.permissions.length, ...before);
    }
  }, 30_000);

  it('菜单在"系统"下面，权限 emergency:view', () => {
    const sys = MENU.find((m) => m.key === 'system');
    expect(sys?.children?.find((c) => c.key === 'emergency')).toMatchObject({ path: '/system/emergency', permission: 'emergency:view' });
  });

  it('真实接口：GET / POST /api/admin/emergency 的请求体', async () => {
    const state = { on: false, changedAt: null, changedBy: null, reason: null, canOperate: true, ownerConfigured: true, history: [] };
    const { calls } = fakeFetch({ 'GET /api/admin/emergency': { body: state }, 'POST /api/admin/emergency': { body: { ...state, on: true } } });
    expect((await real.getEmergency()).canOperate).toBe(true);
    expect((await real.setEmergency(true, '机器人被封', 'pw')).on).toBe(true);
    expect(calls[1].body).toEqual({ on: true, reason: '机器人被封', password: 'pw' });
  });
});
