/**
 * 应急开关下的「用 Telegram 登录」（TG 机器人第 5 批，技术方案 11 页第 3 节，TB-34；原型 webview.html"演示：应急开关已打开"）：
 * 后端说 emergencyLogin 时，在登录页 / "请在 Telegram 打开"提示页上方显示应急提示和 Telegram 官方登录小部件（挂在只管登录的机器人上）。
 * 小部件把 Telegram 签过名的身份交给这里，原样 POST 给后端验签；成功后先告诉客户"24 小时后才能提现"，点「进入交易」再进去。
 * 开关关着、读不到登录方式时什么都不显示。
 */
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { getAuthOptions, restoreSession, tgEmergencyLogin } from '../../api/auth';
import type { TelegramWidgetUser } from '../../api/types';
import { useAuth } from '../../app/contexts';
import { formatDateTime } from '../../lib/format';

export const WIDGET_SRC = 'https://telegram.org/js/telegram-widget.js?22';
const CALLBACK = 'onTrEmergencyTelegramAuth';
const REASONS = ['disabled', 'invalid', 'expired', 'used', 'no_account', 'frozen'];

declare global {
  interface Window {
    [CALLBACK]?: (user: TelegramWidgetUser) => void;
  }
}

export function EmergencyLogin() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { setSession } = useAuth();
  const [bot, setBot] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [withdrawAt, setWithdrawAt] = useState<string | null>(null);
  const box = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let alive = true;
    getAuthOptions().then(
      (o) => {
        const u = o.loginBotUsername;
        if (alive && o.emergencyLogin && u && /^[A-Za-z][A-Za-z0-9_]{3,31}$/.test(u)) setBot(u);
      },
      () => undefined,
    );
    return () => {
      alive = false;
    };
  }, []);

  useEffect(() => {
    const el = box.current;
    if (!bot || !el || withdrawAt) return;
    window[CALLBACK] = (user) => {
      setBusy(true);
      setError(null);
      tgEmergencyLogin(user)
        .then((r) => {
          if ('reason' in r) setError(t(`tg.emergency.reason.${REASONS.includes(r.reason) ? r.reason : 'invalid'}`));
          else setWithdrawAt(r.withdrawAt);
        })
        .catch(() => setError(t('tg.emergency.reason.network')))
        .finally(() => setBusy(false));
    };
    const s = document.createElement('script');
    s.async = true;
    s.src = WIDGET_SRC;
    s.setAttribute('data-telegram-login', bot);
    s.setAttribute('data-size', 'large');
    s.setAttribute('data-radius', '8');
    s.setAttribute('data-onauth', `${CALLBACK}(user)`);
    el.replaceChildren(s);
    return () => {
      el.replaceChildren();
      delete window[CALLBACK];
    };
  }, [bot, withdrawAt, t]);

  if (!bot) return null;
  if (withdrawAt) {
    return (
      <div className="tg-emergency" data-testid="tg-emergency-done">
        <div className="notice warn" role="status">
          {t('tg.emergency.done', { time: formatDateTime(withdrawAt) })}
        </div>
        <button
          type="button"
          className="btn btn-primary btn-lg btn-block"
          data-testid="tg-emergency-enter"
          onClick={async () => {
            setSession(await restoreSession());
            navigate('/', { replace: true });
          }}
        >
          {t('tg.emergency.enter')}
        </button>
      </div>
    );
  }
  return (
    <div className="tg-emergency" data-testid="tg-emergency">
      <div className="notice warn" role="note">
        {t('tg.emergency.notice')}
      </div>
      <div className="tg-emergency-widget" ref={box} aria-label={t('tg.emergency.button')} aria-busy={busy} />
      {error && (
        <div className="notice error" role="alert" data-testid="tg-emergency-error">
          {error}
        </div>
      )}
    </div>
  );
}
