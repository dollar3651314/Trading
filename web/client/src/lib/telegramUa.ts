/** User-Agent 里带 Telegram（验收反馈 A：安卓 Telegram 内置浏览器会带；iOS 不一定，所以只是会话种类之外的补充） */
export function isTelegramUa(ua: string = typeof navigator === 'undefined' ? '' : navigator.userAgent): boolean {
  return /Telegram/i.test(ua);
}
