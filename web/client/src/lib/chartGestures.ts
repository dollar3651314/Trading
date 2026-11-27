import { TrackingModeExitMode } from 'lightweight-charts';

/**
 * 手势（TG 机器人第 3 批，照原型 trade-ui）：
 * - 手指左右拖动平移（带惯性）；上下拖动交给页面滚动（不然手机上手指放在图上就滚不动页面）；
 * - 双指缩放、电脑滚轮缩放、拖价格轴缩放；
 * - 长按进入十字光标（显示开高低收），再点一下退出。
 */
export const CHART_GESTURES = {
  handleScroll: { mouseWheel: true, pressedMouseMove: true, horzTouchDrag: true, vertTouchDrag: false },
  handleScale: { mouseWheel: true, pinch: true, axisPressedMouseMove: { time: true, price: true }, axisDoubleClickReset: { time: true, price: true } },
  kineticScroll: { touch: true, mouse: false },
  trackingMode: { exitMode: TrackingModeExitMode.OnNextTap },
} as const;
