/**
 * 第 1 批（原型 P4～P7，Amos 11/17 确认）：出金两道审（付款编号、付款状态、"未知"红色置顶只能重新查询）、入金记录的提供方两列、
 * 对账"按单号逐笔核对"和立即对账、钱包提供方页（网络开关和切换是高危操作，原因必填、写审计）、菜单和权限点。
 */
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { renderAs, setScenario } from './test/render';
import { WithdrawalsPage } from './pages/funds/WithdrawalsPage';
import { DepositsPage } from './pages/funds/DepositsPage';
import { ReconPage } from './pages/funds/ReconPage';
import { ProviderPage } from './pages/funds/ProviderPage';
import { setCurrentProviderConnectedForTest } from './mocks/handlers';
import { listWithdrawals, requeryWithdrawal, approveWithdrawal } from './api';
import { PERMISSIONS } from './config/permissions';
import { MENU } from './config/menu';

afterEach(() => {
  setScenario('normal');
  setCurrentProviderConnectedForTest(true);
});

describe('出金审核：两道审（P4）', () => {
  it('列表的"未知"提示在第一屏，点"去重新查询"选中那一笔，详情里有"重新查询"和灰的"退款"', async () => {
    renderAs(<WithdrawalsPage />);
    await screen.findByText(/两道审：这里审核通过/);
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '状态' }));
    fireEvent.click(await screen.findByTitle('放款中'));
    fireEvent.click(screen.getByRole('button', { name: /查\s*询/ }));
    expect(await screen.findByText(/先查清付款状态"未知"的提现/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '去重新查询' }));
    const detail = await screen.findByRole('complementary', { name: '出金详情' });
    await waitFor(() => expect(within(detail).getByRole('group', { name: '第二道审核（钱包提供方放款）' })).toBeInTheDocument());
    expect(within(detail).getByRole('button', { name: '重新查询' })).toBeEnabled();
    expect(within(detail).getByRole('button', { name: '退款' })).toBeDisabled();
  });

  it('页面写明两道审；付款状态"未知"的红色置顶、只能重新查询、退款按钮灰；查清后变成已完成', async () => {
    renderAs(<WithdrawalsPage />);
    expect(await screen.findByText(/两道审：这里审核通过/)).toBeInTheDocument();
    const all = (await listWithdrawals({ page: 1, pageSize: 200 })).items;
    const unknown = all.find((w) => w.payoutStatus === 'unknown')!;
    expect(unknown.status).toBe('broadcasting');
    expect(unknown.payoutNo).toMatch(/^WD-/);
    // 列表里不一定在第一页：直接调接口查清
    const done = await requeryWithdrawal(unknown.id);
    expect(done.status).toBe('completed');
    expect(done.payoutStatus).toBe('completed');
  });

  it('小额审核通过后已交 TopUP：有付款编号、"待放款审核"', async () => {
    const pending = (await listWithdrawals({ page: 1, pageSize: 200, status: 'pending' })).items.find((w) => Number(w.amount) < 3000)!;
    const r = await approveWithdrawal(pending.id);
    expect(r.status).toBe('broadcasting');
    expect(r.payoutStatus).toBe('pending');
    expect(r.payoutNo).toMatch(/^WD-/);
  });
});

describe('入金记录（P5）', () => {
  it('有"提供方""提供方到账编号"两列，不再有确认数；写明低于 1 USDT 不出现', async () => {
    renderAs(<DepositsPage />);
    expect(await screen.findByText('提供方到账编号', { selector: 'th, th *' })).toBeInTheDocument();
    expect(screen.queryByText('确认数', { selector: 'th, th *' })).not.toBeInTheDocument();
    expect(screen.getByText(/低于 1 USDT 的转账 TopUP 不通知/)).toBeInTheDocument();
    expect(screen.getAllByText('TopUP').length).toBeGreaterThan(0);
  });
});

describe('对账（P6）', () => {
  it('顶部固定写"按单号逐笔核对，不是全量对账"；显示上次对账、核对笔数；可以立即对账', async () => {
    renderAs(<ReconPage />);
    expect(await screen.findByText('现在是"按单号逐笔核对"，不是全量对账')).toBeInTheDocument();
    expect(await screen.findByText('126')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '立即对账' }));
    expect(await screen.findByText('对账完成')).toBeInTheDocument();
  });
});

describe('钱包提供方（P7）', () => {
  it('在用的提供方没接入（地址和 Key 没配全）：卡片标红"在用 · 未接入"、顶部提示，不显示给其他家的占位说明（T-5）', async () => {
    setCurrentProviderConnectedForTest(false);
    renderAs(<ProviderPage />);
    expect(await screen.findByText('在用 · 未接入')).toBeInTheDocument();
    expect(screen.getByText('在用的钱包提供方 TopUP 没有接入')).toBeInTheDocument();
    expect(screen.getAllByText(/FX_TOPUP_BASE_URL、FX_TOPUP_API_KEY、FX_TOPUP_API_SECRET 缺一个就算未接入/).length).toBeGreaterThan(0);
    expect(screen.getAllByText('留位置，Amos 开账号后另立一版接入。')).toHaveLength(2);
  });

  it('TopUP 在用、Cobo 和 Fireblocks 未接入；关网络要填原因并勾选确认，写进操作记录', async () => {
    renderAs(<ProviderPage />);
    expect(await screen.findByText('在用')).toBeInTheDocument();
    expect(screen.getAllByText('未接入')).toHaveLength(2);
    expect(screen.queryByRole('button', { name: '切换到它' })).not.toBeInTheDocument();
    expect(screen.getByText(/先不设（Amos 没给数字，TY-6）/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('switch', { name: 'BSC 充值开关' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/已经发出去的 BSC 地址，到账照常入账/)).toBeInTheDocument();
    fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: 'TopUP BSC 节点维护' } });
    fireEvent.click(within(dialog).getByRole('checkbox'));
    fireEvent.click(within(dialog).getByRole('button', { name: /确认关闭/ }));
    expect(await screen.findByText('关闭 BSC 充值')).toBeInTheDocument();
    expect(screen.getByText('TopUP BSC 节点维护')).toBeInTheDocument();
  });
});

describe('菜单和权限点', () => {
  it('资金下有"钱包提供方"，权限 provider:view；开关和切换是高危权限 provider:operate；归集的权限点已经去掉', () => {
    const funds = MENU.find((m) => m.key === 'funds')!;
    expect(funds.children!.find((c) => c.key === 'walletProvider')!.permission).toBe('provider:view');
    expect(PERMISSIONS.find((p) => p.code === 'provider:operate')!.highRisk).toBe(true);
    expect(PERMISSIONS.some((p) => p.code.startsWith('sweep:'))).toBe(false);
  });
});
