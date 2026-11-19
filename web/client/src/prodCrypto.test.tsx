/**
 * 生产只开加密货币（Amos 11/19，生产方案 v1 07 页 R-1）：外汇、黄金等停用后接口不再返回它们。
 * - /trade 默认品种不能写死 XAUUSD：上次看的还启用就用它，否则用第一个启用的；"品种不存在"页的按钮也去第一个启用的
 * - 分类标签只显示有品种的（自选、全部总在）
 */
import { screen, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import Trade from './pages/Trade';
import { SymbolList } from './components/SymbolList';
import { renderPage } from './test/renderApp';

vi.mock('./components/KChart', () => ({ KChart: () => null }));

// 接口只返回启用的品种：这里只留加密货币（和生产一样）
vi.mock('./api/market', async (orig) => {
  const m = await orig<typeof import('./api/market')>();
  return { ...m, listSymbols: async () => (await m.listSymbols()).filter((s) => s.category === 'crypto') };
});

const trade = (path: string) =>
  renderPage(
    <Routes>
      <Route path="/trade" element={<Trade />} />
      <Route path="/trade/:symbol" element={<Trade />} />
    </Routes>,
    path,
  );

beforeEach(() => localStorage.clear());

describe('生产只开加密货币（R-1）', () => {
  it('/trade 没写品种：去第一个启用的品种，不是写死的 XAUUSD', async () => {
    trade('/trade');
    expect((await screen.findAllByText('BTCUSD')).length).toBeGreaterThan(0);
    expect(screen.queryByText(/找不到品种/)).toBeNull();
  });

  it('上次看的品种已经停用：不去它，去第一个启用的', async () => {
    localStorage.setItem('fx.trade.symbol', 'XAUUSD');
    trade('/trade');
    expect((await screen.findAllByText('BTCUSD')).length).toBeGreaterThan(0);
    expect(screen.queryByText(/找不到品种/)).toBeNull();
  });

  it('打开已停用的品种：提示找不到，按钮去第一个启用的品种', async () => {
    trade('/trade/XAUUSD');
    expect(await screen.findByText('找不到品种 XAUUSD')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'BTCUSD' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'XAUUSD' })).toBeNull();
  });

  it('分类标签只有自选、全部、加密', async () => {
    renderPage(<SymbolList onSelect={() => undefined} />, '/');
    await screen.findAllByText('BTCUSD');
    expect(within(screen.getByRole('tablist')).getAllByRole('tab').map((t) => t.textContent)).toEqual(['自选', '全部', '加密']);
  });
});
