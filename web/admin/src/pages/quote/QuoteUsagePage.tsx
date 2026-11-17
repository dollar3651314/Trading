/**
 * 限额和用量（第 3 批，原型 P9，需求 03 页第三节）：顶部写"只计量不收费"（TD-6 A）；今天用到 80% 的 Key 标橙（已发提醒邮件）、100% 标红
 * （只拒超出的请求）；改某个 Key 的限额（只改它，1 分钟内生效）、改默认值（不影响已建 Key）；按客户、Key、日期、品种筛选，导出 CSV（按天保留 13 个月）。
 */
import { Fragment, useCallback, useMemo, useState } from 'react';
import { Alert, Button, Empty, Input, Select, Skeleton, Table } from 'antd';
import { useTranslation } from 'react-i18next';
import { getQuoteOverview, getQuoteUsage, quoteUsageExportUrl, setQuoteDefaults, setQuoteKeyLimits } from '../../api';
import type { QuoteKey, QuoteUsageQuery, QuoteUsageRow } from '../../api/types';
import { useApi } from '../../hooks/useApi';
import { useHighRisk } from '../../hooks/useConfirmDialogs';
import { PageHeader } from '../../components/PageHeader';
import { ErrorState } from '../../components/ErrorState';
import { PermButton } from '../../components/PermButton';
import { Num } from '../../components/Num';
import { LimitsFields, duration, fmtInt, limitsOf } from './common';

const pct = (k: QuoteKey) => Math.floor((k.usedToday / k.limits.restPerDay) * 100);

