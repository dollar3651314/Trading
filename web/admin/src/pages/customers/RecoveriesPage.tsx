/**
 * 找回申请（TG 机器人第 4 批 4b，需求文档 TG机器人-08 页第 1、3、5 节，TB-19 v14）：人工换绑、没邮箱人工重设提现密码。Amos 一人审核（权限 recovery:review）。
 * - 列表按状态筛选；详情显示被找回的客户、申请人的新 TG、反对期、争议（"不是我"、提现密码、争议冻结）、执行时间和经过。
 * - 批准：只在"人工核对中"；写证明核对记录（第 0 节：来源地址 / 视频共享 App / P2P），再输自己的后台密码；批准后换绑等 24 小时、人工重设等 48 小时才执行。
 * - 驳回（换绑会封申请人的 TG）、撤销（本人撤销，直接生效）要原因；争议路径上收到撤销请求时选"撤销"或"维持批准"（维持要后台密码）。
 */
import { useCallback, useState } from 'react';
import { Form, Input, Select, Skeleton } from 'antd';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { approveRecovery, getRecovery, keepRecovery, listRecoveries, rejectRecovery, revokeRecovery } from '../../api';
import type { Recovery, RecoveryDetail, RecoveryStatus } from '../../api/types';
import { useApi } from '../../hooks/useApi';
import { useHighRisk } from '../../hooks/useConfirmDialogs';
import { PageHeader } from '../../components/PageHeader';
import { DataTable } from '../../components/DataTable';
import { ErrorState } from '../../components/ErrorState';
import { PermButton } from '../../components/PermButton';
import { Pill, type PillTone } from '../../components/Pill';
import { Num } from '../../components/Num';
import { shortTime } from '../../utils/display';

const STATUSES: RecoveryStatus[] = ['objection', 'proof', 'approved', 'executed', 'rejected', 'cancelled'];
const TONE: Record<RecoveryStatus, PillTone> = { objection: 'pend', proof: 'warn', approved: 'delay', executed: 'ok', rejected: 'danger', cancelled: 'muted' };

function passwordField(t: (k: string) => string) {
  return (
    <Form.Item name="password" label={t('recoveries.password')} extra={t('recoveries.passwordHint')} rules={[{ required: true, message: t('recoveries.passwordRequired') }]}>
      <Input.Password autoComplete="current-password" />
    </Form.Item>
  );
}

