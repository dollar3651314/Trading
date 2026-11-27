/**
 * TG 机器人第 3 批：手机交易界面（照原型 trade-ui，验收标准 UX-B1～B4）。
 * 这里测逻辑和结构；实际的样子（375、390、1280 宽，内容要滚动、键盘弹出）用 Playwright 截图检查，见 PR 说明。
 */
import { screen, waitFor, within } from '@testing-library/react';
import { TrackingModeExitMode } from 'lightweight-charts';
import { OrderSheet } from './components/OrderSheet';
import { SymbolList } from './components/SymbolList';
import { KEYBOARD_MIN, keyboardState } from './hooks/useKeyboardInset';
import { CHART_GESTURES } from './lib/chartGestures';
import { SYMBOL_SEEDS } from './mocks/data/symbols';
import { renderPage } from './test/renderApp';

const btc = SYMBOL_SEEDS.find((s) => s.info.code === 'BTCUSD')!.info;

describe('键盘弹出时（UX-B2）', () => {
  const base = { innerHeight: 844, vvHeight: 844, vvOffsetTop: 0, maxInnerHeight: 844, editing: true };
  it('iOS：键盘盖在页面上（visualViewport 变矮），算出要抬高多少', () => {
    expect(keyboardState({ ...base, vvHeight: 508 })).toEqual({ inset: 336, open: true, visible: 508 });
  });
  it('安卓：窗口整体变矮（adjustResize），不用抬高，但要知道键盘开着（藏底部导航和买卖栏）', () => {
    expect(keyboardState({ ...base, innerHeight: 500, vvHeight: 500 })).toEqual({ inset: 0, open: true, visible: 500 });
  });
  it('没在输入、或者只是地址栏收起展开（差几十像素）不算键盘', () => {
    expect(keyboardState({ ...base, vvHeight: 508, editing: false }).open).toBe(false);
    expect(keyboardState({ ...base, vvHeight: 844 - KEYBOARD_MIN + 1 }).open).toBe(false);
  });
});

describe('K 线手势', () => {
  it('左右拖动平移（上下拖动留给页面滚动）、双指和滚轮缩放、长按十字光标、再点一下退出', () => {
    expect(CHART_GESTURES.handleScroll).toMatchObject({ horzTouchDrag: true, vertTouchDrag: false, pressedMouseMove: true, mouseWheel: true });
    expect(CHART_GESTURES.handleScale).toMatchObject({ pinch: true, mouseWheel: true });
    expect(CHART_GESTURES.kineticScroll.touch).toBe(true);
    expect(CHART_GESTURES.trackingMode.exitMode).toBe(TrackingModeExitMode.OnNextTap);
  });
});

describe('手机行情列表的走势小图', () => {
  it('trend 时每行一条今日走势小图；切换品种面板不加', async () => {
    const { unmount } = renderPage(<SymbolList onSelect={() => undefined} trend />, '/markets');
    await waitFor(() => expect(screen.getAllByTestId('sym-spark').length).toBeGreaterThan(3));
    await waitFor(() => expect(screen.getAllByTestId('sym-spark')[0].querySelector('svg.spark')).not.toBeNull());
    unmount();
    renderPage(<SymbolList onSelect={() => undefined} />, '/markets');
    await screen.findAllByRole('listitem');
    expect(screen.queryByTestId('sym-spark')).toBeNull();
  });
});

describe('手机下单抽屉（UX-B1）', () => {
  it('主按钮在弹层底部固定区里（不跟着内容滚走）', async () => {
    renderPage(<OrderSheet symbol={btc} side="buy" onClose={() => undefined} />, '/trade/BTCUSD');
    const sticky = await screen.findByTestId('os-sticky');
    expect(within(sticky).getByRole('button', { name: /买入.*BTCUSD|BTCUSD.*买入/ })).toBeInTheDocument();
  });
});
