/**
 * 第 3 批（TD-9 A、TY-4，原型 P12，AC-Q10）：生产没有公开展示授权，公开行情接口标 pricesHidden、不给价格：
 * 官网标题"交易品种"，每行只列品种名和一个"登录后查看实时行情"（去登录），没有价格、涨跌、走势、闪动，也不标"示例行情"。
 * 测试环境（模拟价）照旧显示价格并标"示例行情"（App.test.tsx）。
 */
import { render, screen, within } from '@testing-library/react';
import App from './App';
import { parsePublicQuotes } from './api/quotes';

vi.mock('./api/quotes', async (orig) => {
  const m = await orig<typeof import('./api/quotes')>();
  const items = ['XAUUSD', 'EURUSD', 'BTCUSD'].map((symbol) => ({ symbol, nameZh: `${symbol} 中文名`, nameEn: symbol, bid: null, changePct: null, time: null, status: 'OPEN' as const }));
  return { ...m, fetchPublicQuotes: () => Promise.resolve({ sample: false, pricesHidden: true, items }) };
});

beforeEach(() => {
  localStorage.clear();
  window.history.replaceState(null, '', '/');
});

describe('官网行情区：生产不显示价格（P12）', () => {
  it('每行只有品种名和"登录后查看实时行情"（去登录），没有价格、涨跌、示例行情标签', async () => {
    render(<App initialLang="zh-CN" />);
    const board = await screen.findByTestId('quote-board');
    expect(await within(board).findAllByText('登录后查看实时行情')).not.toHaveLength(0);
    const rows = within(board).getAllByTestId('quote-row');
    for (const r of rows) {
      expect(within(r).getByTestId('login-to-view').getAttribute('href')).toBe('/login?lang=zh-CN');
      expect(within(r).queryByTestId('bid')).toBeNull();
      expect(within(r).queryByTestId('flash')).toBeNull();
    }
    expect(within(board).getByText('交易品种')).toBeInTheDocument();
    expect(within(board).queryByTestId('sample-tag')).toBeNull();
    expect(within(board).getByTestId('board-note')).toHaveTextContent('实时价格登录后在交易页查看。');
    expect(board.textContent).not.toMatch(/\d+\.\d{2}/);
  });

  it('接口带 pricesHidden 才隐藏；没有这个字段（测试环境、老接口）照旧', () => {
    expect(parsePublicQuotes({ sample: true, items: [] }).pricesHidden).toBe(false);
    expect(parsePublicQuotes({ sample: false, pricesHidden: true, items: [] }).pricesHidden).toBe(true);
  });
});
