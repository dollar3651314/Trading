/**
 * 报价源状态（第 3 批，原型 P11，需求 03 页第一节）：每家报价商的连接状态；每个品种在用哪家、最后报价时间、最近 24 小时切换次数和偏差次数。
 * 断流（主备都没有）标红"暂无报价"；偏差 > 0.5% 标黄"交易暂停开仓"；已切到备源标黄。只看不改。
 */
import { useCallback } from 'react';
import { Alert, Button, Empty, Skeleton, Table } from 'antd';
import { useTranslation } from 'react-i18next';
import { getQuoteSources } from '../../api';
import type { QuoteSourceRow } from '../../api/types';
import { useApi } from '../../hooks/useApi';
import { PageHeader } from '../../components/PageHeader';
import { ErrorState } from '../../components/ErrorState';
import { Pill, type PillTone } from '../../components/Pill';
import { Num } from '../../components/Num';
import { fullTime } from '../../utils/display';

const NAMES: Record<string, string> = { alltick: 'AllTick', itick: 'iTick', binance: 'Binance', simulated: 'Simulated' };
const name = (f: string | null) => (f ? NAMES[f] ?? f : '—');

export function QuoteSourcesPage() {
  const { t } = useTranslation();
  const fetcher = useCallback(() => getQuoteSources(), []);
  const { data, error, loading, reload } = useApi(fetcher);
  const header = (
    <PageHeader
      title={t('quote.sourcesTitle')}
      sub={t('quote.sourcesSub')}
      extra={
        <Button onClick={reload} loading={loading}>
          {t('quote.refresh')}
        </Button>
      }
    />
  );
  if (error) {
    return (
      <>
        {header}
        <div className="card">
          <ErrorState error={error} onRetry={reload} />
        </div>
      </>
    );
  }
  if (!data || loading) {
    return (
      <>
        {header}
        <div className="card">
          <Skeleton active paragraph={{ rows: 8 }} />
        </div>
      </>
    );
  }
  const ST: Record<QuoteSourceRow['state'], [string, PillTone]> = {
    ok: [t('quote.srcOk'), 'ok'],
    backup: [t('quote.srcBackup'), 'warn'],
    deviation: [t('quote.srcDeviation'), 'warn'],
    down: [t('quote.srcDown'), 'danger'],
    closed: [t('quote.srcClosed'), 'muted'],
  };
  const down = data.rows.filter((r) => r.state === 'down');
  const dev = data.rows.filter((r) => r.state === 'deviation');
  const configured = data.feeds.some((f) => f.configured);
  return (
    <>
      {header}
      {down.length ? (
        <Alert className="q-mb" type="error" showIcon message={t('quote.srcDownHero', { list: down.map((r) => r.symbol).join('、') })} description={t('quote.srcDownHeroDesc')} />
      ) : null}
      {dev.length ? (
        <Alert className="q-mb" type="warning" showIcon message={t('quote.srcDevHero', { list: dev.map((r) => r.symbol).join('、') })} description={t('quote.srcDevHeroDesc')} />
      ) : null}
      <section className="pcards" aria-label={t('quote.feeds')}>
        {data.feeds.map((f) => (
          <div key={f.feed} className={`card pcard${!f.configured ? ' off' : !f.ok ? ' bad' : ''}`}>
            <h3>
              {name(f.feed)}{' '}
              {!f.configured ? (
                <Pill tone="muted">{f.feed === 'binance' ? t('quote.binanceOff') : t('quote.notConfigured')}</Pill>
              ) : f.ok ? (
                <Pill tone="ok">{t('quote.srcOk')}</Pill>
              ) : (
                <Pill tone="danger">{t('quote.feedBad')}</Pill>
              )}
            </h3>
            {f.configured && f.detail ? <p className="muted small">{f.detail}</p> : null}
          </div>
        ))}
      </section>
      {!configured && !data.rows.length ? (
        <section className="card">
          <Empty description={t('quote.noFeeds')} />
        </section>
      ) : (
        <section className="card">
          {!configured ? <Alert type="info" showIcon className="q-mb" message={t('quote.simulatedHint')} /> : null}
          <Table
            size="small"
            rowKey="symbol"
            pagination={{ pageSize: 50, hideOnSinglePage: true }}
            scroll={{ x: 900 }}
            dataSource={data.rows}
            rowClassName={(r) => (r.state === 'down' ? 'row-danger' : r.state === 'backup' || r.state === 'deviation' ? 'row-warn' : '')}
            columns={[
              { title: t('quote.symbol'), dataIndex: 'symbol', render: (v: string) => <Num>{v}</Num> },
              { title: t('quote.primary'), dataIndex: 'primary', render: (v: string | null) => name(v) },
              { title: t('quote.backup'), dataIndex: 'backup', render: (v: string | null) => name(v) },
              { title: t('quote.current'), dataIndex: 'current', render: (v: string | null) => <b>{name(v)}</b> },
              { title: t('quote.lastQuote'), dataIndex: 'lastQuoteAt', render: (v: string | null) => <Num>{v ? fullTime(v) : '—'}</Num> },
              { title: t('quote.switches24h'), dataIndex: 'switches24h', align: 'right' as const },
              { title: t('quote.deviations24h'), dataIndex: 'deviations24h', align: 'right' as const },
              { title: t('quote.status'), dataIndex: 'state', render: (v: QuoteSourceRow['state']) => <Pill tone={ST[v][1]}>{ST[v][0]}</Pill> },
            ]}
          />
        </section>
      )}
      <p className="muted small">{t('quote.srcRule')}</p>
    </>
  );
}
