/**
 * 报价服务四页（第 3 批，原型 P8～P11）共用：限额输入、总开关提示框、Secret 只显示一次的弹窗、数字格式。
 */
import { useState } from 'react';
import { Alert, Button, Checkbox, Form, InputNumber, Modal, Typography } from 'antd';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import type { QuoteGate, QuoteKeyCreated, QuoteLimits } from '../../api/types';

export const fmtInt = (n: number | null | undefined) => (n === null || n === undefined ? '—' : n.toLocaleString('en-US'));

/** 秒 → "9 小时 12 分" */
export function duration(t: (k: string, o?: Record<string, unknown>) => string, seconds: number): string {
  if (!seconds) return '0';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  return h ? t('quote.hm', { h, m }) : t('quote.m', { m: Math.max(1, m) });
}

/** 四项限额（高危弹窗里的表单项） */
export function LimitsFields() {
  const { t } = useTranslation();
  const rule = [{ required: true, type: 'integer' as const, min: 1, message: t('quote.positiveInt') }];
  return (
    <div className="q-grid2">
      <Form.Item name="restPerSecond" label={t('quote.restPerSecond')} rules={rule}>
        <InputNumber style={{ width: '100%' }} min={1} precision={0} />
      </Form.Item>
      <Form.Item name="restPerDay" label={t('quote.restPerDay')} rules={rule}>
        <InputNumber style={{ width: '100%' }} min={1} precision={0} />
      </Form.Item>
      <Form.Item name="wsConnections" label={t('quote.wsConnections')} rules={rule}>
        <InputNumber style={{ width: '100%' }} min={1} precision={0} />
      </Form.Item>
      <Form.Item name="wsSymbols" label={t('quote.wsSymbols')} rules={rule}>
        <InputNumber style={{ width: '100%' }} min={1} precision={0} />
      </Form.Item>
    </div>
  );
}

export function limitsOf(v: Record<string, unknown>): QuoteLimits {
  return { restPerSecond: Number(v.restPerSecond), restPerDay: Number(v.restPerDay), wsConnections: Number(v.wsConnections), wsSymbols: Number(v.wsSymbols) };
}

/** 页面顶部"对外开放总开关"状态框（P8） */
export function GateBox({ gate }: { gate: QuoteGate }) {
  const { t } = useTranslation();
  return (
    <Alert
      className="q-gate-box"
      type={gate.open ? 'success' : 'warning'}
      showIcon
      message={gate.open ? t('quote.gateOnTitle') : t('quote.gateOffTitle')}
      description={
        <>
          {gate.open ? t('quote.gateOnDesc') : t('quote.gateOffDesc')} <Link to="/quote-service/license">{t('quote.gotoLicense')}</Link>
        </>
      }
    />
  );
}

/** Secret 只显示这一次：要勾"我已经复制保存好了"才能关（TY-3 都留） */
export function SecretModal({ created, gateOpen, onClose }: { created: QuoteKeyCreated | null; gateOpen: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const [ok, setOk] = useState(false);
  const [err, setErr] = useState(false);
  const [copied, setCopied] = useState(false);
  if (!created) return null;
  const close = () => {
    if (!ok) {
      setErr(true);
      return;
    }
    setOk(false);
    setErr(false);
    setCopied(false);
    onClose();
  };
  const copy = async () => {
    try {
      await navigator.clipboard?.writeText(created.secret);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  };
  return (
    <Modal
      open
      title={t('quote.secretTitle')}
      closable={false}
      maskClosable={false}
      keyboard={false}
      footer={[
        <Button key="copy" onClick={() => void copy()}>
          {t('quote.copySecret')}
        </Button>,
        <Button key="close" type="primary" onClick={close}>
          {t('common.close')}
        </Button>,
      ]}
    >
      <Alert type="warning" showIcon message={t('quote.secretWarn')} description={t('quote.secretWarnDesc')} />
      <dl className="q-kv">
        <dt>Key</dt>
        <dd className="num">{created.key.keyId}</dd>
      </dl>
      <Typography.Paragraph className="q-secret num" copyable={false} aria-label={t('quote.secretLabel')}>
        {created.secret}
      </Typography.Paragraph>
      {copied ? <Typography.Text type="success">{t('quote.copied')}</Typography.Text> : null}
      {created.key.kind === 'EXTERNAL' && !gateOpen ? <Alert type="info" showIcon className="q-mt" message={t('quote.secretGateOff')} /> : null}
      <div className="q-mt">
        <Checkbox checked={ok} onChange={(e) => setOk(e.target.checked)}>
          {t('quote.secretSaved')}
        </Checkbox>
        {err ? (
          <div className="q-err" role="alert">
            {t('quote.secretSavedRequired')}
          </div>
        ) : null}
      </div>
    </Modal>
  );
}
