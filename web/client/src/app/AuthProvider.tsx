/**
 * 登录状态。真实环境不保存令牌：打开页面时问后端（GET /api/me/session）是否已登录，检查期间 checking 为 true；
 * 任意接口返回 401 时清空会话，路由守卫回到登录页。原型从 localStorage 恢复，不需要检查。
 */
import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { getSession, logout as apiLogout, restoreSession, sessionChanged } from '../api/auth';
import { onUnauthorized } from '../api/http';
import { ApiError } from '../api/errors';
import { IS_MOCK } from '../api/mode';
import type { Session } from '../api/types';
import { AuthContext } from './contexts';

export function AuthProvider({ children }: { children: ReactNode }) {
  // undefined：还不知道（真实环境正在问后端）
  const [session, setSession] = useState<Session | null | undefined>(() => (IS_MOCK ? getSession() : undefined));
  const [securityLogout, setSecurityLogout] = useState(false);

  useEffect(() => {
    if (session !== undefined) return;
    let alive = true;
    restoreSession().then(
      (s) => alive && setSession((cur) => (cur === undefined ? s : cur)),
      (e: unknown) => {
        if (!alive) return;
        if (e instanceof ApiError && e.reason === 'securityLogout') setSecurityLogout(true);
        setSession((cur) => (cur === undefined ? null : cur));
      },
    );
    return () => {
      alive = false;
    };
    // 只在打开页面时检查一次
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 验收反馈 1b：401 SECURITY_LOGOUT（安全事件作废了会话）时标记，路由守卫去 /tg?r=security
  useEffect(
    () =>
      onUnauthorized((code) => {
        if (code === 'SECURITY_LOGOUT') setSecurityLogout(true);
        setSession(null);
      }),
    [setSession],
  );
  // 重新登录以后清掉标记
  useEffect(() => {
    if (session) setSecurityLogout(false);
  }, [session]);
  const endForSecurity = useCallback(() => {
    setSecurityLogout(true);
    setSession(null);
  }, [setSession]);

  const loggedIn = !!session;
  useEffect(() => {
    sessionChanged(loggedIn);
  }, [loggedIn]);

  const logout = useCallback(async () => {
    await apiLogout().catch(() => undefined);
    setSession(null);
  }, []);
  const value = useMemo(
    () => ({ session: session ?? null, checking: session === undefined, setSession, logout, securityLogout, endForSecurity }),
    [session, logout, setSession, securityLogout, endForSecurity],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
