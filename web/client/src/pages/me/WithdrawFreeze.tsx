/**
 * 提现冻结（第 4 批 4c，需求文档 TG机器人-08 页第 2 节）：列出这个账户全部来源的提现冻结。
 * 机器人里自己冻结的（bot_self）可以在这里输提现密码申请自助解冻：24 小时后自动解冻（TG 和邮箱各通知一次）；别的来源（网页申请、提现密码锁 2 次、
 * 找回申请、后台）只能人工解冻。
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { errorKey } from '../../api/errors';
import type { MyFreeze, PinInput } from '../../api/types';
import { listMyFreezes, requestSelfUnfreeze } from '../../api/wallet';
import { AsyncView, EmptyState } from '../../components/AsyncView';
import { PageHeader } from '../../components/Layout';
import { PinPad } from '../../components/PinPad';
import { useAsync } from '../../hooks/useAsync';
import { formatDateTime } from '../../lib/format';

function SelfUnfreeze({ onDone }: { onDone: () => void }) {
  const { t } = useTranslation();
  const [pin, setPin] = useState<PinInput | null>(null);
  const [round, setRound] = useState(0);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const submit = async () => {
    if (!pin) return;
    setBusy(true);
    setErr(null);
    try {
      await requestSelfUnfreeze(pin);
      onDone();
    } catch (e) {
      setErr(t(errorKey(e)));
      setRound((r) => r + 1);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="pin-form">
      <p className="small">{t('freeze.selfIntro')}</p>
      <PinPad id="self-unfreeze-pin" label={t('pin.withdrawLabel')} onChange={setPin} resetKey={round} />
      {err && (
        <div className="notice error" role="alert">
          {err}
        </div>
      )}
      <button type="button" className="btn btn-primary btn-block" onClick={submit} disabled={busy || !pin}>
        {busy ? t('common.submitting') : t('freeze.selfSubmit')}
      </button>
    </div>
  );
}

export default function WithdrawFreeze() {
  const { t } = useTranslation();
  const s = useAsync(listMyFreezes, 'my-freezes');
  return (
    <div className="page page-narrow">
      <PageHeader title={t('freeze.title')} back="/me" />
      <section className="card pin-page">
        <AsyncView state={s} isEmpty={(d) => d.length === 0} empty={<EmptyState title={t('freeze.none')} />}>
          {(list: MyFreeze[]) => {
            const self = list.find((f) => f.source === 'bot_self');
            return (
              <>
                <ul className="freeze-list">
                  {list.map((f) => (
                    <li key={f.source} data-testid={`freeze-${f.source}`}>
                      <b>{t(`freeze.source.${f.source}`)}</b> <span className="muted small">{formatDateTime(f.at)}</span>
                      {f.releaseAt ? <div className="small ok-text">{t('freeze.releaseAt', { time: formatDateTime(f.releaseAt) })}</div> : null}
                    </li>
                  ))}
                </ul>
                {list.some((f) => f.source !== 'bot_self') ? <div className="notice warn">{t('freeze.manualOnly')}</div> : null}
                {self && !self.releaseAt ? <SelfUnfreeze onDone={s.reload} /> : null}
              </>
            );
          }}
        </AsyncView>
      </section>
    </div>
  );
}
