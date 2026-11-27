/**
 * 安全事件以后（第 4 批：设置 / 修改 / 重设提现密码、TG 客户第一次提交 KYC）后端已作废这个账户的全部网页会话（含 BOT 会话）：
 * 清掉本地会话，去提示页 /tg?r=security，写明"回到机器人点「重新授权」，再点「打开交易」"，不让页面在下一次请求 401 时莫名其妙跳回登录页。
 */
import { useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from './contexts';

export function useSecurityRelogin(): () => void {
  const navigate = useNavigate();
  const { setSession } = useAuth();
  return useCallback(() => {
    navigate('/tg?r=security', { replace: true });
    setSession(null);
  }, [navigate, setSession]);
}
