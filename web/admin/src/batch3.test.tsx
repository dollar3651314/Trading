/**
 * 第 3 批（原型 P8～P11，Amos 11/17 确认）：报价服务四页——外部客户和 Key（Secret 只显示一次、要勾"已复制保存"才能关）、
 * 限额和用量（只计量不收费、80% 提醒、只改这一个 Key / 改默认值）、授权登记和总开关（只有超管、要勾"Amos 已同意"、到期提醒）、报价源状态。
 */
import { configure, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { renderAs, setScenario } from './test/render';
import * as db from './mocks/data/db';
import { QuoteKeysPage } from './pages/quote/QuoteKeysPage';
import { QuoteUsagePage } from './pages/quote/QuoteUsagePage';
import { QuoteLicensePage } from './pages/quote/QuoteLicensePage';
import { QuoteSourcesPage } from './pages/quote/QuoteSourcesPage';
import { getQuoteOverview, setQuoteGate } from './api';
import { seedQuoteLicensesForDemo, setQuoteLicenseEnforcedForTest } from './mocks/quoteService';
import { PERMISSIONS } from './config/permissions';
import { MENU } from './config/menu';

// 报价服务页的表格多，jsdom 里渲染慢：等待上限放宽到 15 秒（只影响这个文件）
configure({ asyncUtilTimeout: 15000 });

afterEach(async () => {
  setScenario('normal');
  db.session.adminId = 1;
  // 总开关复原成关（默认）；授权检查复原成关（默认）
  await setQuoteGate(false, false, '测试复原');
  setQuoteLicenseEnforcedForTest(false);
});

async function confirmHighRisk(dialog: HTMLElement, reason: string, okName: RegExp) {
  const boxes = within(dialog).getAllByRole('textbox');
  fireEvent.change(boxes[boxes.length - 1], { target: { value: reason } });
  const checks = within(dialog).getAllByRole('checkbox');
  fireEvent.click(checks[checks.length - 1]);
  fireEvent.click(within(dialog).getByRole('button', { name: okName }));
}

describe('外部客户和 Key（P8）', () => {
  it('总开关默认关：外部 Key 标"总开关关，现在不能用"；停用的标"已停用"', async () => {
    renderAs(<QuoteKeysPage />);
    expect(await screen.findByText('对外开放总开关：关（默认）')).toBeInTheDocument();
    expect(screen.getByText('总开关关，现在不能用')).toBeInTheDocument();
    expect(screen.getAllByText('已停用').length).toBeGreaterThan(0);
  });

  it('建 Key：原因必填、勾确认；Secret 只显示一次，没勾"我已经复制保存好了"关不掉；关掉后页面上没有 Secret', async () => {
    renderAs(<QuoteKeysPage />);
    fireEvent.click(await screen.findByRole('button', { name: '+ 建 Key' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/REST 每秒 10 次、每天 100,000 次/)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: /建 Key/ }));
    expect(await within(dialog).findByText('请填写操作原因')).toBeInTheDocument();
    await confirmHighRisk(dialog, '对接测试', /建 Key/);
    const secretDialog = await screen.findByRole('dialog', { name: 'Secret 只显示这一次' });
    const secret = within(secretDialog).getByLabelText('Secret').textContent!;
    expect(secret).toMatch(/^qs_/);
    fireEvent.click(within(secretDialog).getByRole('button', { name: /关\s*闭/ }));
    expect(await within(secretDialog).findByText('请先确认已保存')).toBeInTheDocument();
    fireEvent.click(within(secretDialog).getByRole('checkbox', { name: '我已经复制保存好了' }));
    fireEvent.click(within(secretDialog).getByRole('button', { name: /关\s*闭/ }));
    await waitFor(() => expect(screen.queryByText(secret)).not.toBeInTheDocument());
    expect(JSON.stringify(await getQuoteOverview())).not.toContain(secret);
  }, 60_000);

  it('没有客户时大按钮"建第一个客户"', async () => {
    setScenario('empty');
    renderAs(<QuoteKeysPage />);
    await waitFor(() => expect(screen.getByRole('button', { name: '建第一个客户' })).toBeInTheDocument());
  });

  it('加载失败显示重试', async () => {
    setScenario('error');
    renderAs(<QuoteKeysPage />);
    expect(await screen.findByRole('button', { name: /重\s*试/ })).toBeInTheDocument();
  });
});

