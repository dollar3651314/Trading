/**
 * 生产只开加密货币（Amos 11/19，生产方案 v1 07 页 R-1）：公开行情接口只返回 10 个币安品种（生产 FX_PUBLIC_QUOTES_SYMBOLS，停用的品种不返回），
 * 行情面板只列这 10 个、只显示有品种的分类标签（热门、加密货币），8 个新加的币用短名称。
 */
import { render, screen, within } from '@testing-library/react';
import App from './App';

const hold = vi.hoisted(() => ({ pending: false }));
const CRYPTO = vi.hoisted(() => ['BTCUSD', 'ETHUSD', 'SOLUSD', 'XRPUSD', 'BNBUSD', 'DOGEUSD', 'ADAUSD', 'LTCUSD', 'DOTUSD', 'BCHUSD']);

vi.mock('./api/quotes', async (orig) => {
  const m = await orig<typeof import('./api/quotes')>();
  const items = CRYPTO.map((symbol) => ({ symbol, nameZh: `${symbol} 全称`, nameEn: symbol, bid: null, changePct: null, time: null, status: 'OPEN' as const }));
  return { ...m, fetchPublicQuotes: () => (hold.pending ? new Promise<never>(() => undefined) : Promise.resolve({ sample: false, pricesHidden: true, items })) };
});

beforeEach(() => {
  hold.pending = false;
  localStorage.clear();
  window.history.replaceState(null, '', '/');
});

describe('官网行情区：生产只开加密货币（R-1）', () => {
  it('只列接口返回的 10 个加密货币，分类标签只有"热门"和"加密货币"', async () => {
    render(<App initialLang="zh-CN" />);
    const board = await screen.findByTestId('quote-board');
    await within(board).findAllByText('登录后查看实时行情');
    const rows = within(board).getAllByTestId('quote-row');
    expect(rows.map((r) => r.getAttribute('data-symbol'))).toEqual(CRYPTO);
    expect(within(board).getAllByRole('tab').map((t) => t.textContent)).toEqual(['热门', '加密货币']);
    expect(within(rows[7]).getByText('莱特币')).toBeInTheDocument();
  });

  it('第一次拿到行情之前显示"加载中"，不先摆一份写死的外汇、黄金占位', async () => {
    hold.pending = true;
    render(<App initialLang="zh-CN" />);
    const board = await screen.findByTestId('quote-board');
    expect(within(board).getByTestId('board-loading')).toHaveTextContent('加载中');
    expect(within(board).queryAllByTestId('quote-row')).toHaveLength(0);
    expect(board.textContent).not.toMatch(/XAUUSD|EURUSD/);
  });
});
