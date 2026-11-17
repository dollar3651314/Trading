/**
 * 入金记录（第 1 批原型 P5）：钱包提供方（TopUP）确认到账后回调入账，只显示已入账、已冲正；多"提供方""提供方到账编号"两列，去掉确认数（由 TopUP 决定）。
 * 按到账时间（到秒）、客户、链、提供方到账编号、交易哈希查。低于 1 USDT 的转账 TopUP 不通知，这里不会出现。
 */
import { useState } from 'react';
import { Alert, Input, Select, Tooltip, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { listDeposits } from '../../api';
import type { Deposit, DepositQuery } from '../../api/types';
import { CHAINS } from '../../config/constants';
import { usePagedList } from '../../hooks/usePagedList';
import { PageHeader } from '../../components/PageHeader';
import { FilterBar, FilterItem } from '../../components/FilterBar';
import { DataTable } from '../../components/DataTable';
import { ChainTag, Pill } from '../../components/Pill';
import { Amount, Num } from '../../components/Num';
import { depositTone } from '../../utils/status';
import { fullTime, shortHash, shortTime } from '../../utils/display';

type Filters = Omit<DepositQuery, 'page' | 'pageSize'>;

export function DepositsPage() {
  const { t } = useTranslation();
  const list = usePagedList<Deposit, Filters>(listDeposits, {});
  const [draft, setDraft] = useState<Filters>({});

  return (
    <>
      <PageHeader title={t('deposits.title')} sub={t('deposits.sub')} />
      <FilterBar onSearch={() => list.setFilters(draft)} onReset={() => { setDraft({}); list.setFilters({}); }}>
        <FilterItem label={t('funds.chain')}>
          <Select aria-label={t('funds.chain')} allowClear style={{ width: 110 }} placeholder={t('common.all')} value={draft.chain} onChange={(v) => setDraft({ ...draft, chain: v })} options={CHAINS.map((c) => ({ value: c, label: c }))} />
        </FilterItem>
        <FilterItem label={t('deposits.status')}>
          <Select
            aria-label={t('deposits.status')}
            allowClear
            style={{ width: 170 }}
            placeholder={t('common.all')}
            value={draft.status}
            onChange={(v) => setDraft({ ...draft, status: v })}
            options={(['confirming', 'credited', 'ignored', 'reversed'] as const).map((s) => ({ value: s, label: t(`depositStatus.${s}`) }))}
          />
        </FilterItem>
        <FilterItem label={t('deposits.timeRange')}>
          <span className="range">
            <Input aria-label={t('deposits.from')} placeholder={t('deposits.timePh')} style={{ width: 170 }} value={draft.from} onChange={(e) => setDraft({ ...draft, from: e.target.value || undefined })} />
            ~
            <Input aria-label={t('deposits.to')} placeholder={t('deposits.timePh')} style={{ width: 170 }} value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value || undefined })} />
          </span>
        </FilterItem>
        <FilterItem label={t('deposits.providerRef')} htmlFor="d-ref">
          <Input id="d-ref" allowClear style={{ width: 170 }} value={draft.providerRef} onChange={(e) => setDraft({ ...draft, providerRef: e.target.value || undefined })} />
        </FilterItem>
        <FilterItem label={t('funds.user')} htmlFor="d-kw">
          <Input id="d-kw" allowClear style={{ width: 240 }} placeholder={t('deposits.keywordPh')} value={draft.keyword} onChange={(e) => setDraft({ ...draft, keyword: e.target.value })} />
        </FilterItem>
      </FilterBar>
      <Alert type="info" showIcon message={t('deposits.providerHint')} />
      <DataTable<Deposit>
        rowKey="id"
        loading={list.loading}
        error={list.error}
        onRetry={list.reload}
        dataSource={list.data?.items}
        total={list.data?.total ?? 0}
        page={list.page}
        pageSize={list.pageSize}
        onPageChange={list.onPageChange}
        columns={[
          { title: t('deposits.id'), dataIndex: 'id', render: (v: string) => <Num>{v}</Num> },
          {
            title: t('funds.user'),
            key: 'u',
            render: (_, r) => (
              <div className="user-cell">
                <b title={r.customerName}>{r.customerName}</b>
                <small>{r.email}</small>
              </div>
            ),
          },
          { title: t('funds.chain'), dataIndex: 'chain', render: (v: string) => <ChainTag chain={v} /> },
          { title: t('deposits.amount'), dataIndex: 'amount', align: 'right', render: (v: string) => <Amount value={v} decimals={v.includes('.') && v.split('.')[1].length > 2 ? 6 : 2} strong /> },
          {
            title: t('deposits.txHash'),
            dataIndex: 'txHash',
            render: (v: string) => (
              <Tooltip title={v}>
                <Typography.Text copyable={{ text: v, tooltips: [t('common.copy'), t('common.copied')] }} className="num">
                  {shortHash(v, 8, 6)}
                </Typography.Text>
              </Tooltip>
            ),
          },
          { title: t('deposits.provider'), dataIndex: 'provider', render: (v: string | null) => (v === 'TOPUP' ? 'TopUP' : v ?? '—') },
          {
            title: t('deposits.providerRef'),
            dataIndex: 'providerRef',
            render: (v: string | null) =>
              v ? (
                <Typography.Text copyable={{ text: v, tooltips: [t('common.copy'), t('common.copied')] }} className="num">
                  {v}
                </Typography.Text>
              ) : (
                '—'
              ),
          },
          { title: t('deposits.status'), dataIndex: 'status', render: (v: string) => <Pill tone={depositTone[v]}>{t(`depositStatus.${v}`)}</Pill> },
          { title: t('deposits.detectedAt'), dataIndex: 'detectedAt', render: (v: string) => <Num>{shortTime(v)}</Num> },
          { title: t('deposits.creditedAt'), dataIndex: 'creditedAt', render: (v: string | null) => (v ? <Num>{fullTime(v)}</Num> : '—') },
        ]}
      />
    </>
  );
}
