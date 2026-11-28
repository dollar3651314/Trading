/** 认证的真实接口：`/api/auth/*`、`/api/me/session`（`account/auth/AuthController.java`） */
import { ApiError, validCooldown } from '../errors';
import { get, post } from '../http';
import type { AuthOptions, EmailCodePurpose, RegisterRequest, Session, TgLoginReason } from '../types';

/** 后端 SessionView：机器人开户的客户（纯 Telegram）email 为 null；有 TG 账户的带 telegramUsername（TG 机器人第 1 批） */
interface SessionView {
  userId: string;
  email: string | null;
  telegramUsername?: string | null;
  fromTelegram?: boolean;
}

const toSession = (v: SessionView): Session => ({
  userId: v.userId,
  email: v.email ?? null,
  ...(v.telegramUsername ? { telegramUsername: v.telegramUsername } : {}),
  ...(v.fromTelegram ? { fromTelegram: true } : {}),
});

/** 后端的验证码用途是枚举 REGISTER / RESET_PASSWORD */
const PURPOSE: Record<EmailCodePurpose, string> = { register: 'REGISTER', resetPassword: 'RESET_PASSWORD' };

/** 旧版后端（返回 204、没有响应体）时的重发间隔，和后端默认的 `trading.auth.code-resend-seconds` 一致 */
const DEFAULT_RESEND_SECONDS = 60;

export async function login(email: string, password: string): Promise<Session> {
  return toSession(await post<SessionView>('/api/auth/login', { email, password }, { quiet401: true }));
}

export async function register(req: RegisterRequest): Promise<Session> {
  return toSession(await post<SessionView>('/api/auth/register', req, { codeFields: { CODE_INVALID: 'code', EMAIL_TAKEN: 'email' } }));
}

/**
 * 发验证码：后端返回 200 `{"cooldownSeconds":60}`，按它倒计时；兼容旧版的 204（没有响应体）按 60 秒。
 * 60 秒内重发是 429 `CODE_TOO_FREQUENT`，ApiError.cooldownSeconds 是还要等的秒数。
 */
export async function sendEmailCode(email: string, purpose: EmailCodePurpose): Promise<{ cooldownSeconds: number }> {
  const r = await post<{ cooldownSeconds?: number } | undefined>('/api/auth/email-code', { email, purpose: PURPOSE[purpose] });
  return { cooldownSeconds: validCooldown(r?.cooldownSeconds) ?? DEFAULT_RESEND_SECONDS };
}

export async function resetPassword(email: string, code: string, password: string): Promise<void> {
  await post<void>('/api/auth/password/reset', { email, code, password }, { codeFields: { CODE_INVALID: 'code' } });
}

export async function changePassword(current: string, password: string): Promise<void> {
  await post<void>(
    '/api/auth/password/change',
    { currentPassword: current, newPassword: password },
    { fieldNames: { currentPassword: 'current', newPassword: 'password' }, codeFields: { CURRENT_PASSWORD_WRONG: 'current' } },
  );
}

export async function logout(): Promise<void> {
  await post<void>('/api/auth/logout', undefined, { quiet401: true });
}

/** 打开页面时问后端"现在是谁"：Cookie 有效返回会话，没登录返回 null */
export async function restoreSession(): Promise<Session | null> {
  try {
    return toSession(await get<SessionView>('/api/me/session', { quiet401: true }));
  } catch (e) {
    if (e instanceof ApiError && e.reason === 'unauthorized') return null;
    // 其他（包括验收反馈 1b 的 securityLogout：会话因为安全事件作废了）交给 AuthProvider
    throw e;
  }
}

/** 登录方式（公开接口）：第一阶段只走 Telegram、机器人用户名 */
export const getAuthOptions = (): Promise<AuthOptions> => get<AuthOptions>('/api/auth/options', { quiet401: true });

const TG_REASONS: TgLoginReason[] = ['refused', 'invalid', 'expired', 'used', 'no_account', 'frozen', 'reauth', 'disabled'];

/**
 * 一次性链接换会话（退路模式，TG 机器人第 2 批）：令牌从网址 # 后面读出来，POST 给后端（GET 打开页面不消耗令牌）。
 * 成功返回登录后要打开的页面；失败返回原因（后端错误码 TG_LOGIN_<原因>），网络错误抛出。
 */
export async function tgLink(token: string): Promise<{ next: string } | { reason: TgLoginReason }> {
  const res = await fetch('/api/tg/link', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ token }),
  });
  let body: { next?: string; code?: string } = {};
  try {
    body = JSON.parse(await res.text()) as typeof body;
  } catch {
    /* 不是 JSON：按失败处理 */
  }
  if (res.ok && typeof body.next === 'string') return { next: body.next };
  const r = (body.code ?? '').replace(/^TG_LOGIN_/, '').toLowerCase() as TgLoginReason;
  return { reason: TG_REASONS.includes(r) ? r : 'invalid' };
}

/** 应急开关下用 Telegram 登录（第 5 批）：成功返回什么时候能提现（UTC ISO）；失败返回后端错误码去掉 TG_EMERGENCY_ 前缀（小写），网络错误抛出 */
export async function tgEmergencyLogin(user: Record<string, string | number>): Promise<{ withdrawAt: string } | { reason: string }> {
  const res = await fetch('/api/tg/emergency-login', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(user),
  });
  let body: { withdrawAt?: string; code?: string } = {};
  try {
    body = JSON.parse(await res.text()) as typeof body;
  } catch {
    /* 不是 JSON：按失败处理 */
  }
  if (res.ok && typeof body.withdrawAt === 'string') return { withdrawAt: body.withdrawAt };
  return { reason: (body.code ?? '').replace(/^TG_EMERGENCY_/, '').toLowerCase() || 'invalid' };
}
