/**
 * 外部客户和 Key（第 3 批，原型 P8，需求 03 页第三节）：建客户、建 Key（内部测试 / 外部）、轮换、停用；Secret 只显示一次。
 * 总开关关的时候外部 Key 标"总开关关，现在不能用"（调用会被拒 403）。高危操作原因必填、写审计。第一版不做自助注册和客户门户（TD-7 A）。
 */
import { Fragment, useCallback, useState } from 'react';
import { Alert, Empty, Form, Input, Radio, Select, Skeleton, Table } from 'antd';
import { useTranslation } from 'react-i18next';
import { createQuoteClient, createQuoteKey, disableQuoteKey, getQuoteOverview, rotateQuoteKey } from '../../api';
import type { QuoteKey, QuoteKeyCreated } from '../../api/types';
import { useApi } from '../../hooks/useApi';
import { useHighRisk } from '../../hooks/useConfirmDialogs';
import { PageHeader } from '../../components/PageHeader';
import { ErrorState } from '../../components/ErrorState';
import { PermButton } from '../../components/PermButton';
import { Pill } from '../../components/Pill';
import { Num } from '../../components/Num';
import { fullTime } from '../../utils/display';
import { GateBox, SecretModal } from './common';

export function QuoteKeysPage() {
  const { t } = useTranslation();
  const fetcher = useCallback(() => getQuoteOverview(), []);
  const { data, error, loading, reload } = useApi(fetcher);
  const hr = useHighRisk();
  const [secret, setSecret] = useState<QuoteKeyCreated | null>(null);

  const header = <PageHeader title={t('quote.keysTitle')} sub={t('quote.keysSub')} />;
  // 弹窗（Secret 只显示一次、高危确认）在重新加载时也留着，不会因为页面变成"加载中"而消失
  // 固定的 key：加载中、出错、有数据三种返回里都是同一个弹窗，不会因为重新加载被重建（丢掉"已勾选"等状态）
  const modals = (
    <Fragment key="q-modals">
      <SecretModal created={secret} gateOpen={!!data?.gate.open} onClose={() => setSecret(null)} />
      {hr.element}
    </Fragment>
  );
  if (error) {
    return (
      <>
        {header}
        <div className="card">
          <ErrorState error={error} onRetry={reload} />
        </div>
        {modals}
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
        {modals}
      </>
    );
  }

  const newClient = () =>
    hr.ask({
      title: t('quote.newClient'),
      description: t('quote.newClientDesc'),
      extra: (
        <>
          <Form.Item name="name" label={t('quote.clientName')} rules={[{ required: true, whitespace: true, message: t('quote.clientNameRequired') }]}>
            <Input placeholder={t('quote.clientNamePh')} maxLength={100} />
          </Form.Item>
          <Form.Item name="email" label={t('quote.clientEmail')} rules={[{ type: 'email', message: t('quote.emailInvalid') }]}>
            <Input placeholder="name@example.com" maxLength={200} />
          </Form.Item>
          <Form.Item name="note" label={t('quote.note')}>
            <Input maxLength={500} />
          </Form.Item>
        </>
      ),
      confirmText: t('quote.newClientOk'),
      successText: t('quote.clientCreated'),
      onConfirm: (v) => createQuoteClient(String(v.name ?? ''), String(v.email ?? ''), String(v.note ?? ''), v.reason),
      onDone: reload,
    });

  const newKey = () =>
    hr.ask({
      title: t('quote.newKey'),
      description: t('quote.newKeyDesc', { rps: data.defaults.restPerSecond, daily: data.defaults.restPerDay.toLocaleString('en-US'), ws: data.defaults.wsConnections, syms: data.defaults.wsSymbols }),
      initialValues: { clientId: data.clients[data.clients.length - 1]?.id, kind: 'INTERNAL_TEST', symbols: '' },
      extra: (
        <>
          <Form.Item name="clientId" label={t('quote.client')} rules={[{ required: true, message: t('quote.clientRequired') }]}>
            <Select options={data.clients.filter((c) => c.active).map((c) => ({ value: c.id, label: `${c.code} ${c.name}` }))} />
          </Form.Item>
          <Form.Item name="kind" label={t('quote.kind')}>
            <Radio.Group>
              <Radio value="INTERNAL_TEST">{t('quote.kindInternalLong')}</Radio>
              <Radio value="EXTERNAL">{t('quote.kindExternalLong')}</Radio>
            </Radio.Group>
          </Form.Item>
          <Form.Item name="symbols" label={t('quote.symbols')} extra={t('quote.symbolsHint')}>
            <Input placeholder="EURUSD,GBPUSD,XAUUSD" />
          </Form.Item>
        </>
      ),
      confirmText: t('quote.newKeyOk'),
      successText: t('quote.keyCreated'),
      onConfirm: async (v) => {
        const syms = String(v.symbols ?? '')
          .split(/[,，\s]+/)
          .map((s) => s.trim().toUpperCase())
          .filter(Boolean);
        setSecret(await createQuoteKey(Number(v.clientId), String(v.kind), syms, v.reason));
      },
      onDone: reload,
    });

  const rotate = (k: QuoteKey) =>
    hr.ask({
      title: t('quote.rotateTitle', { key: k.keyId }),
      description: t('quote.rotateDesc'),
      confirmText: t('quote.rotateOk'),
      successText: t('quote.rotated'),
      onConfirm: async (v) => setSecret(await rotateQuoteKey(k.keyId, v.reason)),
      onDone: reload,
    });

  const stop = (k: QuoteKey) =>
    hr.ask({
      title: t('quote.disableTitle', { key: k.keyId }),
      description: t('quote.disableDesc'),
      confirmText: t('quote.disableOk'),
      successText: t('quote.disabled'),
      onConfirm: (v) => disableQuoteKey(k.keyId, v.reason),
      onDone: reload,
    });

  const status = (k: QuoteKey) =>
    !k.active ? <Pill tone="muted">{t('quote.stDisabled')}</Pill> : !k.usableNow ? <Pill tone="warn">{t('quote.stGateOff')}</Pill> : <Pill tone="ok">{t('quote.stOk')}</Pill>;

  return (
    <>
      {header}
      <GateBox gate={data.gate} />
      {!data.keyEncReady ? <Alert type="error" showIcon className="q-mb" message={t('quote.encNotReady')} /> : null}
      {data.clients.length === 0 ? (
        <section className="card q-hero ok" aria-label={t('quote.noClientTitle')}>
          <div className="t">
            <b>{t('quote.noClientTitle')}</b>
            <span>{t('quote.noClientDesc')}</span>
          </div>
          <PermButton perm="quote_key:operate" type="primary" size="large" onClick={newClient}>
            {t('quote.firstClient')}
          </PermButton>
        </section>
      ) : null}
      <section className="card">
        <div className="q-card-head">
          <h3>{t('quote.clients')}</h3>
          <PermButton perm="quote_key:operate" onClick={newClient}>
            {t('quote.newClientBtn')}
          </PermButton>
        </div>
        <Table
          size="small"
          rowKey="id"
          pagination={false}
          scroll={{ x: 720 }}
          dataSource={data.clients}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('quote.noClients')} /> }}
          columns={[
            { title: t('quote.code'), dataIndex: 'code', render: (v: string) => <Num>{v}</Num> },
            { title: t('quote.clientName'), dataIndex: 'name' },
            { title: t('quote.clientEmail'), dataIndex: 'email', render: (v: string | null) => v ?? '—' },
            { title: t('quote.status'), dataIndex: 'active', render: (v: boolean) => (v ? <Pill tone="ok">{t('quote.clientActive')}</Pill> : <Pill tone="muted">{t('quote.stDisabled')}</Pill>) },
            { title: t('quote.note'), dataIndex: 'note', render: (v: string | null) => v ?? '' },
            { title: t('quote.keyCount'), dataIndex: 'keyCount', align: 'right' as const },
          ]}
        />
      </section>
      <section className="card">
        <div className="q-card-head">
          <h3>Key</h3>
          <PermButton perm="quote_key:operate" type="primary" onClick={newKey} disabled={!data.clients.length || !data.keyEncReady} disabledReason={t('quote.needClient')}>
            {t('quote.newKeyBtn')}
          </PermButton>
        </div>
        <Table
          size="small"
          rowKey="keyId"
          pagination={false}
          scroll={{ x: 980 }}
          dataSource={data.keys}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('quote.noKeys')} /> }}
          columns={[
            { title: 'Key', dataIndex: 'keyId', render: (v: string) => <Num>{v}</Num> },
            { title: t('quote.client'), dataIndex: 'clientName' },
            { title: t('quote.kind'), dataIndex: 'kind', render: (v: string) => <Pill tone={v === 'EXTERNAL' ? 'info' : 'muted'}>{v === 'EXTERNAL' ? t('quote.kindExternal') : t('quote.kindInternal')}</Pill> },
            { title: t('quote.status'), key: 'st', render: (_: unknown, k: QuoteKey) => status(k) },
            { title: t('quote.symbols'), dataIndex: 'symbols', render: (v: string[]) => (v.length ? v.join('、') : t('quote.allLicensed')) },
            { title: t('quote.createdAt'), dataIndex: 'createdAt', render: (v: string) => <Num>{fullTime(v)}</Num> },
            { title: t('quote.lastUsed'), dataIndex: 'lastUsedAt', render: (v: string | null) => <Num>{v ? fullTime(v) : '—'}</Num> },
            {
              title: t('quote.ops'),
              key: 'ops',
              render: (_: unknown, k: QuoteKey) =>
                k.active ? (
                  <span className="q-ops">
                    <PermButton perm="quote_key:operate" size="small" onClick={() => rotate(k)}>
                      {t('quote.rotate')}
                    </PermButton>
                    <PermButton perm="quote_key:operate" size="small" danger onClick={() => stop(k)}>
                      {t('quote.disable')}
                    </PermButton>
                  </span>
                ) : null,
            },
          ]}
        />
        <p className="muted small q-mt">{t('quote.keysFoot')}</p>
      </section>
      {modals}
    </>
  );
}