describe('限额和用量（P9）', () => {
  it('顶部写"只计量不收费"；用到 83% 的 Key 标"已发 80% 提醒邮件"，100% 的写拒绝了多少次；没有价格、账单字样', async () => {
    renderAs(<QuoteUsagePage />);
    expect(await screen.findByText('只计量不收费')).toBeInTheDocument();
    expect(screen.getByText(/83% · 已发 80% 提醒邮件/)).toBeInTheDocument();
    expect(screen.getByText(/今天已拒绝超出的 1,206 次请求（429）/)).toBeInTheDocument();
    expect(screen.getByText('新建 Key 用这组默认值；改默认值不影响已建的 Key。')).toBeInTheDocument();
    // 只计量不收费：页面上没有价格、单价、月费、支付入口（顶部说明文字本身除外）
    expect(document.body.textContent).not.toMatch(/单价|月费|\/\s*月|去支付|付款方式/);
  });

  it('改某个 Key 的限额只改它；改默认值不影响已建 Key', async () => {
    renderAs(<QuoteUsagePage />);
    // 等两份数据（Key 和明细）都到了再点，免得点到重新加载前的按钮
    await screen.findAllByText('REST /v1/quotes');
    fireEvent.click(screen.getAllByRole('button', { name: '改这个 Key 的限额' })[0]);
    let dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/只改这一个 Key/)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: /取\s*消/ }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '改默认值' }));
    dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('REST 每天请求数'), { target: { value: '5000' } });
    await confirmHighRisk(dialog, '调默认', /确认/);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const ov = await getQuoteOverview();
    expect(ov.defaults.restPerDay).toBe(5000);
    expect(ov.keys.find((k) => k.keyId === 'qk_K3p9Fg5Hj2Kl8Mn4e02D')!.limits.restPerDay).toBe(100000);
  }, 60_000);
});

