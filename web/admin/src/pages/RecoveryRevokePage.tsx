/**
 * 告警邮件里的找回申请撤销链接（TG 机器人第 6 批，决定清单 TB-37 ②）：不用登录（Amos 可能进不了后台）。
 * 链接是 /admin/recovery-revoke#t=令牌：令牌在 # 后面（不进服务器日志），打开页面先把它从地址栏去掉，再问后端是哪一份申请（不用掉令牌）；
 * 点「撤销这份申请」才真的撤销（邮件客户端预取链接不会误撤销）。只能撤销，没有批准的入口；令牌 24 小时有效、只能用一次。
 */
import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Descriptions, Result, Spin } from 'antd';
import { useTranslation } from 'react-i18next';
import { peekRecoveryRevoke, revokeRecoveryByLink } from '../api';
import type { RevokeLinkResult, RevokePeek } from '../api/types';
import { errorText } from '../utils/errors';
import { fullTime } from '../utils/display';

/** 从 #t=令牌 里取令牌 */
export function revokeTokenFromHash(hash: string): string | null {
  const m = /^#?(?:.*&)?t=([A-Za-z0-9_-]{20,100})(?:&|$)/.exec(hash);
  return m ? m[1] : null;
}

export function RecoveryRevokePage() {
  const { t } = useTranslation();
  const token = useRef<string | null>(null);
  const [peek, setPeek] = useState<RevokePeek | null>(null);
  const [result, setResult] = useState<RevokeLinkResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (token.current === null) {
      token.current = revokeTokenFromHash(window.location.hash) ?? '';
      window.history.replaceState(null, '', window.location.pathname);
    }
    if (!token.current) {
      setPeek({ valid: false, id: null, kind: null, customerId: null, status: null, executeAt: null });
      return;
    }
    peekRecoveryRevoke(token.current).then(setPeek, (e) => setError(errorText(t, e)));
  }, [t]);

  const revoke = async () => {
    if (!token.current) return;
    setBusy(true);
    setError(null);
    try {
      setResult(await revokeRecoveryByLink(token.current));
    } catch (e) {
      setError(errorText(t, e));
    } finally {
      setBusy(false);
    }
  };

  let body;
  if (result) {
    body = (
      <div data-testid="revoke-result">
        <Result
          status={result === 'cancelled' ? 'success' : 'warning'}
          title={t(`revokeLink.result.${result}`, { id: peek?.id ?? '' })}
          subTitle={result === 'cancelled' ? t('revokeLink.doneHint') : t('revokeLink.serverHint')}
        />
      </div>
    );
  } else if (error) {
    body = <Alert type="error" showIcon message={error} />;
  } else if (!peek) {
    body = <Spin />;
  } else if (!peek.valid) {
    body = (
      <div data-testid="revoke-invalid">
        <Result status="warning" title={t('revokeLink.invalid')} subTitle={t('revokeLink.serverHint')} />
      </div>
    );
  } else {
    body = (
      <>
        <Descriptions bordered column={1} size="small" style={{ marginBottom: 16 }}>
          <Descriptions.Item label={t('revokeLink.id')}>#{peek.id}</Descriptions.Item>
          <Descriptions.Item label={t('revokeLink.kind')}>{t(`recoveries.kind.${peek.kind}`)}</Descriptions.Item>
          <Descriptions.Item label={t('revokeLink.customer')}>#{peek.customerId}</Descriptions.Item>
          <Descriptions.Item label={t('revokeLink.status')}>{t(`recoveries.status.${peek.status}`)}</Descriptions.Item>
          {peek.executeAt ? <Descriptions.Item label={t('revokeLink.executeAt')}>{fullTime(peek.executeAt)}</Descriptions.Item> : null}
        </Descriptions>
        <Alert type="warning" showIcon style={{ marginBottom: 16 }} message={t('revokeLink.impact')} />
        <Button type="primary" danger block loading={busy} onClick={() => void revoke()} data-testid="revoke-confirm">
          {t('revokeLink.confirm')}
        </Button>
      </>
    );
  }
  return (
    <div style={{ maxWidth: 520, margin: '40px auto', padding: '0 16px' }}>
      <Card title={t('revokeLink.title')}>{body}</Card>
    </div>
  );
}
