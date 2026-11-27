/**
 * 找回申请（TG 机器人第 4 批 4b，需求文档 TG机器人-08 页第 1、3 节）：别人申请把我的账户换绑到另一个 Telegram、或我申请的人工重设提现密码。
 * 原 TG 在机器人里点了「不是我」以后从这里输提现密码（随机键盘）：输对不冻结提现，输错冻结直到人工处理完；也可以撤销（争议路径上撤销要审核员决定）。
 * 从机器人按钮进来时带 ?id=申请编号，那一份排在最前、展开输提现密码。
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import { errorKey } from '../../api/errors';
import type { MyRecovery, PinInput } from '../../api/types';
import { listMyRecoveries, objectRecovery, revokeRecovery } from '../../api/wallet';
import { AsyncView, EmptyState } from '../../components/AsyncView';
import { PageHeader } from '../../components/Layout';
import { ConfirmDialog } from '../../components/Modal';
import { PinPad } from '../../components/PinPad';
import { useAsync } from '../../hooks/useAsync';
import { formatDateTime } from '../../lib/format';

const OPEN = ['objection', 'proof', 'approved'];

function Item({ r, focus, onChanged }: { r: MyRecovery; focus: boolean; onChanged: () => void }) {
  const { t } = useTranslation();
  const [pin, setPin] = useState<PinInput | null>(null);
  const [round, setRound] = useState(0);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  const open = OPEN.includes(r.status);
  const needPin = open && r.kind === 'rebind' && r.disputed && !r.disputePinOk;

  const submitPin = async () => {
    if (!pin) return;
    setBusy(true);
    setErr(null);
    try {
      await objectRecovery(r.id, pin);
      setMsg(t('recovery.pinOkDone'));
      onChanged();
    } catch (e) {
      setErr(t(errorKey(e)));
      setRound((x) => x + 1);
      onChanged();
    } finally {
      setBusy(false);
    }
  };

  const revoke = async () => {
    setBusy(true);
    setErr(null);
    try {
      setMsg((await revokeRecovery(r.id)).message);
      setConfirm(false);
      onChanged();
    } catch (e) {
      setErr(t(errorKey(e)));
      setConfirm(false);
    } finally {
      setBusy(false);
    }
  };

  return (
    <li className={`recovery-item${focus ? ' focus' : ''}`} data-testid={`recovery-${r.id}`}>
      <div className="recovery-head">
        <b>{t('recovery.no', { id: r.id })}</b> · {t(`recovery.kind.${r.kind}`)}
        <span className={`st st-${open ? 'wait' : 'mute'}`}>{t(`recovery.status.${r.status}`)}</span>
      </div>
      <p className="muted small">
        {t('recovery.createdAt', { time: formatDateTime(r.createdAt) })}
        {r.status === 'objection' && r.objectionEndsAt ? ` · ${t('recovery.objectionEnds', { time: formatDateTime(r.objectionEndsAt) })}` : ''}
        {r.status === 'approved' && r.executeAt ? ` · ${t('recovery.executeAt', { time: formatDateTime(r.executeAt) })}` : ''}
      </p>
      {r.disputed && <p className="small">{t('recovery.disputed')}</p>}
      {r.disputePinOk && <p className="small ok-text">{t('recovery.pinOk')}</p>}
      {r.disputeFrozen && !r.disputePinOk && <p className="small warn-text">{t('recovery.frozen')}</p>}
      {r.revokeRequested && <p className="small warn-text">{t('recovery.revokeRequested')}</p>}
      {needPin && (
        <div className="pin-form">
          <PinPad id={`rc-pin-${r.id}`} label={t('recovery.pinLabel')} onChange={setPin} resetKey={round} />
          <button type="button" className="btn btn-primary btn-block" onClick={submitPin} disabled={busy || !pin}>
            {busy ? t('common.submitting') : t('recovery.pinSubmit')}
          </button>
        </div>
      )}
      {open && !r.revokeRequested && (
        <button type="button" className="btn btn-danger-outline btn-sm" onClick={() => setConfirm(true)} disabled={busy}>
          {t('recovery.revoke')}
        </button>
      )}
      {msg && (
        <div className="notice ok" role="status">
          {msg}
        </div>
      )}
      {err && (
        <div className="notice error" role="alert">
          {err}
        </div>
      )}
      <ConfirmDialog open={confirm} title={t('recovery.revokeConfirm')} confirmText={t('recovery.revoke')} tone="danger" busy={busy} onConfirm={revoke} onClose={() => setConfirm(false)}>
        <p>{t('recovery.revokeBody')}</p>
      </ConfirmDialog>
    </li>
  );
}

export default function Recovery() {
  const { t } = useTranslation();
  const [params] = useSearchParams();
  const focus = Number(params.get('id')) || null;
  const s = useAsync(listMyRecoveries, 'my-recoveries');
  return (
    <div className="page page-narrow">
      <PageHeader title={t('recovery.title')} back="/me" />
      <section className="card">
        <p className="muted small">{t('recovery.intro')}</p>
        <AsyncView state={s} isEmpty={(d) => d.length === 0} empty={<EmptyState title={t('recovery.empty')} />}>
          {(list) => (
            <ul className="recovery-list">
              {[...list].sort((a, b) => (a.id === focus ? -1 : b.id === focus ? 1 : 0)).map((r) => (
                <Item key={r.id} r={r} focus={r.id === focus} onChanged={s.reload} />
              ))}
            </ul>
          )}
        </AsyncView>
      </section>
    </div>
  );
}
