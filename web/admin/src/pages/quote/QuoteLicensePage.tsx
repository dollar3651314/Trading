/**
 * 授权登记和总开关（第 3 批，原型 P10，需求 03 页第四节，TR3 两道闸）：
 * - 闸 1 对外开放总开关：默认关；只有超管能改；打开要写原因、勾"Amos 已同意打开"（L3，TY-3 都留），写审计；
 * - 闸 2 报价商 × 品种三项授权（交易用途 / 登录后展示 / 转售）：有没有、授权日期、到期日、备注，不存合同原文和价格；
 *   30 天内到期标橙（已发提醒邮件），已到期标红（按没授权处理）。
 */
import { Fragment, useCallback, useState } from 'react';
import { Alert, Button, Checkbox, Empty, Form, Input, Modal, Select, Skeleton, Table } from 'antd';
import { useTranslation } from 'react-i18next';
import { getQuoteLicenses, saveQuoteLicense, setQuoteGate } from '../../api';
import type { QuoteGrant, QuoteLicenseRow } from '../../api/types';
import { useApi } from '../../hooks/useApi';
import { useHighRisk } from '../../hooks/useConfirmDialogs';
import { useAuth } from '../../auth/AuthContext';
import { PageHeader } from '../../components/PageHeader';
import { ErrorState } from '../../components/ErrorState';
import { PermButton } from '../../components/PermButton';
import { Pill } from '../../components/Pill';
import { Num } from '../../components/Num';
import { errorText } from '../../utils/errors';

const FEEDS = [
  { value: 'alltick', label: 'AllTick' },
  { value: 'itick', label: 'iTick' },
  { value: 'binance', label: 'Binance' },
];
const feedName = (f: string) => FEEDS.find((x) => x.value === f)?.label ?? f;
const worst = (r: QuoteLicenseRow) => [r.trading, r.display, r.resale].reduce<'expired' | 'soon' | null>((w, g) => (g.state === 'expired' ? 'expired' : w ?? (g.state === 'soon' ? 'soon' : null)), null);

