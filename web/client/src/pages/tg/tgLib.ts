/** TG 机器人第 2 批网页侧的小工具（纯函数，测试在 tg.test.tsx） */

/** 机器人用户名只认字母、数字、下划线（防止拼进网址时出问题） */
export function botLink(username: string | null | undefined): string | null {
  return username && /^[A-Za-z][A-Za-z0-9_]{3,31}$/.test(username) ? `https://t.me/${username}` : null;
}

/**
 * 提示页"在 Telegram 打开"用的深链接（验收反馈 1c；11/28 改）：https://t.me/机器人?start=open（安全事件后 ?start=reauth）。
 * 机器人收到以后：要先重新授权的回「重新授权」提示；不用的发一整套新菜单，「打开交易」是 60 秒一次性链接「进入交易 ›」。
 * 聊天已经打开过时，普通链接打开以后什么都不会发生，客户会卡住
 */
export function botStartLink(username: string | null | undefined, payload: 'open' | 'reauth' = 'open'): string | null {
  const l = botLink(username);
  return l ? `${l}?start=${payload}` : null;
}

/** 从 #t=令牌 里取令牌 */
export function tokenFromHash(hash: string): string | null {
  const m = /^#?(?:.*&)?t=([A-Za-z0-9_-]{20,100})(?:&|$)/.exec(hash);
  return m ? m[1] : null;
}
