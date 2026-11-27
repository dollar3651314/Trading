/** TG 机器人第 2 批网页侧的小工具（纯函数，测试在 tg.test.tsx） */

/** 机器人用户名只认字母、数字、下划线（防止拼进网址时出问题） */
export function botLink(username: string | null | undefined): string | null {
  return username && /^[A-Za-z][A-Za-z0-9_]{3,31}$/.test(username) ? `https://t.me/${username}` : null;
}

/** 从 #t=令牌 里取令牌 */
export function tokenFromHash(hash: string): string | null {
  const m = /^#?(?:.*&)?t=([A-Za-z0-9_-]{20,100})(?:&|$)/.exec(hash);
  return m ? m[1] : null;
}