describe('授权登记和总开关（P10）', () => {
  it('0 家授权（现在的真实情况）：大按钮"登记第一条授权"；总开关关', async () => {
    renderAs(<QuoteLicensePage />);
    expect(await screen.findByText('还没有任何报价商授权（0 家）')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '登记第一条授权' })).toBeInTheDocument();
    expect(screen.getByText(/默认关。关的时候只有"内部测试"Key/)).toBeInTheDocument();
  });

  it('授权检查关（默认，Amos 11/17）：顶部、闸 2、底部都说"只做记录"，不说"只显示、不开仓 / 外部 Key 拿不到"（T-5）', async () => {
    renderAs(<QuoteLicensePage />);
    expect(await screen.findByText(/授权检查已关闭（所有环境默认关，Amos 11\/17 决定）：没有登记也照常开仓/)).toBeInTheDocument();
    expect(screen.getByText('授权登记（报价商 × 品种，只做记录）')).toBeInTheDocument();
    expect(screen.getByText('授权检查：关（只登记）')).toBeInTheDocument();
    expect(screen.getByText(/三项（交易用途、登录后展示、转售）只做记录/)).toBeInTheDocument();
    expect(screen.getByText('没有交易用途授权的品种（只是记录：授权检查已关闭，这些品种照常开仓）')).toBeInTheDocument();
    expect(screen.queryByText(/外部 Key 拿不到任何品种/)).toBeNull();
    expect(screen.queryByText(/只显示、不开仓/)).toBeNull();
  });

  it('授权检查开（FX_QUOTE_LICENSE_ENFORCE=true）：照旧说"只显示、不开仓 / 外部 Key 拿不到"', async () => {
    setQuoteLicenseEnforcedForTest(true);
    renderAs(<QuoteLicensePage />);
    expect(await screen.findByText(/授权检查已打开：没有交易用途授权的品种只显示、不开仓；外部 Key 拿不到任何品种/)).toBeInTheDocument();
    expect(screen.getByText('闸 2：授权登记（报价商 × 品种）')).toBeInTheDocument();
    expect(screen.getByText('授权检查：开')).toBeInTheDocument();
    expect(screen.getByText(/没有交易用途授权的品种（授权检查打开时只显示、不开仓/)).toBeInTheDocument();
    expect(screen.queryByText(/只做记录/)).toBeNull();
  });

  it('打开总开关：要写原因、勾"Amos 已同意打开"，没勾不能开；超管打开后写审计', async () => {
    renderAs(<QuoteLicensePage />);
    fireEvent.click(await screen.findByRole('button', { name: '打开对外总开关' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('⚠ 需 Amos 同意（L3）')).toBeInTheDocument();
    fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: 'Amos 11/20 同意' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /确认打开/ }));
    expect(await within(dialog).findByText('没有 Amos 同意不能打开')).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('checkbox', { name: 'Amos 已同意打开' }));
    fireEvent.click(within(dialog).getByRole('button', { name: /确认打开/ }));
    await waitFor(() => expect(screen.getByRole('button', { name: '关闭对外总开关' })).toBeInTheDocument());
    expect(db.auditLogs[0].action).toBe('quote.gate_on');
  }, 60_000);

  it('不是超管：提示"只有超管能改"，接口也拒绝', async () => {
    db.session.adminId = 2;
    renderAs(<QuoteLicensePage />, { adminId: 2 });
    fireEvent.click(await screen.findByRole('button', { name: '打开对外总开关' }));
    expect(await screen.findByText('只有超管能改对外总开关，请用超管账号操作。')).toBeInTheDocument();
    await expect(setQuoteGate(true, true, 'x')).rejects.toMatchObject({ reasonKey: 'errors.quoteGateSuperOnly' });
  }, 60_000);

  it('快到期标"还剩 N 天 · 已发提醒邮件"，已到期标"已到期，按没授权处理"（授权检查开时）', async () => {
    setQuoteLicenseEnforcedForTest(true);
    seedQuoteLicensesForDemo();
    renderAs(<QuoteLicensePage />);
    expect(await screen.findByText('还剩 18 天 · 已发提醒邮件')).toBeInTheDocument();
    expect(screen.getByText('已到期，按没授权处理')).toBeInTheDocument();
    expect(screen.getByText(/2 条授权到期或快到期/)).toBeInTheDocument();
  });

  it('授权检查关时，已到期只标"已到期（授权检查关，只做记录）"', async () => {
    seedQuoteLicensesForDemo();
    renderAs(<QuoteLicensePage />);
    expect(await screen.findByText('已到期（授权检查关，只做记录）')).toBeInTheDocument();
    expect(screen.queryByText('已到期，按没授权处理')).toBeNull();
    expect(screen.getByText(/授权检查已关闭：到期只做提醒/)).toBeInTheDocument();
  });
});

describe('报价源状态（P11）', () => {
  it('偏差标黄"交易暂停开仓"、断流标红"暂无报价"；币安开关关', async () => {
    renderAs(<QuoteSourcesPage />);
    expect(await screen.findByText('偏差 > 0.5% · 交易暂停开仓')).toBeInTheDocument();
    expect(screen.getByText('断流 · 暂无报价')).toBeInTheDocument();
    expect(screen.getByText('开关关（Amos 决定后打开）')).toBeInTheDocument();
    expect(screen.getByText(/这些品种主备都没有报价：BTCUSD/)).toBeInTheDocument();
  });
});

describe('菜单和权限点', () => {
  it('一级菜单"报价服务"下 4 页，查看权限 quote_service:view；改 Key、限额、授权是高危权限点；总开关没有权限点（只有超管）', () => {
    const q = MENU.find((m) => m.key === 'quoteService')!;
    expect(q.children!.map((c) => c.path)).toEqual(['/quote-service/keys', '/quote-service/usage', '/quote-service/license', '/quote-service/sources']);
    for (const code of ['quote_key:operate', 'quote_limit:edit', 'quote_license:edit']) expect(PERMISSIONS.find((p) => p.code === code)!.highRisk).toBe(true);
    expect(PERMISSIONS.some((p) => p.code.includes('gate'))).toBe(false);
  });
});
