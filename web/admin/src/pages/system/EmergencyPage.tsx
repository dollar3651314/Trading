/**
 * 应急开关（TG 机器人第 5 批，技术方案 TG机器人-11 页第 3 节，TB-34；原型 admin.html"应急开关"页）：
 * 平时关。机器人被封、客户进不来时，只有 Amos 的后台账号能打开（后端配置写死他的账号编号，不写用户名、不靠权限点）；
 * 打开后网页 8082 登录页出现「用 Telegram 登录」（另一个只管登录的机器人），这样登录算安全事件、24 小时不能提现。
 * 开、关都要二次确认（再输后台密码 + 写原因）、写审计、发告警邮件。下面是开关记录（后台和服务器终端的都在）。
 */
import { useCallback } from 'react';
import { Alert, Button, Descriptions, Form, Input, Skeleton, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { getEmergency, setEmergency } from '../../api';
import type { EmergencyChange } from '../../api/types';
import { useApi } from '../../hooks/useApi';
import { useHighRisk } from '../../hooks/useConfirmDialogs';
import { PageHeader } from '../../components/PageHeader';
import { ErrorState } from '../../components/ErrorState';
import { DataTable } from '../../components/DataTable';
import { Pill } from '../../components/Pill';
import { fullTime } from '../../utils/display';

export function EmergencyPage() {
  const { t } = useTranslation();
  const fetcher = useCallback(() => getEmergency(), []);
  const { data, loading, error, reload } = useApi(fetcher);
  const hr = useHighRisk();

  const toggle = () => {
    if (!data) return;
    const turnOn = !data.on;
    hr.ask({
      title: t(turnOn ? 'emergency.askOn' : 'emergency.askOff'),
      description: <div className={turnOn ? 'text-danger' : undefined}>{t(turnOn ? 'emergency.impactOn' : 'emergency.impactOff')}</div>,
      extra: (
        <Form.Item name="password" label={t('emergency.password')} rules={[{ required: true, message: t('emergency.passwordRequired') }]}>
          <Input.Password autoComplete="current-password" />
        </Form.Item>
      ),
      confirmText: t(turnOn ? 'emergency.confirmOn' : 'emergency.confirmOff'),
      successText: t(turnOn ? 'emergency.doneOn' : 'emergency.doneOff'),
      onConfirm: (v) => setEmergency(turnOn, v.reason, String(v.password ?? '')),
      onDone: reload,
    });
  };

  if (error) return <ErrorState error={error} onRetry={reload} />;
  return (
    <>
      <PageHeader title={t('emergency.title')} sub={t('emergency.sub')} />
      {loading || !data ? (
        <Skeleton active />
      ) : (
        <>
          <Descriptions bordered column={1} size="middle" style={{ marginBottom: 16 }}>
            <Descriptions.Item label={t('emergency.state')}>
              <span data-testid="emergency-state">
                {data.on ? <Pill tone="danger">{t('emergency.on')}</Pill> : <Pill tone="muted">{t('emergency.off')}</Pill>}
              </span>
              {data.changedAt ? <Typography.Text type="secondary"> {t('emergency.changedLine', { time: fullTime(data.changedAt), who: data.changedBy ?? '—' })}</Typography.Text> : null}
            </Descriptions.Item>
            <Descriptions.Item label={t('emergency.who')}>{t('emergency.whoText')}</Descriptions.Item>
            <Descriptions.Item label={t('emergency.fallback')}>
              {t('emergency.fallbackText')} <code>sudo trading set TR_TG_EMERGENCY_LOGIN {data.on ? 'off' : 'on'} "原因"</code>
            </Descriptions.Item>
          </Descriptions>
          {!data.ownerConfigured ? <Alert type="warning" showIcon style={{ marginBottom: 16 }} message={t('emergency.ownerMissing')} /> : null}
          {data.ownerConfigured && !data.canOperate ? (
            <div data-testid="emergency-not-owner">
              <Alert type="info" showIcon style={{ marginBottom: 16 }} message={t('emergency.notOwner')} />
            </div>
          ) : null}
          <Button type="primary" danger={!data.on} disabled={!data.canOperate} onClick={toggle} data-testid="emergency-toggle" style={{ marginBottom: 16 }}>
            {t(data.on ? 'emergency.turnOff' : 'emergency.turnOn')}
          </Button>
          <Typography.Title level={5}>{t('emergency.history')}</Typography.Title>
          <DataTable<EmergencyChange>
            rowKey="id"
            dataSource={data.history}
            emptyText={t('emergency.noHistory')}
            columns={[
              { title: t('emergency.col.time'), dataIndex: 'at', render: (v: string) => fullTime(v) },
              { title: t('emergency.col.who'), dataIndex: 'actorName', render: (v: string, r) => (r.actor === 'server' ? `${v}（${t('emergency.serverTerminal')}）` : v) },
              { title: t('emergency.col.action'), dataIndex: 'on', render: (v: boolean) => t(v ? 'emergency.actOn' : 'emergency.actOff') },
              { title: t('emergency.col.reason'), dataIndex: 'reason' },
            ]}
          />
        </>
      )}
      {hr.element}
    </>
  );
}
