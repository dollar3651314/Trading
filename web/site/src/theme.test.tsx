/** TY-7（Amos 11/17：三端都默认浅色，客户自己可以切深色）：官网默认浅色，右上角切深色，记在本机，再打开还是深色 */
import { fireEvent, render, screen } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { ThemeSwitch } from './components/Chrome';
import { LangProvider } from './i18n/I18nProvider';
import { readTheme, THEME_KEY } from './lib/theme';

afterEach(() => {
  localStorage.clear();
  document.documentElement.removeAttribute('data-theme');
});

describe('官网深浅色（TY-7）', () => {
  it('默认浅色；点一下切深色并记住，再点切回浅色', () => {
    expect(readTheme()).toBe('light');
    render(
      <LangProvider>
        <ThemeSwitch />
      </LangProvider>,
    );
    expect(document.documentElement.getAttribute('data-theme')).toBeNull();
    fireEvent.click(screen.getByTestId('theme-switch'));
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(localStorage.getItem(THEME_KEY)).toBe('dark');
    expect(readTheme()).toBe('dark');
    fireEvent.click(screen.getByTestId('theme-switch'));
    expect(document.documentElement.getAttribute('data-theme')).toBeNull();
    expect(readTheme()).toBe('light');
  });

  it('样式：默认（:root）是浅色，深色在 data-theme="dark" 下', () => {
    const css = readFileSync(join(__dirname, 'styles/tokens.css'), 'utf8');
    expect(css).toMatch(/:root \{[^}]*--bg: #f4f7fb;[^}]*color-scheme: light;/);
    expect(css).toMatch(/:root\[data-theme='dark'\] \{[^}]*--bg: #0d1117;[^}]*color-scheme: dark;/);
  });
});
