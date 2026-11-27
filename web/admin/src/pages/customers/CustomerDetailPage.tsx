/**
 * 客户详情：资料、KYC、账户余额、持仓、最近资金流水；冻结/解冻、调整余额（高危）。
 * v2 E1：编辑资料（高危，必须填原因）：姓名（名、中间名、姓）、出生日期、国籍；邮箱不能改，状态只能用冻结 / 解冻改。
 *   校验和客户端注册一致（utils/validators.ts）；KYC 已通过的客户允许改，但弹窗里提示"修改会和证件不一致"，审计里也会标出。
 * v2 E6：资料里显示最近登录 IP 和时间。
 */
import { useCallback } from 'react';
import { Alert, Button, Descriptions, Form, Input, Radio, Select, Skeleton, Space } from 'antd';
import { ArrowLeftOutlined, LockOutlined, UnlockOutlined, DollarOutlined, EditOutlined } from '@ant-design/icons';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { adjustBalance, cancelHeldWithdrawal, getAdjustLimits, getCustomer, releaseHeldWithdrawal, setCustomerFrozen, unfreezeCustomerWithdraw, updateCustomerProfile } from '../../api';
import { freezesOf, type ClosedPosition, type HeldWithdrawal, type LedgerEntry, type Position } from '../../api/types';
import { closedColumns } from '../trading/closedColumns';
import { useApi } from '../../hooks/useApi';
import { useHighRisk } from '../../hooks/useConfirmDialogs';
import { PageHeader } from '../../components/PageHeader';
import { ErrorState } from '../../components/ErrorState';
import { DataTable } from '../../components/DataTable';
import { PermButton } from '../../components/PermButton';
import { Pill } from '../../components/Pill';
import { Amount, Num } from '../../components/Num';
import { customerTone, kycTone } from '../../utils/status';
import { formatAmount, subtractAmounts } from '../../utils/money';
import { DateFields } from '../../components/DateFields';
import { adjustAmountProblems, birthDateProblems, nameProblems, nationalityProblems } from '../../utils/validators';
import { contact, fullTime, shortTime } from '../../utils/display';

