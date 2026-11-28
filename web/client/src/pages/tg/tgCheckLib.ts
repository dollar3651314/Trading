/** 诊断页 /tg-check 用的纯函数：浏览器这边能看到的信息（测试在 tgcheck.test.tsx） */

import { isTelegramInApp, platformFamily } from '../../lib/telegramInApp';

export type Row = [string, string];

/** 名字里带 telegram 的 window 属性（只要名字，不读值） */
function telegramNames(w: Window): string[] {
  const names = new Set<string>();
  for (const k of Object.getOwnPropertyNames(w)) if (/telegram/i.test(k)) names.add(k);
  for (const k in w) if (/telegram/i.test(k)) names.add(k);
  return [...names].sort();
}

function cookieWorks(d: Document): string {
  try {
    const k = 'fx_tgcheck=1';
    d.cookie = `${k}; path=/; max-age=60; SameSite=Lax`;
    const ok = d.cookie.includes(k);
    d.cookie = 'fx_tgcheck=; path=/; max-age=0; SameSite=Lax';
    return ok ? '能用' : '不能用';
  } catch (e) {
    return `出错：${String(e)}`;
  }
}

function storageWorks(w: Window): string {
  try {
    w.localStorage.setItem('fx.tgcheck', '1');
    const ok = w.localStorage.getItem('fx.tgcheck') === '1';
    w.localStorage.removeItem('fx.tgcheck');
    return ok ? '能用' : '不能用';
  } catch (e) {
    return `出错：${String(e)}`;
  }
}

// ---- 以下几段借鉴自 inapp-spy v5.0.10（MIT，Copyright (c) 2024 Shalanah Dawson，https://github.com/shalanah/inapp-spy）----
// 只摘了诊断要用的几个判断，逻辑和正则照原样，函数名改成我们的；没有加依赖（诊断页要逐项看信号，不要库的综合结论；
// 真要"只在 Telegram 里用"时再评估直接装这个库）。原文件：src/detectTelegram.ts、src/utils.ts（isSafariRegex）、
// src/regexInApp.ts（inAppRegExps）、src/detectSFSVC.ts（SchemaDataExtractor / MicrodataExtractor、clearAppBadge）

/** inapp-spy detectTelegram.ts：Telegram 内置浏览器的全局对象（安卓 TelegramWebview；iPhone TelegramWebviewProxy / TelegramWebviewProxyProto） */
export function isTelegramWebview(w: Window): boolean {
  return 'TelegramWebview' in w || 'TelegramWebviewProxy' in w || 'TelegramWebviewProxyProto' in w;
}

/** inapp-spy utils.ts isSafariRegex：iOS、iPadOS、macOS 上的 Safari（SFSafariViewController 的 UA 也一样） */
export const SAFARI_UA = /Mozilla\/5\.0 \([^)]+\) AppleWebKit\/[^\s]+ \(KHTML, like Gecko\) Version\/[^\s]+ (Mobile\/[^\s]+ )?Safari\/[^\s]+$/;

/** inapp-spy regexInApp.ts：按 UA 判断"内置浏览器"的通用规则（WebView 字样；苹果设备后面没有 Safari/；安卓 wv)） */
export const INAPP_UA = new RegExp(['WebView', '(iPhone|iPod|iPad)(?!.*Safari/)', 'Android.*wv\\)'].map((r) => `(${r})`).join('|'), 'i');

// ---- 借鉴结束 ----

/*
 * 上面借鉴部分的许可（inapp-spy 原文）：
 *
 * MIT License
 *
 * Copyright (c) 2024 Shalanah Dawson
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */


const show = (v: unknown): string => (v === undefined ? '（没有）' : v === null ? 'null' : v === '' ? '（空）' : typeof v === 'string' ? v : JSON.stringify(v));

