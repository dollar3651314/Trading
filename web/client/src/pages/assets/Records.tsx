/**
 * 资金记录（原型 P2"资金记录"，TY-1：充值和提现合成一页）：按类型（全部 / 充值 / 提现）、网络、时间筛选；
 * 提现展开每一步进入的时间（到秒）；"未知"的客户只看到"放款中"（后端已经这样返回）。低于 1 USDT 的转账不入账，不会出现在这里。
 * 资金流水（每一笔余额变化，含交易）仍在"资金流水"页。
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { listDeposits, listWithdrawals } from '../../api/wallet';
import type { DepositRecord, Network, Withdrawal } from '../../api/types';
import { EmptyState, ErrorState, Loading } from '../../components/AsyncView';
import { PageHeader } from '../../components/Layout';
import { useAsync } from '../../hooks/useAsync';
import { useServerEvent } from '../../hooks/useServerEvent';
import { formatAmount, formatDateTime, shortAddress } from '../../lib/format';
import { statusLabel } from './withdrawStatus';
import { Timeline } from './withdrawView';

type Row = { kind: 'deposit'; time: string; network: Network; d: DepositRecord } | { kind: 'withdraw'; time: string; network: Network; w: Withdrawal };

async function loadAll(): Promise<Row[]> {
  const [deps, wds] = await Promise.all([listDeposits(), listWithdrawals(1, 100)]);
  const rows: Row[] = [
    ...deps.map((d): Row => ({ kind: 'deposit', time: d.time, network: d.network, d })),
    ...wds.items.map((w): Row => ({ kind: 'withdraw', time: w.createdAt, network: w.network, w })),
  ];
  return rows.sort((a, b) => b.time.localeCompare(a.time));
}

const NETS: (Network | 'all')[] = ['all', 'ERC20', 'TRC20', 'BSC'];

export default function Records() {
  const { t } = useTranslation();
  const s = useAsync(loadAll, 'fund-records');
  useServerEvent((e) => (e.type === 'deposit' || e.type === 'withdraw' || e.type === 'account') && s.reload());
  const [kind, setKind] = useState<'all' | 'deposit' | 'withdraw'>('all');
  const [net, setNet] = useState<Network | 'all'>('all');
  const [range, setRange] = useState<'all' | '7d'>('all');
  const since = range === '7d' ? Date.now() - 7 * 86_400_000 : 0;
  const rows = (s.data ?? []).filter((r) => (kind === 'all' || r.kind === kind) && (net === 'all' || r.network === net) && new Date(r.time).getTime() >= since);
  const reset = () => {
    setKind('all');
    setNet('all');
    setRange('all');
  };
  return (
    <div className="page page-records">
      <PageHeader title={t('records.title')} back="/assets" />
      <section className="card">
        <div className="filters" role="group" aria-label={t('records.filterType')}>
          {(['all', 'deposit', 'withdraw'] as const).map((k) => (
            <button key={k} type="button" className={`chip${kind === k ? ' on' : ''}`} aria-pressed={kind === k} onClick={() => setKind(k)}>
              {t(`records.kind.${k}`)}
            </button>
          ))}
        </div>
        <div className="filters" role="group" aria-label={t('records.filterNetwork')}>
          {NETS.map((n) => (
            <button key={n} type="button" className={`chip${net === n ? ' on' : ''}`} aria-pressed={net === n} onClick={() => setNet(n)}>
              {n === 'all' ? t('records.allNetworks') : n}
            </button>
          ))}
          <select className="select chip-select" aria-label={t('records.filterTime')} value={range} onChange={(e) => setRange(e.target.value as 'all' | '7d')}>
            <option value="all">{t('records.allTime')}</option>
            <option value="7d">{t('ledger.range.7d')}</option>
          </select>
        </div>
        <p className="muted small">{t('records.note')}</p>
        {s.error && !s.data ? (
          <ErrorState error={s.error} onRetry={s.reload} compact />
        ) : !s.data ? (
          <Loading rows={4} />
        ) : s.data.length === 0 ? (
          <EmptyState
            compact
            title={t('records.empty')}
            hint={t('records.emptyHint')}
            action={
              <Link to="/assets" className="btn btn-primary">
                {t('records.goDeposit')}
              </Link>
            }
          />
        ) : rows.length === 0 ? (
          <EmptyState
            compact
            title={t('records.emptyFiltered')}
            action={
              <button type="button" className="btn btn-ghost btn-sm" onClick={reset}>
                {t('records.clearFilters')}
              </button>
            }
          />
        ) : (
          <ul className="wd-recs fund-recs">
            {rows.map((r) =>
              r.kind === 'deposit' ? (
                <li key={`d${r.d.id}`}>
                  <div className="a">
                    <span className="net-tag">{r.network}</span>
                    <b className="num up">+{formatAmount(r.d.amount)}</b>
                    <span className={`st st-${r.d.status === 'credited' ? 'done' : 'mute'}`}>{t(`deposit.status.${r.d.status}`, { n: r.d.confirmations, m: r.d.requiredConfirmations })}</span>
                  </div>
                  <div className="d">
                    <span className="num">{t('records.creditedAt', { time: formatDateTime(r.d.time, { seconds: true }) })}</span>
                    <span className="num break">{t('records.txHash', { hash: shortAddress(r.d.txHash) })}</span>
                  </div>
                </li>
              ) : (
                <li key={`w${r.w.id}`}>
                  <div className="a">
                    <span className="net-tag">{r.network}</span>
                    <b className="num">-{formatAmount(r.w.amount)}</b>
                    <span className="st st-wait">{statusLabel(t, r.w)}</span>
                  </div>
                  <div className="d">
                    <span className="num">
                      {r.w.id}
                      {r.w.payoutNo ? ` · ${t('withdraw.rec.payoutNo', { no: r.w.payoutNo })}` : ''}
                      {r.w.txHash ? ` · ${t('withdraw.rec.tx', { hash: shortAddress(r.w.txHash) })}` : ''}
                    </span>
                  </div>
                  <Timeline w={r.w} />
                </li>
              ),
            )}
          </ul>
        )}
      </section>
    </div>
  );
}
