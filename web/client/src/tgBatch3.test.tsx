/**
 * TG 机器人第 3 批：手机交易界面（照原型 trade-ui，验收标准 UX-B1～B4）。
 * 这里测逻辑和结构；实际的样子（375、390、1280 宽，内容要滚动、键盘弹出）用 Playwright 截图检查，见 PR 说明。
 */
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { vi } from 'vitest';
import { TrackingModeExitMode } from 'lightweight-charts';
import { OrderSheet } from './components/OrderSheet';
import { SymbolList } from './components/SymbolList';
import { KEYBOARD_MIN, keyboardState, useKeyboardInset } from './hooks/useKeyboardInset';
import { OrderPanel } from './components/OrderPanel';
import { ClosePositionDialog } from './components/PositionDialogs';
import { db } from './mocks/db';
import { symbolInfo } from './mocks/market';
import { CHART_GESTURES } from './lib/chartGestures';
import { SYMBOL_SEEDS } from './mocks/data/symbols';
import { renderPage } from './test/renderApp';

const btcSeed = SYMBOL_SEEDS.find((s) => s.info.code === 'BTCUSD')!.info;
/** 可以下单的状态（原型数据里 BTCUSD 是"暂停开仓"） */
const btc = { ...btcSeed, openPaused: false, tradingOpen: true, tradable: true };

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

describe('暂停开仓、休市时不能下单（C140），平仓照常', () => {
  for (const [name, patch, text] of [
    ['暂停开仓', { openPaused: true }, '暂停开仓'],
    ['休市', { tradingOpen: false }, '休市中'],
  ] as const) {
    it(`${name}：手机下单抽屉、电脑下单面板的按钮写"${text}"，点不了`, async () => {
      const sym = { ...btc, ...patch };
      const { unmount } = renderPage(<OrderSheet symbol={sym} side="buy" onClose={() => undefined} />, '/trade/BTCUSD');
      const b1 = within(await screen.findByTestId('os-sticky')).getByRole('button');
      expect(b1).toHaveTextContent(text);
      expect(b1).toBeDisabled();
      unmount();
      renderPage(<OrderPanel symbol={sym} inline />, '/trade/BTCUSD');
      const b2 = await screen.findByRole('button', { name: text });
      expect(b2).toBeDisabled();
      fireEvent.click(b2);
      expect(screen.queryByRole('dialog')).toBeNull();
    });
  }

  it('暂停开仓的品种照样能平仓', async () => {
    const p = db.positions.find((x) => symbolInfo(x.symbol)?.tradingOpen && Number(x.lots) >= 0.5)!;
    const onClose = vi.fn();
    renderPage(<ClosePositionDialog position={{ ...p }} symbol={{ ...symbolInfo(p.symbol)!, openPaused: true }} onClose={onClose} />, '/positions');
    const dialog = await screen.findByRole('dialog');
    const all = within(dialog).getAllByRole('button', { name: '全部平仓' });
    const confirm = all[all.length - 1];
    expect(confirm).toBeEnabled();
    fireEvent.click(confirm);
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(db.positions.find((x) => x.id === p.id)).toBeUndefined();
  });
});

describe('键盘检测的边界情况（审查）', () => {
  function Probe() {
    useKeyboardInset();
    return null;
  }
  const setH = (h: number) => Object.defineProperty(window, 'innerHeight', { configurable: true, value: h });
  const root = document.documentElement;

  afterEach(() => setH(768));

  it('安卓：输入时窗口变矮算键盘；输入框被移除（弹层关掉）后主动恢复，底部导航不会一直藏着', async () => {
    setH(844);
    const { unmount } = render(<Probe />);
    const input = document.createElement('input');
    document.body.appendChild(input);
    act(() => input.focus());
    act(() => {
      setH(500);
      window.dispatchEvent(new Event('resize'));
    });
    expect(root.dataset.kb).toBe('1');
    act(() => {
      setH(844);
      input.remove();
    });
    await waitFor(() => expect(root.dataset.kb).toBeUndefined());
    unmount();
  });

  it('屏幕方向变了：最高窗口高度重置，横屏变矮不当成键盘', () => {
    setH(844);
    const { unmount } = render(<Probe />);
    const input = document.createElement('input');
    document.body.appendChild(input);
    act(() => input.focus());
    act(() => {
      setH(390);
      window.dispatchEvent(new Event('orientationchange'));
    });
    expect(root.dataset.kb).toBeUndefined();
    input.remove();
    unmount();
  });

  it('卸载时清掉 data-kb 和变量', () => {
    setH(844);
    const { unmount } = render(<Probe />);
    const input = document.createElement('input');
    document.body.appendChild(input);
    act(() => input.focus());
    act(() => {
      setH(500);
      window.dispatchEvent(new Event('resize'));
    });
    expect(root.dataset.kb).toBe('1');
    unmount();
    expect(root.dataset.kb).toBeUndefined();
    expect(root.style.getPropertyValue('--kb')).toBe('');
    input.remove();
  });
});
