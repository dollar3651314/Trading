/**
 * 键盘弹出时主按钮不被遮住（TG 机器人第 3 批，原型说明 UX-B2）。
 * 两种手机浏览器的表现不一样：
 * - iOS（含 Telegram 内置浏览器）：键盘盖在页面上，布局视口不变，只有 visualViewport 变矮——要把底部弹层抬高"键盘高度"；
 * - 安卓（多数）：整个窗口变矮（adjustResize），fixed 在底部的东西会跟着顶到键盘上面——要把底部导航、底部买卖栏藏起来，给表单腾地方。
 * 这里把算出来的值写在 <html> 上：--kb（键盘盖住了底部多少像素）、--vvh（可见区域高度），data-kb="1"（键盘开着）。样式在 layout.css 末尾。
 */
import { useEffect } from 'react';

export interface ViewportSample {
  innerHeight: number;
  /** visualViewport 的高度和顶部偏移；没有 visualViewport 的浏览器传 innerHeight 和 0 */
  vvHeight: number;
  vvOffsetTop: number;
  /** 这次会话里见过的最高窗口高度（安卓键盘弹出时窗口变矮，靠它判断） */
  maxInnerHeight: number;
  /** 焦点在输入框里吗 */
  editing: boolean;
}

/** 键盘至少这么高才算弹出（地址栏收起、展开只差几十像素） */
export const KEYBOARD_MIN = 120;

export function keyboardState(v: ViewportSample): { inset: number; open: boolean; visible: number } {
  const inset = Math.max(0, Math.round(v.innerHeight - v.vvHeight - v.vvOffsetTop));
  const shrunk = v.maxInnerHeight - v.innerHeight;
  const open = v.editing && (inset >= KEYBOARD_MIN || shrunk >= KEYBOARD_MIN);
  return { inset: open ? inset : 0, open, visible: Math.round(v.vvHeight) };
}

function isEditable(el: Element | null): boolean {
  if (!el) return false;
  if (el instanceof HTMLTextAreaElement) return true;
  if (el instanceof HTMLInputElement) return !['button', 'checkbox', 'radio', 'submit', 'reset', 'file', 'range', 'color'].includes(el.type);
  return (el as HTMLElement).isContentEditable === true;
}

/**
 * 装在应用最外层，跟着窗口、visualViewport、焦点变化更新。
 * 边界情况（TG 机器人第 3 批审查）：屏幕方向变了，记录的最高窗口高度重置成当前高度（横竖屏高度本来就不同，不能当成键盘）；
 * 弹层关闭、输入框被移除时浏览器不一定发 focusout——监听 DOM 变化主动重新判断；卸载时清掉 data-kb 和变量，底部导航不会一直藏着。
 */
export function useKeyboardInset(): void {
  useEffect(() => {
    let maxH = window.innerHeight;
    const root = document.documentElement;
    const update = () => {
      const vv = window.visualViewport;
      if (!document.activeElement || !isEditable(document.activeElement)) maxH = Math.max(maxH, window.innerHeight);
      const s = keyboardState({
        innerHeight: window.innerHeight,
        vvHeight: vv ? vv.height : window.innerHeight,
        vvOffsetTop: vv ? vv.offsetTop : 0,
        maxInnerHeight: maxH,
        editing: isEditable(document.activeElement),
      });
      root.style.setProperty('--kb', `${s.inset}px`);
      root.style.setProperty('--vvh', `${s.visible}px`);
      if (s.open) root.dataset.kb = '1';
      else delete root.dataset.kb;
    };
    const onOrientation = () => {
      maxH = window.innerHeight;
      update();
    };
    update();
    const vv = window.visualViewport;
    vv?.addEventListener('resize', update);
    vv?.addEventListener('scroll', update);
    window.addEventListener('resize', update);
    window.addEventListener('orientationchange', onOrientation);
    screen.orientation?.addEventListener?.('change', onOrientation);
    document.addEventListener('focusin', update);
    // 失焦后键盘收起有动画：稍等再算
    const onOut = () => window.setTimeout(update, 50);
    document.addEventListener('focusout', onOut);
    // 输入框所在的弹层被关掉（节点被移除）时不一定有 focusout：DOM 有增删就在下一帧重新判断（只在键盘开着时才需要）
    let pending = false;
    const mo = new MutationObserver(() => {
      if (pending || root.dataset.kb !== '1') return;
      pending = true;
      window.setTimeout(() => {
        pending = false;
        update();
      }, 0);
    });
    mo.observe(document.body, { childList: true, subtree: true });
    return () => {
      vv?.removeEventListener('resize', update);
      vv?.removeEventListener('scroll', update);
      window.removeEventListener('resize', update);
      window.removeEventListener('orientationchange', onOrientation);
      screen.orientation?.removeEventListener?.('change', onOrientation);
      document.removeEventListener('focusin', update);
      document.removeEventListener('focusout', onOut);
      mo.disconnect();
      delete root.dataset.kb;
      root.style.removeProperty('--kb');
      root.style.removeProperty('--vvh');
    };
  }, []);
}