function Detail({ id, onChanged }: { id: string; onChanged: () => void }) {
  const { t } = useTranslation();
  const hr = useHighRisk();
  const fetcher = useCallback(() => getRecovery(id), [id]);
  const { data, loading, error, reload } = useApi<RecoveryDetail>(fetcher);
  if (error) return <ErrorState error={error} onRetry={reload} compact />;
  if (!data || loading) return <Skeleton active paragraph={{ rows: 8 }} />;
  const r = data.recovery;
  const after = () => {
    reload();
    onChanged();
  };
  const target = t('recoveries.target', { name: r.customerName ?? '—', id: r.customerId });
  const approve = () =>
    hr.ask({
      title: t('recoveries.approve'),
      description: (
        <>
          <div>{target}</div>
          <div>{t(r.kind === 'rebind' ? 'recoveries.approveImpactRebind' : 'recoveries.approveImpactPin')}</div>
        </>
      ),
      reasonLabel: t('recoveries.proofNote'),
      extra: passwordField(t),
      confirmText: t('recoveries.confirmApprove'),
      onConfirm: (v) => approveRecovery(r.id, v.reason, String(v.password ?? '')),
      onDone: after,
    });
  const reject = () =>
    hr.ask({
      title: t('recoveries.reject'),
      description: (
        <>
          <div>{target}</div>
          {r.kind === 'rebind' ? <div>{t('recoveries.rejectImpact')}</div> : null}
        </>
      ),
      confirmText: t('recoveries.confirmReject'),
      onConfirm: (v) => rejectRecovery(r.id, v.reason),
      onDone: after,
    });
  const revoke = () =>
    hr.ask({
      title: t('recoveries.revoke'),
      description: (
        <>
          <div>{target}</div>
          <div>{t('recoveries.revokeImpact')}</div>
        </>
      ),
      confirmText: t('recoveries.confirmRevoke'),
      onConfirm: (v) => revokeRecovery(r.id, v.reason),
      onDone: after,
    });
  const keep = () =>
    hr.ask({
      title: t('recoveries.keep'),
      description: (
        <>
          <div>{target}</div>
          <div>{t('recoveries.keepImpact')}</div>
        </>
      ),
      extra: passwordField(t),
      confirmText: t('recoveries.confirmKeep'),
      onConfirm: (v) => keepRecovery(r.id, v.reason, String(v.password ?? '')),
      onDone: after,
    });
  const open = r.status === 'objection' || r.status === 'proof' || r.status === 'approved';
  return (
    <div className="det" aria-label={t('recoveries.detail')} role="region">
      <h3>
        {t('recoveries.no', { id: r.id })} · {t(`recoveries.kind.${r.kind}`)} <Pill tone={TONE[r.status]}>{t(`recoveries.status.${r.status}`)}</Pill>
      </h3>
      <div className="kv-grid">
        <div>
          {t('recoveries.customer')}
          <span>
            <Link to={`/customers/${r.customerId}`}>{r.customerName ?? '—'}</Link> <Num>#{r.customerId}</Num>
          </span>
        </div>
        <div>
          {t('recoveries.currentTg')}
          <span>{r.customerTelegram ?? '—'}</span>
        </div>
        <div>
          {t('recoveries.kyc')}
          <span>{r.kycStatus ?? '—'}</span>
        </div>
        {r.kind === 'rebind' ? (
          <div>
            {t('recoveries.applicant')}
            <span>
              {r.applicantTgUsername ? `@${r.applicantTgUsername} · ` : ''}
              <Num>{r.applicantTgId}</Num>
            </span>
          </div>
        ) : null}
        <div>
          {t('recoveries.createdAt')}
          <span className="num">{shortTime(r.createdAt)}</span>
        </div>
        {r.objectionEndsAt ? (
          <div>
            {t('recoveries.objectionEnds')}
            <span className="num">{shortTime(r.objectionEndsAt)}</span>
          </div>
        ) : null}
        {r.executeAt ? (
          <div>
            {t('recoveries.executeAt')}
            <span className="num">{shortTime(r.executeAt)}</span>
          </div>
        ) : null}
      </div>
      {r.disputed ? (
        <div className="addr-box" role="group" aria-label={t('recoveries.dispute')}>
          <b>{t('recoveries.dispute')}</b> <Pill tone="danger">{t('recoveries.disputedTag')}</Pill>{' '}
          {r.disputePinOk ? <Pill tone="ok">{t('recoveries.pinOk')}</Pill> : null} {r.disputeFrozen ? <Pill tone="warn">{t('recoveries.disputeFrozen')}</Pill> : null}
          <div>{t('recoveries.disputeRule')}</div>
        </div>
      ) : null}
      {r.revokeRequested ? (
        <div className="addr-box" role="alert">
          <span className="text-danger">{t('recoveries.revokeRequested', { time: shortTime(r.revokeRequestedAt ?? '') })}</span>
        </div>
      ) : null}
      {r.keptAt && !r.revokeRequested && (r.status === 'proof' || r.status === 'approved') ? (
        <div className="addr-box" role="status" aria-label={t('recoveries.keptTitle')}>
          {t('recoveries.kept', { time: shortTime(r.keptAt) })}
        </div>
      ) : null}
      {r.proofNote ? <div className="addr-box ok">{t('recoveries.proofNoteLine', { note: r.proofNote, by: r.approvedBy ?? '—' })}</div> : null}
      {r.closeReason ? <div className="addr-box">{t('recoveries.closeLine', { by: r.closedBy ?? '—', reason: r.closeReason })}</div> : null}
      {open ? (
        <div className="acts">
          {r.status === 'proof' ? (
            <PermButton perm="recovery:review" type="primary" onClick={approve} disabled={r.revokeRequested} disabledReason={t('errors.recoveryRevokePending')}>
              {t('recoveries.approve')}
            </PermButton>
          ) : null}
          {r.status === 'objection' || r.status === 'proof' ? (
            <PermButton perm="recovery:review" className="btn-reject" onClick={reject}>
              {t('recoveries.reject')}
            </PermButton>
          ) : null}
          {(r.status === 'approved' || r.status === 'proof') && r.revokeRequested ? (
            <PermButton perm="recovery:review" type="primary" onClick={keep}>
              {t('recoveries.keep')}
            </PermButton>
          ) : null}
          <PermButton perm="recovery:review" danger onClick={revoke}>
            {t('recoveries.revoke')}
          </PermButton>
        </div>
      ) : null}
      <div className="mini" role="group" aria-label={t('recoveries.deposits')}>
        <div style={{ fontWeight: 600, color: 'var(--text)', marginBottom: 2 }}>{t('recoveries.deposits')}</div>
        <div className="muted">{t('recoveries.depositsHint')}</div>
        {data.deposits?.length ? (
          data.deposits.map((d, i) => (
            <div className="row" key={i}>
              <span>
                {shortTime(d.time)} · {d.chain}
                {d.explorerUrl ? (
                  <>
                    {' · '}
                    <a href={d.explorerUrl} target="_blank" rel="noreferrer noopener" title={d.txHash ?? ''}>
                      {t('withdrawals.viewTx')}
                    </a>
                  </>
                ) : null}
              </span>
              <span className="num up">+{d.amount}</span>
            </div>
          ))
        ) : (
          <div className="row">
            <span>{t('common.none')}</span>
          </div>
        )}
      </div>
      <div className="mini">
        <div style={{ fontWeight: 600, color: 'var(--text)', marginBottom: 2 }}>{t('recoveries.events')}</div>
        {data.events.map((e, i) => (
          <div className="row" key={i}>
            <span>
              {shortTime(e.at)} · {t(`recoveries.action.${e.action}`, { defaultValue: e.action })} · {e.actor}
            </span>
            <span>{e.note ?? ''}</span>
          </div>
        ))}
      </div>
      {hr.element}
    </div>
  );
}

