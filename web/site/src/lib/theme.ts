/** 官网深浅色（TY-7：默认浅色，客户自己可以切深色）。选择记在本机 localStorage（读写出错时按浅色，页面照常显示）。 */
export type SiteTheme = 'light' | 'dark';
export const THEME_KEY = 'fx.site.theme';

export function readTheme(): SiteTheme {
  try {
    return globalThis.localStorage?.getItem(THEME_KEY) === 'dark' ? 'dark' : 'light';
  } catch {
    return 'light';
  }
}

export function applyTheme(t: SiteTheme): void {
  const root = document.documentElement;
  if (t === 'dark') root.setAttribute('data-theme', 'dark');
  else root.removeAttribute('data-theme');
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', t === 'dark' ? '#0d1117' : '#f4f7fb');
  try {
    globalThis.localStorage?.setItem(THEME_KEY, t);
  } catch {
    /* 存不了：只在这次打开的页面里生效 */
  }
}
