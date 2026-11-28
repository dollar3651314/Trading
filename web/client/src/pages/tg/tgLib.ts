/** TG 机器人第 2 批网页侧的小工具（纯函数，测试在 tg.test.tsx） */

/** 机器人用户名只认字母、数字、下划线（防止拼进网址时出问题） */
export function botLink(username: string | null | undefined): string | null {
  return username && /^[A-Za-z][A-Za-z0-9_]{3,31}$/.test(username) ? `https://t.me/${username}` : null;
}

/**
 * 机器人能直达的网页（和机器人 UpdateHandler.PAGES 一致）：深链接 ?start=open_<键> 只带这几个键
 * （Telegram 的 start 参数只能是 [A-Za-z0-9_-]、最长 64）
 */
export const TG_PAGES = { trade: '/trade', markets: '/markets', positions: '/positions', assets: '/assets', withdraw: '/assets/withdraw' } as const;
export type TgPage = keyof typeof TG_PAGES;

/** 网页路径对应哪个键（不认识的为 null） */
export function tgPageOfPath(path: string): TgPage | null {
  const p = path.replace(/[?#].*$/, '');
  return (Object.keys(TG_PAGES) as TgPage[]).find((k) => TG_PAGES[k] === p) ?? null;
}

/** 校验来自网址参数的键 */
export function asTgPage(v: string | null | undefined): TgPage | null {
  return v && Object.prototype.hasOwnProperty.call(TG_PAGES, v) ? (v as TgPage) : null;
}

/**
 * 提示页"在 Telegram 打开"用的深链接（验收反馈 1c；11/28 改；验收修复 5 带上目标页）：
 * https://t.me/机器人?start=open_positions（没有目标页时 ?start=open，安全事件后 ?start=reauth[_页]）。
 * 机器人收到以后：要先重新授权的回「重新授权」提示；不用的发一整套新菜单，每个进网页的按钮都带 60 秒一次性令牌，目标页放第一个「进入持仓 ›」。
 * 聊天已经打开过时普通链接打开以后什么都不会发生，所以一定要带 start（Amos 11/28 在 iOS 上证实：已有聊天也会收到 /start open）
 */
export function botStartLink(username: string | null | undefined, payload: 'open' | 'reauth' = 'open', page: TgPage | null = null): string | null {
  const l = botLink(username);
  const p = asTgPage(page);
  return l ? `${l}?start=${payload}${p ? `_${p}` : ''}` : null;
}

/** 从 #t=令牌 里取令牌 */
export function tokenFromHash(hash: string): string | null {
  const m = /^#?(?:.*&)?t=([A-Za-z0-9_-]{20,100})(?:&|$)/.exec(hash);
  return m ? m[1] : null;
}