export function QuoteLicensePage() {
  const { t } = useTranslation();
  const fetcher = useCallback(() => getQuoteLicenses(), []);
  const { data, error, loading, reload } = useApi(fetcher);
  const hr = useHighRisk();
  // 固定的 key：重新加载（页面变成"加载中"）时确认弹窗不被重建
  const hrEl = <Fragment key="q-hr">{hr.element}</Fragment>;
  const { me } = useAuth();
  const isSuper = !!me?.isSuperAdmin;
  const [gateOpen, setGateOpen] = useState(false);

  const header = <PageHeader title={t('quote.licenseTitle')} sub={t('quote.licenseSub')} />;
  if (error) {
    return (
      <>
        {header}
        <div className="card">
          <ErrorState error={error} onRetry={reload} />
        </div>
        {hrEl}
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
        {hrEl}
      </>
    );
  }

  const yn = (g: QuoteGrant) =>
    g.state === 'expired' ? <span className="down">✗ {t('quote.expired')}</span> : g.granted ? <span className="up">✓ {t('quote.yes')}</span> : <span className="muted">✗ {t('quote.no')}</span>;
  const until = (r: QuoteLicenseRow) => {
    const dates = [r.trading, r.display, r.resale].filter((g) => g.granted && g.until).map((g) => g.until as string);
    const min = dates.sort()[0];
    const w = worst(r);
    const left = Math.min(...[r.trading, r.display, r.resale].filter((g) => g.granted && g.daysLeft !== null).map((g) => g.daysLeft as number));
    return (
      <>
        <Num>{min ?? t('quote.noLimit')}</Num>
        {w === 'soon' ? (
          <div>
            <Pill tone="warn">{t('quote.daysLeftMailed', { n: left })}</Pill>
          </div>
        ) : w === 'expired' ? (
          <div>
            <Pill tone="danger">{t('quote.expiredAsNone')}</Pill>
          </div>
        ) : null}
      </>
    );
  };

  const edit = (row?: QuoteLicenseRow) =>
    hr.ask({
      title: row ? t('quote.licenseEditTitle', { feed: feedName(row.feed), symbol: row.symbol }) : t('quote.licenseNewTitle'),
      description: t('quote.licenseDesc'),
      initialValues: {
        feed: row?.feed ?? 'alltick',
        symbol: row?.symbol ?? '',
        uses: row ? (['trading', 'display', 'resale'] as const).filter((u) => row[u].granted) : ['trading', 'display'],
        from: row?.trading.from ?? row?.display.from ?? '',
        until: row?.trading.until ?? row?.display.until ?? '',
        note: row?.note ?? '',
      },
      extra: (
        <>
          <div className="q-grid2">
            <Form.Item name="feed" label={t('quote.feed')} rules={[{ required: true }]}>
              <Select options={FEEDS} />
            </Form.Item>
            <Form.Item name="symbol" label={t('quote.symbol')} rules={[{ required: true, pattern: /^(\*|[A-Z0-9]{1,16})$/, message: t('quote.symbolInvalid') }]}>
              <Input placeholder={t('quote.symbolPh')} />
            </Form.Item>
          </div>
          <Form.Item name="uses" label={t('quote.uses')}>
            <Checkbox.Group
              options={[
                { value: 'trading', label: t('quote.useTrading') },
                { value: 'display', label: t('quote.useDisplay') },
                { value: 'resale', label: t('quote.useResale') },
              ]}
            />
          </Form.Item>
          <div className="q-grid2">
            <Form.Item name="from" label={t('quote.grantFrom')}>
              <Input type="date" />
            </Form.Item>
            <Form.Item name="until" label={t('quote.grantUntil')} rules={[{ required: true, message: t('quote.untilRequired') }]}>
              <Input type="date" />
            </Form.Item>
          </div>
          <Form.Item name="note" label={t('quote.note')}>
            <Input placeholder={t('quote.notePh')} maxLength={500} />
          </Form.Item>
        </>
      ),
      successText: t('quote.licenseSaved'),
      onConfirm: (v) => {
        const uses = new Set((v.uses as string[] | undefined) ?? []);
        const from = String(v.from ?? '') || null;
        const to = String(v.until ?? '') || null;
        const gr = (u: string) => ({ granted: uses.has(u), from: uses.has(u) ? from : null, until: uses.has(u) ? to : null });
        return saveQuoteLicense({ feed: String(v.feed), symbol: String(v.symbol).trim().toUpperCase(), trading: gr('trading'), display: gr('display'), resale: gr('resale'), note: String(v.note ?? ''), reason: v.reason });
      },
      onDone: reload,
    });

  const exp = data.items.filter((r) => worst(r));
  const first = exp.find((r) => worst(r) === 'expired') ?? exp[0];

  return (
    <>
      {header}
      {first ? (
        <section className={`card q-hero${worst(first) === 'expired' ? ' err' : ''}`} aria-label={t('quote.expiryHeroAria')}>
          <div className="t">
            <b>{t('quote.expiryHero', { n: exp.length, feed: feedName(first.feed), symbol: first.symbol })}</b>
            <span>{t('quote.expiryHeroDesc')}</span>
          </div>
          <PermButton perm="quote_license:edit" type="primary" size="large" onClick={() => edit(first)}>
            {t('quote.renew')}
          </PermButton>
        </section>
      ) : null}
      {!data.items.length ? (
        <section className="card q-hero" aria-label={t('quote.noLicenseTitle')}>
          <div className="t">
            <b>{t('quote.noLicenseTitle')}</b>
            <span>{t('quote.noLicenseDesc')}</span>
          </div>
          <PermButton perm="quote_license:edit" type="primary" size="large" onClick={() => edit()}>
            {t('quote.firstLicense')}
          </PermButton>
        </section>
      ) : null}
      <section className="card" aria-label={t('quote.gate1')}>
        <div className="q-card-head">
          <h3>{t('quote.gate1')}</h3>
          <Pill tone="warn">{t('quote.superOnly')}</Pill>
        </div>
        <div className="q-gate">
          <b className={data.gate.open ? 'up' : 'muted'}>{data.gate.open ? t('quote.on') : t('quote.off')}</b>
          <span className="muted small">{data.gate.open ? t('quote.gateOnDesc') : t('quote.gateOffLong')}</span>
          <Button danger type={data.gate.open ? 'default' : 'primary'} onClick={() => setGateOpen(true)}>
            {data.gate.open ? t('quote.gateClose') : t('quote.gateOpen')}
          </Button>
        </div>
        {data.gate.updatedAt ? <p className="muted small">{t('quote.gateLast', { by: data.gate.updatedBy ?? '—', reason: data.gate.reason ?? '—' })}</p> : null}
      </section>
      <section className="card">
        <div className="q-card-head">
          <h3>{t('quote.gate2')}</h3>
          <PermButton perm="quote_license:edit" type="primary" onClick={() => edit()}>
            {t('quote.newLicense')}
          </PermButton>
        </div>
        <p className="muted small">{t('quote.licenseHint')}</p>
        <Table
          size="small"
          rowKey={(r: QuoteLicenseRow) => `${r.feed}|${r.symbol}`}
          pagination={false}
          scroll={{ x: 900 }}
          dataSource={data.items}
          rowClassName={(r) => (worst(r) === 'expired' ? 'row-danger' : worst(r) === 'soon' ? 'row-warn' : '')}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('quote.noLicenseEmpty')} /> }}
          columns={[
            { title: t('quote.feed'), dataIndex: 'feed', render: (v: string) => feedName(v) },
            { title: t('quote.symbol'), dataIndex: 'symbol', render: (v: string) => <Num>{v === '*' ? t('quote.allSymbolsOfFeed') : v}</Num> },
            { title: t('quote.useTrading'), key: 'tr', render: (_: unknown, r: QuoteLicenseRow) => yn(r.trading) },
            { title: t('quote.useDisplay'), key: 'di', render: (_: unknown, r: QuoteLicenseRow) => yn(r.display) },
            { title: t('quote.useResale'), key: 're', render: (_: unknown, r: QuoteLicenseRow) => yn(r.resale) },
            { title: t('quote.grantFrom'), key: 'from', render: (_: unknown, r: QuoteLicenseRow) => <Num>{r.trading.from ?? r.display.from ?? r.resale.from ?? '—'}</Num> },
            { title: t('quote.grantUntil'), key: 'until', render: (_: unknown, r: QuoteLicenseRow) => until(r) },
            { title: t('quote.note'), dataIndex: 'note', render: (v: string | null) => v ?? '' },
            {
              title: t('quote.ops'),
              key: 'ops',
              render: (_: unknown, r: QuoteLicenseRow) => (
                <PermButton perm="quote_license:edit" size="small" onClick={() => edit(r)}>
                  {worst(r) ? t('quote.renew') : t('common.edit')}
                </PermButton>
              ),
            },
          ]}
        />
        <Alert
          className="q-mt"
          type="warning"
          showIcon
          message={t('quote.notTradableTitle')}
          description={`${data.notTradable.length ? data.notTradable.join('、') : t('quote.none')}${data.enforced ? '' : t('quote.notEnforced')}`}
        />
      </section>
      <GateModal open={gateOpen} on={data.gate.open} isSuper={isSuper} onClose={() => setGateOpen(false)} onDone={reload} />
      {hrEl}
    </>
  );
}

