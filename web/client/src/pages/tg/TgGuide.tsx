/**
 * 方案 B（11/28 Amos）："请在 Telegram 里打开"引导页：门禁（{@link TelegramGate}）判断不在 Telegram 内置浏览器里时，只显示这一页，不显示任何应用界面。
 * 按平台给打开 Telegram「应用内浏览器」的步骤（菜单路径是大致的）、机器人按钮和二维码；总是用外部浏览器打开链接的手机（例如部分华为）给一句说明。
 * 保留「申请冻结提现」「找回 Telegram 账户」两个入口（这两页不受门禁限制，TG 丢了或被占的原主要能用）。
 * 显示一次就匿名计一次数（只报平台大类 IOS / ANDROID / OTHER，不报 UA、不带 Cookie），后台仪表盘看"非 Telegram 打开被拦次数"。
 */
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { getAuthOptions } from '../../api/auth';
import { reportGuideShown } from '../../api/tgGate';
import { QrCode } from '../../components/QrCode';
import { platformFamily, type PlatformFamily } from '../../lib/telegramInApp';
import { AuthLayout } from '../auth/AuthLayout';
import { botStartLink } from './tgLib';

export default function TgGuide() {
  const { t } = useTranslation();
  const [bot, setBot] = useState<string | null>(null);
  const [platform] = useState<PlatformFamily>(() => platformFamily());
  useEffect(() => {
    let alive = true;
    getAuthOptions().then(
      (o) => alive && setBot(o.botUsername),
      () => undefined,
    );
    return () => {
      alive = false;
    };
  }, []);
  useEffect(() => {
    reportGuideShown(platform);
  }, [platform]);
  const link = botStartLink(bot, 'open');
  const step1 = platform === 'IOS' ? 'tg.guide.iosStep1' : platform === 'ANDROID' ? 'tg.guide.androidStep1' : 'tg.guide.otherStep1';
  return (
    <AuthLayout title={t('tg.guide.title')} subtitle={t('tg.guide.subtitle')}>
      <div data-testid="tg-guide" data-platform={platform}>
        <p className="notice info" role="alert">
          {t('tg.guide.why')}
        </p>
        <p className="small">{t('tg.guide.stepsTitle')}</p>
        <ol className="tg-steps">
          <li data-testid="tg-guide-step1">{t(step1)}</li>
          <li>{t('tg.guide.step2')}</li>
        </ol>
        {link && (
          <>
            <a className="btn btn-primary btn-lg btn-block" href={link} data-testid="tg-open">
              {t('tg.guide.open', { bot })}
            </a>
            <div className="tg-qr">
              <QrCode value={link} size={152} label={t('tg.qrLabel')} />
              <small className="muted">{t('tg.qrHint')}</small>
            </div>
          </>
        )}
        <p className="muted small" data-testid="tg-guide-unsupported">
          {t('tg.guide.unsupported')}
        </p>
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
      </div>
    </AuthLayout>
  );
}
