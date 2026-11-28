/**
 * 是不是在 Telegram 的内置浏览器里（11/28 Amos 定方案 B：客户端网页只在 Telegram 内置浏览器里能用，所有平台）。
 * 判断交给开源库 inapp-spy（MIT，锁定 5.0.10，C160）：它看 window 上的 Telegram 标记——安卓 TelegramWebview，
 * iOS TelegramWebviewProxy / TelegramWebviewProxyProto（Amos 的 iOS 18.7 真机上证实有后两个；iOS 的 UA 和 Safari 一样，看 UA 认不出）。
 *
 * 为什么传一个中性的 ua：inapp-spy 先看 UA，UA 命中别的规则（例如安卓 WebView 的 "wv)"）就直接返回，不再看 Telegram 标记，
 * appKey 不是 telegram。传一个什么规则都不命中的 ua，它就只做 Telegram 的标记检查（库的 index.ts：UA 没命中才走 getDetectClientSide）。
 * 以后要换判断方法只改这一个函数。这是体验上的门槛，不是安全措施（标记能伪造）。
 */
import InAppSpy from 'inapp-spy';

/** 不命中 inapp-spy 任何 UA 规则的占位 UA（测试里有检查） */
export const NEUTRAL_UA = 'trading-telegram-check';

export function isTelegramInApp(): boolean {
  if (typeof window === 'undefined') return false;
  try {
    return InAppSpy({ ua: NEUTRAL_UA }).appKey === 'telegram';
  } catch {
    return false;
  }
}

export type PlatformFamily = 'IOS' | 'ANDROID' | 'OTHER';

/** 粗分平台（只用来选引导步骤和匿名计数，不上报 UA）：iPhone / iPad / iPod（含 iPadOS 桌面 UA 带触屏）、安卓（含鸿蒙的安卓 UA）、其他 */
export function platformFamily(ua: string = typeof navigator === 'undefined' ? '' : navigator.userAgent, touch = typeof document !== 'undefined' && 'ontouchend' in document): PlatformFamily {
  if (/iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && touch)) return 'IOS';
  if (/Android|HarmonyOS|OpenHarmony/i.test(ua)) return 'ANDROID';
  return 'OTHER';
}
