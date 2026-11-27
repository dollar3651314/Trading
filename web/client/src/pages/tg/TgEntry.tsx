/**
 * 提示页上的两个入口：「申请冻结提现」「找回 Telegram 账户」（TB-33 要求提示页保留）。
 * 第 4 批 4b：「找回 Telegram 账户」说明流程，并给出用新 Telegram 打开机器人申请的按钮（深链 ?start=recover，机器人直接进入找回）——
 * 换绑只在机器人里申请（要知道申请人的新 TG 是谁）。第 4 批 4c：「申请冻结提现」是网页表单（{@link FreezeForm}，不用登录）。
 */
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { getAuthOptions } from '../../api/auth';
import { AuthLayout } from '../auth/AuthLayout';
import { FreezeForm } from './FreezeForm';
import { botLink } from './tgLib';

function Recover() {
  const { t } = useTranslation();
  const [bot, setBot] = useState<string | null | undefined>(undefined);
  useEffect(() => {
    let alive = true;
    getAuthOptions().then(
      (o) => alive && setBot(o.botUsername),
      () => alive && setBot(null),
    );
    return () => {
      alive = false;
    };
  }, []);
  const link = botLink(bot);
  return (
    <>
      <p className="small" data-testid="tg-recover-steps">
        {t('tg.recoverSteps')}
      </p>
      {link ? (
        <a className="btn btn-primary btn-block" href={`${link}?start=recover`} target="_blank" rel="noreferrer">
          {t('tg.recoverOpenBot')}
        </a>
      ) : bot === null ? (
        <div className="notice info">{t('tg.recoverNoBot')}</div>
      ) : null}
    </>
  );
}

export default function TgEntry({ kind }: { kind: 'freeze' | 'recover' }) {
  const { t } = useTranslation();
  return (
    <AuthLayout title={t(kind === 'freeze' ? 'tg.freeze' : 'tg.recoverTitle')}>
      {kind === 'recover' ? <Recover /> : <FreezeForm />}
      <Link to="/tg" className="btn btn-block">
        {t('tg.back')}
      </Link>
    </AuthLayout>
  );
}
