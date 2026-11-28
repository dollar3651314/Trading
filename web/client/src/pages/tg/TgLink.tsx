/**
 * 一次性链接落地页（退路模式 FX_TG_LOGIN_MODE=link，TG 机器人第 2 批）：令牌在网址 # 后面（不进服务器日志、不随请求发出）。
 * 打开页面不消耗令牌：页面读出来后先把它从地址栏去掉，再用 POST 换会话，成功后跳到机器人按钮对应的页面；失败显示提示页。
 */
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { restoreSession, tgLink } from '../../api/auth';
import type { TgLoginReason } from '../../api/types';
import { useAuth } from '../../app/contexts';
import { Loading } from '../../components/AsyncView';
import TgHint from './TgHint';
import { tokenFromHash } from './tgLib';

export default function TgLink() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { setSession } = useAuth();
  const [failed, setFailed] = useState<TgLoginReason | null>(null);
  const started = useRef(false);
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    const token = tokenFromHash(window.location.hash);
    // 先从地址栏和历史记录里去掉令牌
    window.history.replaceState(null, '', window.location.pathname);
    if (!token) {
      setFailed('invalid');
      return;
    }
    tgLink(token)
      .then(async (r) => {
        if ('reason' in r) {
          // 11/28：链接过期 / 用过了，但这个浏览器里本来就有有效会话（7 天内用过）——直接进去，不让客户感知"重新登录"
          const s = await restoreSession().catch(() => null);
          if (s) {
            setSession(s);
            navigate('/', { replace: true });
            return;
          }
          setFailed(r.reason);
          return;
        }
        setSession(await restoreSession());
        navigate(r.next || '/', { replace: true });
      })
      .catch(() => setFailed('invalid'));
  }, [navigate, setSession]);
  if (failed) return <TgHint reason={failed} />;
  return (
    <div className="tg-link-wait" role="status" aria-label={t('tg.entering')}>
      <Loading rows={3} />
    </div>
  );
}
