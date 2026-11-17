/**
 * 钱包提供方（第 1 批，原型 P7、需求 02 第五节）：各家提供方的接口状态、最后一次成功、回调、可用余额（TopUP 在用；Cobo、Fireblocks"未接入"）；
 * 三条充值网络的开关（全平台设置，关掉的客户看不到，已发地址照常入账）；切换提供方（两步：先看影响，再填原因确认）；操作记录。
 * 网络开关、切换都是高危操作：原因必填、写审计（TY-3）。余额告警线 TY-6 先不设（Amos 没给数字）。
 */
import { useCallback } from 'react';
import { Alert, App, Button, Empty, Skeleton, Switch } from 'antd';
import { useTranslation } from 'react-i18next';
import { checkPayment, getPayment, setPaymentNetwork, switchPaymentProvider } from '../../api';
import type { PaymentProvider } from '../../api/types';
import { useApi } from '../../hooks/useApi';
import { useAction, useHighRisk } from '../../hooks/useConfirmDialogs';
import { useAuth } from '../../auth/AuthContext';
import { PageHeader } from '../../components/PageHeader';
import { ErrorState } from '../../components/ErrorState';
import { PermButton } from '../../components/PermButton';
import { ChainTag, Pill } from '../../components/Pill';
import { Num } from '../../components/Num';
import { formatAmount } from '../../utils/money';
import { fullTime } from '../../utils/display';