/** 浏览器这边能看到的信息（纯函数，测试直接调） */
export function collectClientInfo(w: Window = window): Row[] {
  const n = w.navigator as Navigator & { userAgentData?: { brands?: { brand: string; version: string }[]; platform?: string; mobile?: boolean }; standalone?: boolean };
  const any = w as unknown as Record<string, unknown> & { webkit?: { messageHandlers?: object } };
  const uad = n.userAgentData;
  let handlers = '（没有）';
  try {
    const mh = any.webkit?.messageHandlers;
    if (mh) handlers = Object.keys(mh).join(', ') || '（有 messageHandlers，没有可列出的名字）';
  } catch {
    handlers = '（读不了）';
  }
  const mq = (q: string) => (typeof w.matchMedia === 'function' ? String(w.matchMedia(q).matches) : '（不支持）');
  const tg = telegramNames(w);
  // 用 in 判断（这些属性可能不可枚举，扫键名会漏）
  const has = (o: object, k: string): string => {
    try {
      return k in o ? '有' : '没有';
    } catch {
      return '（读不了）';
    }
  };
  const ua = n.userAgent ?? '';
  return [
    ['安卓 Telegram 标记："TelegramWebview" in window', has(w, 'TelegramWebview')],
    ['iOS Telegram 标记："TelegramWebviewProxy" in window', has(w, 'TelegramWebviewProxy')],
    ['iOS Telegram 标记："TelegramWebviewProxyProto" in window', has(w, 'TelegramWebviewProxyProto')],
    ['inapp-spy 的 Telegram 判断（三个标记任一个有）', isTelegramWebview(w) ? '是 Telegram' : '不是'],
    ['网页门禁用的判断 isTelegramInApp()（inapp-spy 5.0.10 依赖）', isTelegramInApp() ? '是 Telegram 内置浏览器' : '不是'],
    ['平台大类（引导步骤、匿名计数用）', platformFamily(ua)],
    ['UA 是 Safari 形状（inapp-spy isSafariRegex）', SAFARI_UA.test(ua) ? '是' : '不是'],
    ['UA 命中内置浏览器通用规则（inapp-spy inappRegex）', INAPP_UA.test(ua) ? '命中' : '没命中'],
    ['"SchemaDataExtractor" in window（有＝Safari 本体）', has(w, 'SchemaDataExtractor')],
    ['"MicrodataExtractor" in window（有＝Safari 本体）', has(w, 'MicrodataExtractor')],
    ['"clearAppBadge" in navigator', has(n, 'clearAppBadge')],
    ['UA 里有安卓 WebView 标记 "wv)"', /; wv\)/.test(ua) ? '有' : '没有'],
    ['navigator.userAgent', show(n.userAgent)],
    ['userAgentData.brands', uad ? show((uad.brands ?? []).map((b) => `${b.brand} ${b.version}`).join('; ')) : '（没有 userAgentData）'],
    ['userAgentData.platform', uad ? show(uad.platform) : '（没有）'],
    ['userAgentData.mobile', uad ? show(uad.mobile) : '（没有）'],
    ['navigator.standalone', show(n.standalone)],
    ['typeof TelegramWebviewProxy', typeof any.TelegramWebviewProxy],
    ['typeof Telegram', typeof any.Telegram],
    ['typeof TelegramWebview', typeof any.TelegramWebview],
    ['webkit.messageHandlers 名字', handlers],
    ['名字带 telegram 的 window 属性', tg.length ? tg.join(', ') : '（没有）'],
    ['document.referrer', show(w.document.referrer)],
    ['window.name', show(w.name)],
    ['屏幕 screen', `${w.screen?.width ?? '?'} × ${w.screen?.height ?? '?'}（像素比 ${w.devicePixelRatio ?? '?'}）`],
    ['视口 innerWidth × innerHeight', `${w.innerWidth} × ${w.innerHeight}`],
    ['display-mode: standalone', mq('(display-mode: standalone)')],
    ['Cookie', cookieWorks(w.document)],
    ['localStorage', storageWorks(w)],
  ];
}

