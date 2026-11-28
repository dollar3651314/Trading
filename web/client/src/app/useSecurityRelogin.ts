/**
 * 安全事件以后（第 4 批：设置 / 修改 / 重设提现密码、TG 客户第一次提交 KYC）后端已作废这个账户的全部网页会话（含 BOT 会话）：
 * 清掉本地会话，去提示页 /tg?r=security，写明"回到机器人点「重新授权」，再点「打开交易」"。
 * 验收反馈 1b：以前先 navigate 再清会话。路由开了 v7_startTransition，跳转是"过渡"更新、清会话是普通更新，后者先生效，
 * 当前页的路由守卫看到没会话就先跳去 /login（只走 Telegram 时显示普通的"请在 Telegram 打开"），覆盖了 /tg?r=security。
 * 现在清会话时同时标记"因为安全事件"，守卫自己去 /tg?r=security；后端回 401 SECURITY_LOGOUT 时也一样（见 AuthProvider）
 */
import { useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from './contexts';

export function useSecurityRelogin(): () => void {
  const navigate = useNavigate();
  const { endForSecurity } = useAuth();
  return useCallback(() => {
    endForSecurity();
    navigate('/tg?r=security', { replace: true });
  }, [navigate, endForSecurity]);
}
