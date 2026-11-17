/**
 * 对账（第 1 批原型 P6）：列出钱包记录、账本、钱包提供方（TopUP）不一致的问题，标记已处理（必填说明，修复 fxsystem F5）。
 * 顶部固定写"按单号逐笔核对，不是全量对账"（TopUP 还不能按时间段拉流水，TG-2）；显示上次对账时间、核对笔数、提供方通不通；"立即对账"。
 * 问题类型和字段按后端：钱包 ↔ 账本 7 种 + 和提供方逐笔核对 6 种，字段 type、ref、expected、actual、diff、detail；后端只支持按状态筛选。
 */
import { useCallback, useState } from 'react';
import { Alert, Select } from 'antd';
import { useApi } from '../../hooks/useApi';
import { useAction } from '../../hooks/useConfirmDialogs';
import { fullTime } from '../../utils/display';
import { useTranslation } from 'react-i18next';
import { getReconStatus, listRecon, resolveRecon, runRecon } from '../../api';
import type { ReconItem, ReconQuery } from '../../api/types';
import { usePagedList } from '../../hooks/usePagedList';
import { useNoteDialog } from '../../hooks/useConfirmDialogs';
import { PageHeader } from '../../components/PageHeader';
import { FilterBar, FilterItem } from '../../components/FilterBar';
import { DataTable } from '../../components/DataTable';
import { PermButton } from '../../components/PermButton';
import { Pill } from '../../components/Pill';
import { Amount, Num } from '../../components/Num';
import { reconTone } from '../../utils/status';
import { shortTime } from '../../utils/display';

type Filters = Omit<ReconQuery, 'page' | 'pageSize'>;
const INITIAL: Filters = { status: 'open' };

export function ReconPage() {
  const { t, i18n } = useTranslation();
  const list = usePagedList<ReconItem, Filters>(listRecon, INITIAL);
  const [draft, setDraft] = useState<Filters>(INITIAL);
  const note = useNoteDialog();
  const typeLabel = (v: string) => (i18n.exists(`reconType.${v}`) ? t(`reconType.${v}`) : v);
  const statusFetcher = useCallback(() => getReconStatus(), []);
  const status = useApi(statusFetcher);
  const run = useAction();
  const st = status.data;

  return (
    <>
      <PageHeader title={t('recon.title')} sub={t('recon.sub')} />
      <Alert type="warning" showIcon className="recon-byorder" message={t('recon.byOrderTitle')} description={t('recon.byOrderDesc')} />
      <section className="kpis" aria-label={t('recon.lastRun')}>
        <div className="card kpi">
          <div className="k">{t('recon.lastRun')}</div>
          <div className="v">
            <b className="num">{st?.lastRunAt ? fullTime(st.lastRunAt) : '—'}</b>
          </div>
        </div>
        <div className="card kpi">
          <div className="k">{t('recon.checked')}</div>
          <div className="v">
            <b className="num">{st ? st.checked : '—'}</b>
          </div>
        </div>
        <div className={`card kpi${st && !st.providerOk ? ' orange' : ''}`}>
          <div className="k">{t('recon.provider')}</div>
          <div className="v">
            <b>{st ? (st.providerOk ? t('recon.providerOk') : t('recon.providerDown')) : '—'}</b>
          </div>
        </div>
        <div className="card kpi">
          <div className="k">{t('recon.totalCheck')}</div>
          <div className="v">
            <span className="muted">{t('recon.totalCheckTbd')}</span>
          </div>
        </div>
      </section>
      <div className="recon-run">
        <PermButton
          perm="recon:resolve"
          type="primary"
          onClick={() =>
            run(() => runRecon(), t('recon.runDone'), () => {
              status.reload();
              list.reload();
            })
          }
        >
          {t('recon.runNow')}
        </PermButton>
      </div>
      <Alert type="info" showIcon message={t('recon.hint')} />
      <FilterBar onSearch={() => list.setFilters(draft)} onReset={() => { setDraft(INITIAL); list.setFilters(INITIAL); }}>
        <FilterItem label={t('recon.status')}>
          <Select
            aria-label={t('recon.status')}
            allowClear
            style={{ width: 120 }}
            placeholder={t('common.all')}
            value={draft.status}
            onChange={(v) => setDraft({ ...draft, status: v })}
            options={(['open', 'resolved'] as const).map((s) => ({ value: s, label: t(`reconStatus.${s}`) }))}
          />
        </FilterItem>
      </FilterBar>
      <DataTable<ReconItem>
        rowKey="id"
        loading={list.loading}
        error={list.error}
        onRetry={list.reload}
        dataSource={list.data?.items}
        total={list.data?.total ?? 0}
        page={list.page}
        pageSize={list.pageSize}
        onPageChange={list.onPageChange}
        emptyText={t('recon.empty')}
        columns={[
          { title: t('recon.id'), dataIndex: 'id', render: (v: string) => <Num>{v}</Num> },
          {
            title: t('recon.type'),
            dataIndex: 'type',
            render: (v: string, r) => (
              <div className="user-cell">
                <b>{typeLabel(v)}</b>
                {r.detail ? <small className="ellipsis" style={{ maxWidth: 260 }} title={r.detail}>{r.detail}</small> : null}
              </div>
            ),
          },
          { title: t('recon.ref'), dataIndex: 'ref', render: (v: string) => <Num>{v}</Num> },
          { title: t('funds.user'), key: 'u', render: (_, r) => (r.customerName ? <span className="ellipsis" style={{ maxWidth: 180 }} title={r.customerName}>{r.customerName}</span> : <span className="muted">{t('recon.unknownCustomer')}</span>) },
          { title: t('recon.expected'), dataIndex: 'expected', align: 'right', render: (v: string | null) => <Amount value={v} decimals={6} /> },
          { title: t('recon.actual'), dataIndex: 'actual', align: 'right', render: (v: string | null) => <Amount value={v} decimals={6} /> },
          { title: t('recon.diff'), dataIndex: 'diff', align: 'right', render: (v: string | null) => <Amount value={v} decimals={6} signed strong /> },
          { title: t('recon.detectedAt'), dataIndex: 'detectedAt', render: (v: string) => <Num>{shortTime(v)}</Num> },
          { title: t('recon.status'), dataIndex: 'status', render: (v: string) => <Pill tone={reconTone[v]}>{t(`reconStatus.${v}`)}</Pill> },
          { title: t('recon.note'), dataIndex: 'note', render: (v: string | null, r) => (v ? <span title={v}>{r.handler} · <span className="ellipsis" style={{ maxWidth: 200 }}>{v}</span></span> : '—') },
          {
            title: t('common.actions'),
            key: 'op',
            fixed: 'right',
            render: (_, r) =>
              r.status === 'open' ? (
                <PermButton
                  perm="recon:resolve"
                  size="small"
                  onClick={() =>
                    note.ask({
                      title: t('recon.resolve'),
                      label: t('recon.note'),
                      placeholder: t('recon.notePh'),
                      okText: t('recon.resolve'),
                      onConfirm: (n) => resolveRecon(r.id, n),
                      onDone: list.reload,
                    })
                  }
                >
                  {t('recon.resolve')}
                </PermButton>
              ) : (
                <span className="muted">{t('reconStatus.resolved')}</span>
              ),
          },
        ]}
      />
      {note.element}
    </>
  );
}
