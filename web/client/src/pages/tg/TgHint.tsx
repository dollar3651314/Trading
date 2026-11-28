/**
 * "请在 Telegram 打开"提示页（TG 机器人第 2 批，技术方案 11 页第 2b 节，TB-33）：
 * 第一阶段只走 Telegram——没有会话时、点了注册 / 登录 / 忘记密码时、从机器人进来没成功时（/tg?r=原因）都显示这一页。
 * 机器人按钮 + 二维码；**保留「申请冻结提现」「找回 Telegram 账户」两个入口**（TG 被别人占着时是原主唯一的止损手段）。
 * 第 5 批：应急开关开着时上方多一块「用 Telegram 登录」（{@link EmergencyLogin}）。
 */
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import { getAuthOptions } from '../../api/auth';
import type { AuthOptions, TgLoginReason } from '../../api/types';
import { QrCode } from '../../components/QrCode';
import { AuthLayout } from '../auth/AuthLayout';
import { EmergencyLogin } from './EmergencyLogin';
import { botStartLink } from './tgLib';

const REASONS: TgLoginReason[] = ['refused', 'invalid', 'expired', 'used', 'no_account', 'frozen', 'reauth', 'disabled', 'security'];

export default function TgHint({ reason: reasonProp }: { reason?: TgLoginReason | null }) {
  const { t } = useTranslation();
  const [params] = useSearchParams();
  const [options, setOptions] = useState<AuthOptions | null>(null);
  useEffect(() => {
    let alive = true;
    getAuthOptions().then(
      (o) => alive && setOptions(o),
      () => undefined,
    );
    return () => {
      alive = false;
    };
  }, []);
  const r = reasonProp ?? (params.get('r') as TgLoginReason | null);
  const reason = r && REASONS.includes(r) ? r : null;
  const bot = options?.botUsername ?? null;
  // 验收反馈 1c：深链接，机器人一定会回一条（要重新授权的回提示，不用的回主菜单）
  const link = botStartLink(bot);
  return (
    <AuthLayout title={t('tg.title')} subtitle={bot ? t('tg.subtitle', { bot }) : t('tg.subtitleNoBot')}>
      <EmergencyLogin />
      {reason && (
        <div className={`notice ${reason === 'refused' ? 'info' : 'error'}`} role="alert" data-testid="tg-reason">
          {t(`tg.reason.${reason}`)}
        </div>
      )}
      <ol className="tg-steps">
        <li>{t('tg.step1')}</li>
        <li>{t('tg.step2')}</li>
      </ol>
      {link && (
        <>
          <a className="btn btn-primary btn-lg btn-block" href={link} data-testid="tg-open">
            {t('tg.open', { bot })}
          </a>
          <div className="tg-qr">
            <QrCode value={link} size={152} label={t('tg.qrLabel')} />
            <small className="muted">{t('tg.qrHint')}</small>
          </div>
        </>
      )}
      <div className="tg-entries" aria-label={t('tg.entriesTitle')}>
        <h2>{t('tg.entriesTitle')}</h2>
        <Link to="/tg/freeze" className="tg-entry" data-testid="tg-freeze">
          <b>{t('tg.freeze')}</b>
          <small className="muted">{t('tg.freezeDesc')}</small>
        </Link>
        <Link to="/tg/recover" className="tg-entry" data-testid="tg-recover">
          <b>{t('tg.recover')}</b>
          <small className="muted">{t('tg.recoverDesc')}</small>
        </Link>
      </div>
    </AuthLayout>
  );
}