export function CustomerDetailPage() {
  const { t, i18n } = useTranslation();
  const { id } = useParams();
  const navigate = useNavigate();
  const customerId = Number(id);
  const fetcher = useCallback(() => getCustomer(customerId), [customerId]);
  const { data: c, loading, error, reload } = useApi(fetcher);
  const hr = useHighRisk();

  const askFreeze = () => {
    if (!c) return;
    const freezing = c.status === 'active';
    hr.ask({
      title: freezing ? t('customers.freeze') : t('customers.unfreeze'),
      description: (
        <>
          <div>{t('customers.targetLine', { name: c.name, id: c.id, email: contact(c.email, c.telegramUsername) })}</div>
          <div>{freezing ? t('customers.freezeImpact') : t('customers.unfreezeImpact')}</div>
        </>
      ),
      confirmText: freezing ? t('customers.confirmFreeze') : t('customers.confirmUnfreeze'),
      onConfirm: (v) => setCustomerFrozen(c.id, freezing, v.reason),
      onDone: reload,
    });
  };

  /** 第 4 批：人工解除提现冻结（冻结提现只能人工解冻；权限 withdraw:unfreeze，高危、重新输入管理员密码、填原因、写审计、告警邮件） */
  const askWithdrawUnfreeze = () => {
    if (!c?.withdrawSafety?.withdrawFrozen) return;
    const list = freezesOf(c.withdrawSafety);
    hr.ask({
      title: t('customers.wdSafety.unfreeze'),
      description: (
        <>
          <div>{t('customers.targetLine', { name: c.name, id: c.id, email: contact(c.email, c.telegramUsername) })}</div>
          {list.map((f) => (
            <div key={f.source}>{t('customers.wdSafety.frozenLine', { source: t(`customers.wdSafety.source.${f.source}`), time: f.at ? fullTime(f.at) : '—' })}</div>
          ))}
          <div>{t('customers.wdSafety.unfreezeImpact')}</div>
          {list.some((f) => f.source === 'recovery' || f.source === 'dispute') ? <div className="text-danger">{t('customers.wdSafety.recoveryWarn')}</div> : null}
        </>
      ),
      initialValues: { source: list.length === 1 ? list[0].source : undefined },
      extra: (
        <>
        <Form.Item name="source" label={t('customers.wdSafety.which')} rules={[{ required: true, message: t('customers.wdSafety.whichRequired') }]}>
          <Select
            aria-label={t('customers.wdSafety.which')}
            options={[...list.map((f) => ({ value: f.source, label: t(`customers.wdSafety.source.${f.source}`) })), ...(list.length > 1 ? [{ value: 'all', label: t('customers.wdSafety.all') }] : [])]}
          />
        </Form.Item>
        <Form.Item name="password" label={t('customers.wdSafety.password')} extra={t('customers.wdSafety.passwordHint')} rules={[{ required: true, message: t('customers.wdSafety.passwordRequired') }]}>
          <Input.Password autoComplete="current-password" />
        </Form.Item>
        </>
      ),
      confirmText: t('customers.wdSafety.confirmUnfreeze'),
      onConfirm: (v) => unfreezeCustomerWithdraw(c.id, v.reason, String(v.password ?? ''), String(v.source ?? 'all')),
      onDone: reload,
    });
  };

  /** 4b 审查 🟡1：冻结时暂停的提现，逐笔"继续放款"或"取消并退回"（高危、要原因） */
  const askHeld = (h: HeldWithdrawal, cancel: boolean) =>
    hr.ask({
      title: t(cancel ? 'customers.held.cancel' : 'customers.held.release'),
      description: (
        <>
          <div>{t('customers.held.target', { id: h.id, amount: formatAmount(h.amount), address: h.address })}</div>
          <div>{t(cancel ? 'customers.held.cancelImpact' : 'customers.held.releaseImpact')}</div>
        </>
      ),
      confirmText: t(cancel ? 'customers.held.confirmCancel' : 'customers.held.confirmRelease'),
      onConfirm: (v) => (cancel ? cancelHeldWithdrawal(h.id, v.reason) : releaseHeldWithdrawal(h.id, v.reason)),
      onDone: reload,
    });

  /** 表单校验：把校验函数返回的翻译键拼成一条提示（一次显示这个字段的全部问题） */
  const rule = (problems: (v: string) => string[]) => ({
    validator: (_r: unknown, value: string | undefined) => {
      const p = problems(value ?? '');
      return p.length ? Promise.reject(new Error(p.map((k) => t(k)).join('；'))) : Promise.resolve();
    },
  });

  const askEdit = () => {
    if (!c) return;
    const kycApproved = c.kyc.status === 'approved';
    hr.ask({
      title: t('customers.editProfile'),
      description: (
        <>
          <div>{t('customers.targetLine', { name: c.name, id: c.id, email: contact(c.email, c.telegramUsername) })}</div>
          <div>{t('customers.editProfileImpact')}</div>
        </>
      ),
      initialValues: {
        firstName: c.firstName ?? '',
        middleName: c.middleName ?? '',
        lastName: c.lastName ?? '',
        birthDate: c.birthDate ?? '',
        nationality: c.nationality ?? '',
      },
      extra: (
        <div className="profile-form">
          {kycApproved ? <Alert type="warning" showIcon style={{ marginBottom: 12 }} message={t('customers.kycApprovedEditWarning')} /> : null}
          <Form.Item label={t('customers.email')} extra={t('customers.emailReadOnly')}>
            <Input value={c.email ?? ''} disabled />
          </Form.Item>
          <div className="name-row">
            <Form.Item name="firstName" label={t('customers.firstName')} required rules={[rule((v) => nameProblems(v, true))]}>
              <Input autoComplete="off" />
            </Form.Item>
            <Form.Item name="middleName" label={t('customers.middleName')} rules={[rule((v) => nameProblems(v, false))]}>
              <Input autoComplete="off" />
            </Form.Item>
            <Form.Item name="lastName" label={t('customers.lastName')} required rules={[rule((v) => nameProblems(v, true))]}>
              <Input autoComplete="off" />
            </Form.Item>
          </div>
          <Form.Item name="birthDate" label={t('customers.birthDate')} required extra={t('customers.birthDateHint')} rules={[rule((v) => birthDateProblems(v))]}>
            <DateFields groupLabel={t('customers.birthDate')} />
          </Form.Item>
          <Form.Item
            name="nationality"
            label={t('customers.nationality')}
            required
            extra={t('customers.nationalityHint')}
            normalize={(v: string) => (v ?? '').toUpperCase()}
            rules={[rule(nationalityProblems)]}
          >
            <Input maxLength={2} style={{ width: 120 }} autoComplete="off" />
          </Form.Item>
        </div>
      ),
      confirmText: t('customers.confirmEditProfile'),
      onConfirm: (v) =>
        updateCustomerProfile(
          c.id,
          {
            firstName: String(v.firstName ?? '').trim(),
            middleName: String(v.middleName ?? '').trim(),
            lastName: String(v.lastName ?? '').trim(),
            birthDate: String(v.birthDate ?? '').trim(),
            nationality: String(v.nationality ?? '').trim(),
          },
          v.reason,
        ),
      onDone: reload,
    });
  };

  const askAdjust = async () => {
    if (!c) return;
    const limits = await getAdjustLimits();
    const remaining = subtractAmounts(limits.dailyLimit, limits.usedToday);
    hr.ask({
      title: t('customers.adjust'),
      description: (
        <>
          <div>{t('customers.adjustTarget', { name: c.name, id: c.id, balance: formatAmount(c.account.balance) })}</div>
          <div>
            {t('customers.adjustLimits', {
              single: formatAmount(limits.singleLimit),
              used: formatAmount(limits.usedToday),
              daily: formatAmount(limits.dailyLimit),
              remaining: formatAmount(remaining),
            })}
          </div>
        </>
      ),
      initialValues: { direction: 'credit' },
      extra: (
        <>
          <Form.Item name="direction" label={t('customers.direction')} rules={[{ required: true }]}>
            <Radio.Group
              options={[
                { value: 'credit', label: t('customers.credit') },
                { value: 'debit', label: t('customers.debit') },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="amount"
            label={t('customers.adjustAmount')}
            required
            rules={[
              {
                validator: (_r, value: string | undefined) => {
                  const p = adjustAmountProblems(value ?? '', limits.singleLimit, remaining);
                  return p.length
                    ? Promise.reject(new Error(p.map((k) => t(k, { single: formatAmount(limits.singleLimit), remaining: formatAmount(remaining) })).join('；')))
                    : Promise.resolve();
                },
              },
            ]}
          >
            <Input inputMode="decimal" suffix="USDT" placeholder="0.00" />
          </Form.Item>
        </>
      ),
      confirmText: t('customers.confirmAdjust'),
      onConfirm: (v) => adjustBalance(c.id, v.direction as 'credit' | 'debit', String(v.amount).trim(), v.reason),
      onDone: reload,
    });
  };

  return (
    <>
      <PageHeader
        title={
          <Space>
            <Button type="text" icon={<ArrowLeftOutlined />} aria-label={t('common.back')} onClick={() => navigate('/customers')} />
            {c ? <span className="ellipsis" style={{ maxWidth: 360 }} title={c.name}>{c.name}</span> : t('customers.detail')}
            {c ? <Pill tone={customerTone[c.status]}>{t(`customerStatus.${c.status}`)}</Pill> : null}
          </Space>
        }
        extra={
          c ? (
            <>
              <PermButton perm="customer:edit" icon={<EditOutlined />} onClick={askEdit}>
                {t('customers.editProfile')}
              </PermButton>
              <PermButton perm="customer:freeze" danger icon={c.status === 'active' ? <LockOutlined /> : <UnlockOutlined />} onClick={askFreeze}>
                {c.status === 'active' ? t('customers.freeze') : t('customers.unfreeze')}
              </PermButton>
              <PermButton perm="customer:adjust_balance" icon={<DollarOutlined />} onClick={() => void askAdjust()}>
                {t('customers.adjust')}
              </PermButton>
            </>
          ) : null
        }
      />
      {error ? (
        <div className="card">
          <ErrorState error={error} onRetry={reload} />
        </div>
      ) : !c || loading ? (
        <div className="card card-pad">
          <Skeleton active paragraph={{ rows: 8 }} />
        </div>
      ) : (
        <>
          {c.status === 'frozen' ? <Alert type="error" showIcon message={t('customers.frozenBanner')} description={c.frozenReason ? t('customers.frozenReasonLine', { reason: c.frozenReason }) : undefined} /> : null}
          {c.heldWithdrawals?.length ? (
            <div className="card card-pad" role="group" aria-label={t('customers.held.title')}>
              <h2 className="card-title">{t('customers.held.title')}</h2>
              <Alert type="warning" showIcon message={t('customers.held.note')} />
              {c.heldWithdrawals.map((h) => (
                <div key={h.id} className="held-row" data-testid={`held-${h.id}`}>
                  <span>
                    <Num>{h.id}</Num> · {t('customers.held.line', { amount: formatAmount(h.amount), chain: h.chain, time: fullTime(h.heldAt), reason: h.reason })}
                    {h.sentBefore ? <Pill tone="warn">{t('customers.held.sentBefore')}</Pill> : null}
                  </span>
                  <Space>
                    <PermButton perm="withdrawal:review" size="small" onClick={() => askHeld(h, false)}>
                      {t('customers.held.release')}
                    </PermButton>
                    <PermButton perm="withdrawal:emergency_cancel" size="small" danger disabled={h.sentBefore} disabledReason={t('customers.held.sentBefore')} onClick={() => askHeld(h, true)}>
                      {t('customers.held.cancel')}
                    </PermButton>
                  </Space>
                </div>
              ))}
            </div>
          ) : null}
          <section className="detail-grid" aria-label={t('customers.account')}>
            <div className="card stat">
              <div className="k">{t('customers.balance')}</div>
              <div className="v"><Amount value={c.account.balance} /></div>
            </div>
            <div className="card stat">
              <div className="k">{t('customers.usedMargin')}</div>
              <div className="v"><Amount value={c.account.usedMargin} /></div>
            </div>
            <div className="card stat">
              <div className="k">{t('customers.available')}</div>
              <div className="v"><Amount value={c.account.available} /></div>
            </div>
            <div className="card stat">
              <div className="k">{t('customers.floatingPnl')}</div>
              <div className="v"><Amount value={c.account.floatingPnl} signed /></div>
              <div className="muted">
                {t('customers.frozenWithdraw')} <Num>{formatAmount(c.account.frozenWithdraw)}</Num>
              </div>
            </div>
          </section>

          <div className="card card-pad">
            <h2 className="card-title">{t('customers.profile')}</h2>
            <Descriptions size="small" column={{ xs: 1, sm: 2, lg: 3 }}>
              <Descriptions.Item label={t('customers.id')}><Num>{c.id}</Num></Descriptions.Item>
              <Descriptions.Item label={t('customers.email')}>{c.email ?? '—'}</Descriptions.Item>
              {c.telegramId && <Descriptions.Item label="Telegram">{contact(null, c.telegramUsername)} · {c.telegramId}</Descriptions.Item>}
              {c.withdrawSafety ? (
                <Descriptions.Item label={t('customers.wdSafety.title')}>
                  <Space size={4} wrap>
                    <Pill tone={c.withdrawSafety.pinSet ? 'ok' : 'muted'}>{t(c.withdrawSafety.pinSet ? 'customers.wdSafety.pinSet' : 'customers.wdSafety.pinNotSet')}</Pill>
                    {c.withdrawSafety.pinLockedUntil ? <Pill tone="warn">{t('customers.wdSafety.pinLocked', { time: fullTime(c.withdrawSafety.pinLockedUntil) })}</Pill> : null}
                    {c.withdrawSafety.withdrawFrozen ? (
                      <>
                        {freezesOf(c.withdrawSafety).map((f) => (
                          <Pill key={f.source} tone="danger">
                            {t('customers.wdSafety.frozen', { source: t(`customers.wdSafety.source.${f.source}`) })}
                          </Pill>
                        ))}
                        <PermButton perm="withdraw:unfreeze" size="small" danger icon={<UnlockOutlined />} onClick={askWithdrawUnfreeze}>
                          {t('customers.wdSafety.unfreeze')}
                        </PermButton>
                      </>
                    ) : null}
                  </Space>
                </Descriptions.Item>
              ) : null}
              <Descriptions.Item label={t('customers.nationality')}>{c.nationality}</Descriptions.Item>
              <Descriptions.Item label={t('customers.firstName')}>{c.firstName || '—'}</Descriptions.Item>
              <Descriptions.Item label={t('customers.middleName')}>{c.middleName || '—'}</Descriptions.Item>
              <Descriptions.Item label={t('customers.lastName')}>{c.lastName || '—'}</Descriptions.Item>
              <Descriptions.Item label={t('customers.birthDate')}><Num>{c.birthDate ?? '—'}</Num></Descriptions.Item>
              <Descriptions.Item label={t('customers.registeredAt')}><Num>{fullTime(c.registeredAt)}</Num></Descriptions.Item>
              <Descriptions.Item label={t('customers.lastLogin')}>{c.lastLoginAt ? <Num>{fullTime(c.lastLoginAt)}</Num> : t('customers.neverLogin')}</Descriptions.Item>
              <Descriptions.Item label={t('customers.lastLoginIp')}>
                {c.lastLoginIp ? (
                  <span>
                    <Num>{c.lastLoginIp}</Num>
                    {c.lastLoginIpAt ? <span className="muted"> · <Num>{fullTime(c.lastLoginIpAt)}</Num></span> : null}
                  </span>
                ) : (
                  '—'
                )}
              </Descriptions.Item>
            </Descriptions>
          </div>

          <div className="card card-pad">
            <h2 className="card-title">
              <span>{t('customers.kycTitle')}</span>
              {c.kyc.status === 'pending' ? <Link to="/kyc">{t('customers.goKyc')}</Link> : null}
            </h2>
            <Descriptions size="small" column={{ xs: 1, sm: 2, lg: 3 }}>
              <Descriptions.Item label={t('customers.kyc')}>
                <Pill tone={kycTone[c.kyc.status]}>{t(`kycStatus.${c.kyc.status}`)}</Pill>
              </Descriptions.Item>
              <Descriptions.Item label={t('kyc.docType')}>{c.kyc.docType ? t(`kyc.doc.${c.kyc.docType}`) : '—'}</Descriptions.Item>
              <Descriptions.Item label={t('kyc.docNumber')}>{c.kyc.docNumber ? <Num>{c.kyc.docNumber}</Num> : '—'}</Descriptions.Item>
              <Descriptions.Item label={t('kyc.reviewedAt')}>{c.kyc.reviewedAt ? <Num>{fullTime(c.kyc.reviewedAt)}</Num> : '—'}</Descriptions.Item>
              {c.kyc.rejectReason ? <Descriptions.Item label={t('kyc.rejectReason')}>{c.kyc.rejectReason}</Descriptions.Item> : null}
            </Descriptions>
          </div>

          <div className="card card-pad">
            <h2 className="card-title">{t('customers.positionsTitle', { count: c.positions.length })}</h2>
            <DataTable<Position>
              bare
              rowKey="id"
              dataSource={c.positions}
              emptyText={t('customers.noPositions')}
              columns={[
                { title: t('trading.positionId'), dataIndex: 'id', render: (v: string) => <Num>{v}</Num> },
                { title: t('trading.symbol'), dataIndex: 'symbol', render: (v: string) => <b>{v}</b> },
                { title: t('trading.side'), dataIndex: 'side', render: (v: string) => <span className={v === 'buy' ? 'up' : 'down'}>{t(`side.${v}`)}</span> },
                { title: t('trading.lots'), dataIndex: 'lots', align: 'right', render: (v: string) => <Num>{v}</Num> },
                { title: t('trading.openPrice'), dataIndex: 'openPrice', align: 'right', render: (v: string) => <Num>{v}</Num> },
                { title: t('trading.margin'), dataIndex: 'margin', align: 'right', render: (v: string) => <Amount value={v} /> },
                { title: t('trading.pnl'), dataIndex: 'pnl', align: 'right', render: (v: string) => <Amount value={v} signed /> },
                { title: t('trading.openedAt'), dataIndex: 'openedAt', render: (v: string) => <Num>{shortTime(v)}</Num> },
              ]}
            />
          </div>

          <div className="card card-pad">
            <h2 className="card-title">{t('positions.recentClosed')}</h2>
            <DataTable<ClosedPosition> bare rowKey="id" dataSource={c.closedPositions ?? []} emptyText={t('common.empty')} columns={closedColumns(t, { withCustomer: false })} />
          </div>

          <div className="card card-pad">
            <h2 className="card-title">{t('customers.ledgerTitle')}</h2>
            <DataTable<LedgerEntry>
              bare
              rowKey="id"
              dataSource={c.ledger}
              emptyText={t('customers.noLedger')}
              columns={[
                { title: t('customers.time'), dataIndex: 'time', render: (v: string) => <Num>{fullTime(v)}</Num> },
                { title: t('customers.ledgerType'), dataIndex: 'type', render: (v: string) => (i18n.exists(`ledger.${v}`) ? t(`ledger.${v}`) : v) },
                { title: t('customers.amount'), dataIndex: 'amount', align: 'right', render: (v: string) => <Amount value={v} signed /> },
                { title: t('customers.balanceAfter'), dataIndex: 'balanceAfter', align: 'right', render: (v: string) => <Amount value={v} /> },
                { title: t('customers.ref'), dataIndex: 'ref', render: (v: string) => <Num>{v}</Num> },
              ]}
            />
          </div>
        </>
      )}
      {hr.element}
    </>
  );
}