export function QuoteUsagePage() {
  const { t } = useTranslation();
  const [q, setQ] = useState<QuoteUsageQuery>({});
  const [draft, setDraft] = useState<QuoteUsageQuery>({});
  const fetchOverview = useCallback(() => getQuoteOverview(), []);
  const ov = useApi(fetchOverview);
  const fetchUsage = useCallback(() => getQuoteUsage(q), [q]);
  const us = useApi(fetchUsage);
  const hr = useHighRisk();
  // 固定的 key：重新加载（页面变成"加载中"）时确认弹窗不被重建
  const hrEl = <Fragment key="q-hr">{hr.element}</Fragment>;
  const reload = () => {
    ov.reload();
    us.reload();
  };

  const today = useMemo(() => {
    const m = new Map<string, { pushes: number; seconds: number }>();
    for (const d of us.data?.days ?? []) {
      const x = m.get(d.keyId) ?? { pushes: 0, seconds: 0 };
      m.set(d.keyId, { pushes: x.pushes + d.wsPushes, seconds: x.seconds + d.wsSeconds });
    }
    return m;
  }, [us.data]);

  const header = <PageHeader title={t('quote.usageTitle')} sub={t('quote.usageSub')} />;
  const free = <Alert type="info" showIcon className="q-mb" message={t('quote.freeTitle')} description={t('quote.freeDesc')} />;
  if (ov.error || us.error) {
    return (
      <>
        {header}
        {free}
        <div className="card">
          <ErrorState error={ov.error ?? us.error} onRetry={reload} />
        </div>
        {hrEl}
      </>
    );
  }
  if (!ov.data || ov.loading) {
    return (
      <>
        {header}
        {free}
        <div className="card">
          <Skeleton active paragraph={{ rows: 8 }} />
        </div>
        {hrEl}
      </>
    );
  }
  const data = ov.data;
  const keys = data.keys.filter((k) => k.active).sort((a, b) => pct(b) - pct(a));
  const top = keys.find((k) => pct(k) >= 80);

  const editKey = (k: QuoteKey) =>
    hr.ask({
      title: t('quote.limitTitle', { key: k.keyId }),
      description: t('quote.limitDesc'),
      initialValues: { ...k.limits },
      extra: <LimitsFields />,
      successText: t('quote.limitSaved'),
      onConfirm: (v) => setQuoteKeyLimits(k.keyId, limitsOf(v), v.reason),
      onDone: reload,
    });
  const editDefault = () =>
    hr.ask({
      title: t('quote.defaultTitle'),
      description: t('quote.defaultDesc'),
      initialValues: { ...data.defaults },
      extra: <LimitsFields />,
      successText: t('quote.defaultSaved'),
      onConfirm: (v) => setQuoteDefaults(limitsOf(v), v.reason),
      onDone: reload,
    });

  return (
    <>
      {header}
      {free}
      {top ? (
        <section className="card q-hero" aria-label={t('quote.usageHeroAria')}>
          <div className="t">
            <b>{t('quote.usageHero', { key: top.keyId, client: top.clientName ?? '', pct: pct(top) })}</b>
            <span>{t('quote.usageHeroDesc')}</span>
          </div>
          <PermButton perm="quote_limit:edit" type="primary" size="large" onClick={() => editKey(top)}>
            {t('quote.editThisKey')}
          </PermButton>
        </section>
      ) : null}
      <section className="card">
        <div className="q-card-head">
          <h3>{t('quote.defaults')}</h3>
          <PermButton perm="quote_limit:edit" onClick={editDefault}>
            {t('quote.editDefault')}
          </PermButton>
        </div>
        <div className="q-stats">
          <div>
            <small>{t('quote.restPerSecond')}</small>
            <b>{fmtInt(data.defaults.restPerSecond)}</b>
          </div>
          <div>
            <small>{t('quote.restPerDay')}</small>
            <b>{fmtInt(data.defaults.restPerDay)}</b>
          </div>
          <div>
            <small>{t('quote.wsConnections')}</small>
            <b>{fmtInt(data.defaults.wsConnections)}</b>
          </div>
          <div>
            <small>{t('quote.wsSymbols')}</small>
            <b>{fmtInt(data.defaults.wsSymbols)}</b>
          </div>
        </div>
        <p className="muted small">{t('quote.defaultsHint')}</p>
      </section>
      <section className="card">
        <h3>{t('quote.todayByKey')}</h3>
        <Table
          size="small"
          rowKey="keyId"
          pagination={false}
          scroll={{ x: 980 }}
          dataSource={keys}
          rowClassName={(k) => (pct(k) >= 100 ? 'row-danger' : pct(k) >= 80 ? 'row-warn' : '')}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('quote.noUsage')} /> }}
          columns={[
            { title: 'Key', dataIndex: 'keyId', render: (v: string) => <Num>{v}</Num> },
            { title: t('quote.client'), dataIndex: 'clientName' },
            { title: t('quote.todayRest'), key: 'rest', align: 'right' as const, render: (_: unknown, k: QuoteKey) => <Num>{`${fmtInt(k.usedToday)} / ${fmtInt(k.limits.restPerDay)}`}</Num> },
            {
              title: t('quote.used'),
              key: 'pct',
              render: (_: unknown, k: QuoteKey) => {
                const p = pct(k);
                return (
                  <div className="usage">
                    <div className={`bar${p >= 100 ? ' over' : p >= 80 ? ' warn' : ''}`}>
                      <i style={{ width: `${Math.min(p, 100)}%` }} />
                    </div>
                    <span className="small">
                      {p}%{p >= 80 && p < 100 ? ` · ${t('quote.mailed80')}` : ''}
                      {k.rejectedToday ? ` · ${t('quote.rejectedToday', { n: fmtInt(k.rejectedToday) })}` : ''}
                    </span>
                  </div>
                );
              },
            },
            { title: t('quote.wsConnectionsNow'), key: 'ws', align: 'right' as const, render: (_: unknown, k: QuoteKey) => <Num>{`${k.connections} / ${k.limits.wsConnections}`}</Num> },
            { title: t('quote.todayPushes'), key: 'push', align: 'right' as const, render: (_: unknown, k: QuoteKey) => <Num>{fmtInt(today.get(k.keyId)?.pushes ?? 0)}</Num> },
            { title: t('quote.connDuration'), key: 'dur', render: (_: unknown, k: QuoteKey) => duration(t, today.get(k.keyId)?.seconds ?? 0) },
            {
              title: t('quote.ops'),
              key: 'ops',
              render: (_: unknown, k: QuoteKey) => (
                <PermButton perm="quote_limit:edit" size="small" onClick={() => editKey(k)}>
                  {t('quote.editThisKey')}
                </PermButton>
              ),
            },
          ]}
        />
      </section>
      <section className="card">
        <h3>{t('quote.detail')}</h3>
        <div className="q-filters" role="search">
          <Select
            allowClear
            placeholder={t('quote.allClients')}
            aria-label={t('quote.client')}
            style={{ minWidth: 160 }}
            value={draft.clientId}
            onChange={(v) => setDraft({ ...draft, clientId: v })}
            options={data.clients.map((c) => ({ value: c.id, label: c.name }))}
          />
          <Select
            allowClear
            placeholder={t('quote.allKeys')}
            aria-label="Key"
            style={{ minWidth: 200 }}
            value={draft.keyId}
            onChange={(v) => setDraft({ ...draft, keyId: v })}
            options={data.keys.map((k) => ({ value: k.keyId, label: k.keyId }))}
          />
          <Input type="date" aria-label={t('quote.from')} style={{ width: 150 }} value={draft.from ?? ''} onChange={(e) => setDraft({ ...draft, from: e.target.value || undefined })} />
          <Input type="date" aria-label={t('quote.to')} style={{ width: 150 }} value={draft.to ?? ''} onChange={(e) => setDraft({ ...draft, to: e.target.value || undefined })} />
          <Input placeholder={t('quote.allSymbols')} aria-label={t('quote.symbol')} style={{ width: 130 }} value={draft.symbol ?? ''} onChange={(e) => setDraft({ ...draft, symbol: e.target.value.toUpperCase() || undefined })} />
          <Button type="primary" onClick={() => setQ({ ...draft })}>
            {t('common.search')}
          </Button>
          <Button href={quoteUsageExportUrl(q)} disabled={!us.data?.rows.length}>
            {t('quote.exportCsv')}
          </Button>
        </div>
        <p className="muted small">{t('quote.keep13')}</p>
        {us.loading ? (
          <Skeleton active />
        ) : (
          <Table
            size="small"
            rowKey={(r: QuoteUsageRow) => `${r.day}|${r.keyId}|${r.endpoint}|${r.symbol}`}
            pagination={{ pageSize: 20, hideOnSinglePage: true }}
            scroll={{ x: 900 }}
            dataSource={us.data?.rows ?? []}
            locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('quote.noUsage')} /> }}
            columns={[
              { title: t('quote.day'), dataIndex: 'day', render: (v: string) => <Num>{v}</Num> },
              { title: 'Key', dataIndex: 'keyId', render: (v: string) => <Num>{v}</Num> },
              { title: t('quote.endpoint'), dataIndex: 'endpoint', render: (v: string) => (v ? `REST /v1/${v}` : 'WebSocket') },
              { title: t('quote.symbol'), dataIndex: 'symbol', render: (v: string) => v || '—' },
              { title: t('quote.restCalls'), dataIndex: 'restCalls', align: 'right' as const, render: (v: number) => <Num>{fmtInt(v)}</Num> },
              { title: t('quote.restRejected'), dataIndex: 'restRejected', align: 'right' as const, render: (v: number) => <Num>{fmtInt(v)}</Num> },
              { title: t('quote.wsPushes'), dataIndex: 'wsPushes', align: 'right' as const, render: (v: number) => <Num>{fmtInt(v)}</Num> },
              { title: t('quote.connDuration'), dataIndex: 'wsSeconds', render: (v: number) => duration(t, v) },
            ]}
          />
        )}
      </section>
      {hrEl}
    </>
  );
}