export function RecoveriesPage() {
  const { t } = useTranslation();
  const [status, setStatus] = useState<RecoveryStatus | ''>('proof');
  const [selected, setSelected] = useState<string | null>(null);
  const fetcher = useCallback(() => listRecoveries(status || undefined), [status]);
  const { data, loading, error, reload } = useApi<Recovery[]>(fetcher);
  return (
    <>
      <PageHeader title={t('recoveries.title')} sub={t('recoveries.sub')} />
      <section className="card card-pad">
        <Select
          aria-label={t('recoveries.statusFilter')}
          value={status}
          style={{ width: 200, marginBottom: 12 }}
          onChange={(v) => setStatus(v)}
          options={[{ value: '', label: t('common.all') }, ...STATUSES.map((s) => ({ value: s, label: t(`recoveries.status.${s}`) }))]}
        />
        <DataTable<Recovery>
          rowKey="id"
          loading={loading}
          error={error}
          onRetry={reload}
          dataSource={data ?? []}
          emptyText={t('recoveries.empty')}
          onRow={(r) => ({ onClick: () => setSelected(r.id), className: 'clickable-row' })}
          columns={[
            { title: t('recoveries.no', { id: '' }), dataIndex: 'id', render: (v: string) => <Num>{v}</Num> },
            { title: t('recoveries.kindCol'), dataIndex: 'kind', render: (v: Recovery['kind']) => t(`recoveries.kind.${v}`) },
            { title: t('recoveries.customer'), key: 'c', render: (_, r) => `${r.customerName ?? '—'} #${r.customerId}` },
            {
              title: t('recoveries.statusCol'),
              key: 's',
              render: (_, r) => (
                <span>
                  <Pill tone={TONE[r.status]}>{t(`recoveries.status.${r.status}`)}</Pill> {r.disputed ? <Pill tone="danger">{t('recoveries.disputedTag')}</Pill> : null}
                  {r.revokeRequested ? <Pill tone="warn">{t('recoveries.revokeRequestedTag')}</Pill> : null}
                </span>
              ),
            },
            { title: t('recoveries.createdAt'), dataIndex: 'createdAt', render: (v: string) => <span className="num">{shortTime(v)}</span> },
          ]}
        />
      </section>
      {selected ? (
        <section className="card card-pad">
          <Detail id={selected} onChanged={reload} />
        </section>
      ) : null}
    </>
  );
}