/** 打开 / 关闭对外总开关：原因必填；打开还要勾"Amos 已同意打开"；不是超管提示"只有超管能改" */
function GateModal({ open, on, isSuper, onClose, onDone }: { open: boolean; on: boolean; isSuper: boolean; onClose: () => void; onDone: () => void }) {
  const { t } = useTranslation();
  const [form] = Form.useForm();
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<unknown>(null);
  const submit = async () => {
    setErr(null);
    let v: { reason?: string; amos?: boolean };
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    setBusy(true);
    try {
      await setQuoteGate(!on, !!v.amos, String(v.reason ?? '').trim());
      form.resetFields();
      onClose();
      onDone();
    } catch (e) {
      setErr(e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      open={open}
      title={on ? t('quote.gateClose') : t('quote.gateOpen')}
      okText={on ? t('quote.gateCloseOk') : t('quote.gateOpenOk')}
      okButtonProps={{ danger: true, loading: busy }}
      cancelText={t('common.cancel')}
      onOk={() => void submit()}
      onCancel={() => {
        setErr(null);
        form.resetFields();
        onClose();
      }}
      destroyOnClose
      maskClosable={false}
    >
      {!isSuper ? <Alert type="warning" showIcon className="q-mb" message={t('quote.superOnlyTip')} /> : null}
      {on ? (
        <p>{t('quote.gateCloseDesc')}</p>
      ) : (
        <>
          <Alert type="error" showIcon className="q-mb" message={t('quote.gateL3Title')} description={t('quote.gateL3Desc')} />
          <p className="small">{t('quote.gateOpenDesc')}</p>
        </>
      )}
      <Form form={form} layout="vertical" preserve={false} name="quote-gate">
        <Form.Item name="reason" label={on ? t('highRisk.reason') : t('quote.gateReason')} rules={[{ required: true, whitespace: true, message: t('highRisk.reasonRequired') }]}>
          <Input.TextArea rows={3} maxLength={200} showCount />
        </Form.Item>
        {!on ? (
          <Form.Item
            name="amos"
            valuePropName="checked"
            rules={[{ validator: (_r, v) => (v ? Promise.resolve() : Promise.reject(new Error(t('quote.amosRequired')))) }]}
          >
            <Checkbox>{t('quote.amosAgreed')}</Checkbox>
          </Form.Item>
        ) : null}
      </Form>
      <p className="muted small">{t('highRisk.auditNote')}</p>
      {err ? <Alert type="error" showIcon message={t('highRisk.failed')} description={errorText(t, err)} /> : null}
    </Modal>
  );
}