export function ProviderPage() {
  const { t } = useTranslation();
  const { modal } = App.useApp();
  const fetcher = useCallback(() => getPayment(), []);
  const { data, error, loading, reload } = useApi(fetcher);
  const run = useAction();
  const hr = useHighRisk();
  const { can } = useAuth();

  if (error) {
    return (
      <>
        <PageHeader title={t('provider.title')} sub={t('provider.sub')} />
        <div className="card">
          <ErrorState error={error} onRetry={reload} />
        </div>
      </>
    );
  }
  if (!data || loading) {
    return (
      <>
        <PageHeader title={t('provider.title')} sub={t('provider.sub')} />
        <div className="card">
          <Skeleton active paragraph={{ rows: 8 }} />
        </div>
      </>
    );
  }

  const cur = data.providers.find((p) => p.current);
  const down = cur && cur.connected && !cur.up;

  const toggle = (network: string, open: boolean) =>
    hr.ask({
      title: open ? t('provider.netOnTitle', { network }) : t('provider.netOffTitle', { network }),
      description: open ? t('provider.netOnDesc', { network }) : t('provider.netOffDesc', { network }),
      confirmText: open ? t('provider.netOn') : t('provider.netOff'),
      successText: t('provider.saved'),
      onConfirm: (v) => setPaymentNetwork(network, open, v.reason),
      onDone: reload,
    });

  const switchTo = (p: PaymentProvider) =>
    modal.confirm({
      title: t('provider.switchStep1', { from: cur?.name ?? '—', to: p.name }),
      content: (
        <ul className="pv-impact">
          <li>{t('provider.impactNew')}</li>
          <li>{t('provider.impactOld')}</li>
          <li>{t('provider.impactPayout')}</li>
        </ul>
      ),
      okText: t('provider.next'),
      cancelText: t('common.cancel'),
      onOk: () =>
        hr.ask({
          title: t('provider.switchStep2', { from: cur?.name ?? '—', to: p.name }),
          description: t('provider.switchConfirm'),
          confirmText: t('provider.switch'),
          successText: t('provider.switched', { to: p.name }),
          onConfirm: (v) => switchPaymentProvider(p.code, v.reason),
          onDone: reload,
        }),
    });

  return (
    <>
      <PageHeader title={t('provider.title')} sub={t('provider.sub')} />
      {down ? (
        <Alert
          type="error"
          showIcon
          message={t('provider.downTitle', { name: cur.name, time: cur.lastOkAt ? fullTime(cur.lastOkAt) : '—' })}
          description={t('provider.downDesc')}
          action={
            <Button type="primary" onClick={() => run(() => checkPayment(), t('provider.checked'), reload)}>
              {t('provider.recheck')}
            </Button>
          }
        />
      ) : null}
      <section className="pcards" aria-label={t('provider.list')}>
        {data.providers.map((p) => (
          <div key={p.code} className={`card pcard${p.current ? ' cur' : ''}${!p.connected ? ' off' : ''}${p.current && p.connected && !p.up ? ' bad' : ''}`}>
            <h3>
              {p.name}{' '}
              {p.current ? <Pill tone="info">{t('provider.current')}</Pill> : p.connected ? <Pill tone="warn">{t('provider.connected')}</Pill> : <Pill tone="muted">{t('provider.notConnected')}</Pill>}
            </h3>
            {p.connected ? (
              <dl className="kv">
                <dt>{t('provider.api')}</dt>
                <dd>{p.up ? <span className="up">{t('provider.up')}</span> : <b className="text-danger">{t('provider.down')}</b>}</dd>
                <dt>{t('provider.lastOk')}</dt>
                <dd>
                  <Num>{p.lastOkAt ? fullTime(p.lastOkAt) : '—'}</Num>
                </dd>
                <dt>{t('provider.lastCallback')}</dt>
                <dd>
                  <Num>{p.lastCallbackAt ? fullTime(p.lastCallbackAt) : '—'}</Num>
                </dd>
                <dt>{t('provider.available')}</dt>
                <dd>{p.current ? (p.available ? <Num>{formatAmount(p.available)} USDT</Num> : t('provider.availableUnknown')) : '—'}</dd>
                <dt>{t('provider.byChain')}</dt>
                <dd className="muted">{t('provider.byChainTbd')}</dd>
              </dl>
            ) : (
              <p className="muted">{t('provider.placeholder')}</p>
            )}
            {p.connected && !p.current ? (
              <PermButton perm="provider:operate" danger onClick={() => switchTo(p)}>
                {t('provider.switchTo')}
              </PermButton>
            ) : null}
            {p.current ? (
              <Button size="small" onClick={() => run(() => checkPayment(), t('provider.checked'), reload)}>
                {t('provider.recheck')}
              </Button>
            ) : null}
          </div>
        ))}
      </section>
      <section className="card">
        <h3>{t('provider.networks')}</h3>
        <p className="muted small">{t('provider.networksHint')}</p>
        {data.networks.map((n) => (
          <div className="pv-net" key={n.network}>
            <ChainTag chain={n.network} />
            <span className={n.open ? 'up' : 'muted'}>{n.open ? t('provider.on') : t('provider.off')}</span>
            <Switch checked={n.open} disabled={!can('provider:operate')} aria-label={t('provider.netSwitch', { network: n.network })} onChange={(v) => toggle(n.network, v)} />
          </div>
        ))}
      </section>
      <section className="card">
        <h3>{t('provider.alertLine')}</h3>
        <p className="muted small">{t('provider.alertLineTbd')}</p>
      </section>
      <section className="card">
        <h3>{t('provider.audits')}</h3>
        {data.audits.length ? (
          <table className="pv-audit">
            <thead>
              <tr>
                <th>{t('provider.auditAt')}</th>
                <th>{t('provider.auditAdmin')}</th>
                <th>{t('provider.auditAction')}</th>
                <th>{t('provider.auditReason')}</th>
              </tr>
            </thead>
            <tbody>
              {data.audits.map((a) => (
                <tr key={a.id}>
                  <td>
                    <Num>{fullTime(a.at)}</Num>
                  </td>
                  <td>{a.admin}</td>
                  <td>{t(`provider.action.${a.action}`, { detail: a.detail })}</td>
                  <td>{a.reason}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('provider.auditsEmpty')} />
        )}
      </section>
      {hr.element}
    </>
  );
}
