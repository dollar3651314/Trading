/**
 * 提示页上的两个入口：「申请冻结提现」「找回 Telegram 账户」（TB-33 要求提示页保留）。
 * 完整流程（本人信息、图形验证码、人工审核、反对期等，需求文档 TG机器人-08 页）在第 4 批做；这一批先占住入口，如实说明还没开放。
 */
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { AuthLayout } from '../auth/AuthLayout';

export default function TgEntry({ kind }: { kind: 'freeze' | 'recover' }) {
  const { t } = useTranslation();
  return (
    <AuthLayout title={t(kind === 'freeze' ? 'tg.freeze' : 'tg.recover')}>
      <div className="notice info" role="status" data-testid="tg-entry-pending">
        {t('tg.entryPending')}
      </div>
      <Link to="/tg" className="btn btn-block">
        {t('tg.back')}
      </Link>
    </AuthLayout>
  );
}
