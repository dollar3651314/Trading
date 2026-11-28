/**
 * 方案 B 门禁（11/28 Amos：「选择B，等正式跑一段时间，如果反馈的客户特别多，再调整」）：客户端网页只在 Telegram 内置浏览器里能用，所有平台。
 * 规则：后端说"只走 Telegram"（telegramOnly，FX_PHASE1_TELEGRAM_ONLY）、应急开关关着（emergencyLogin=false）、又不在 Telegram 内置浏览器里
 * （{@link isTelegramInApp}，inapp-spy）时，只显示引导页 {@link TgGuide}，不渲染任何应用界面；一次性链接落地页 /tg-link 也就不会去换会话、不消耗令牌
 * （令牌顺手从地址栏去掉）。不在门禁里的页面见 App.tsx：/tg/freeze、/tg/recover、/tg-check；应急开关开着时整个门禁不起作用
 * （机器人可能被封，应急登录要在任意浏览器里能用）。后台、官网是别的站点，不受影响。
 * 读不到登录方式（后端不通）时不拦，和"只走 Telegram"的提示页一致。这是体验上的门槛，不是安全措施。
 */
import { useEffect, useState, type ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { getAuthOptions } from '../../api/auth';
import { Loading } from '../../components/AsyncView';
import { isTelegramInApp } from '../../lib/telegramInApp';
import TgGuide from './TgGuide';

export function TelegramGate({ children }: { children: ReactNode }) {
  const [block, setBlock] = useState<boolean | null>(null);
  const location = useLocation();
  useEffect(() => {
    let alive = true;
    getAuthOptions().then(
      (o) => alive && setBlock(o.telegramOnly && !o.emergencyLogin && !isTelegramInApp()),
      () => alive && setBlock(false),
    );
    return () => {
      alive = false;
    };
  }, []);
  const blocked = block === true;
  const onLink = location.pathname === '/tg-link';
  useEffect(() => {
    // 拦下的一次性链接：不换会话，令牌也别留在地址栏（截图、转发时会带出去）
    if (blocked && onLink && window.location.hash) window.history.replaceState(null, '', window.location.pathname + window.location.search);
  }, [blocked, onLink]);
  if (block === null) return <Loading rows={4} />;
  return blocked ? <TgGuide /> : <>{children}</>;
}
