/**
 * 第 3 批客户端交易页（原型 P3，Amos 11/17 确认）：
 * - 没有"交易用途"授权的品种（tradable=false）：行情照常，标"暂不开放交易"、下单区写"该品种暂不开放交易"、按钮灰、"不会出现在你的持仓里"；
 * - 报价服务中断（推送 quoteStatus false）：下单区"暂无报价"、按钮灰；恢复后自动恢复，不用刷新；
 * - 拒单 SYMBOL_NOT_TRADABLE 的文案是"该品种暂不开放交易"。
 */
import { act, render, screen } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import i18n from '../i18n';
import { SYMBOL_SEEDS } from '../mocks/data/symbols';
import { setQuoteAvailable } from '../mocks/market';
import { renderPage } from '../test/renderApp';
import { fromServer, errorKey } from '../api/errors';
import { OrderPanel } from './OrderPanel';
import { StatusTags } from './SymbolList';

const xau = SYMBOL_SEEDS.find((s) => s.info.code === 'XAUUSD')!.info;

afterEach(() => act(() => setQuoteAvailable(true)));

describe('没有交易用途授权的品种（P3-2）', () => {
  it('列表标"暂不开放交易"', () => {
    render(
      <I18nextProvider i18n={i18n}>
        <StatusTags s={{ ...xau, tradable: false }} />
      </I18nextProvider>,
    );
    expect(screen.getByText('暂不开放交易')).toBeInTheDocument();
  });

  it('下单区写"该品种暂不开放交易"、提交按钮灰、写"不会出现在你的持仓里"', async () => {
    renderPage(<OrderPanel symbol={{ ...xau, tradable: false }} inline />, '/trade/XAUUSD');
    expect(await screen.findByText(/该品种暂不开放交易：可以看行情和 K 线，暂时不能下单/)).toBeInTheDocument();
    expect(screen.getByText('XAUUSD 不会出现在你的持仓里')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /买入.*XAUUSD|XAUUSD.*买入/ })).toBeDisabled();
  });

  it('有授权（或没有这个字段的老接口）照常可以下单', async () => {
    renderPage(<OrderPanel symbol={xau} inline />, '/trade/XAUUSD');
    expect(await screen.findByRole('button', { name: /买入.*XAUUSD|XAUUSD.*买入/ })).toBeEnabled();
    expect(screen.queryByText(/暂不开放交易/)).not.toBeInTheDocument();
  });

  it('拒单 SYMBOL_NOT_TRADABLE 显示"该品种暂不开放交易"', () => {
    expect(i18n.t(errorKey(fromServer(400, { code: 'SYMBOL_NOT_TRADABLE', message: 'x' })))).toBe('该品种暂不开放交易');
  });
});

describe('报价服务中断（P3-3）', () => {
  it('推 quoteStatus false：下单区"暂无报价"、按钮灰；恢复后自动恢复', async () => {
    renderPage(<OrderPanel symbol={xau} inline />, '/trade/XAUUSD');
    const buy = await screen.findByRole('button', { name: /买入.*XAUUSD|XAUUSD.*买入/ });
    act(() => setQuoteAvailable(false));
    expect(await screen.findByText(/暂无报价：报价暂时中断，暂不能开新仓/)).toBeInTheDocument();
    expect(buy).toBeDisabled();
    act(() => setQuoteAvailable(true));
    expect(screen.queryByText(/暂无报价/)).not.toBeInTheDocument();
    expect(buy).toBeEnabled();
  });
});
