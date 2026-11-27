/**
 * 出金审核（效果图 M6，第 1 批原型 P4）：筛选、待办卡片、列表、详情、通过、拒绝（必填原因）、延迟期内紧急取消（高危）。
 * 流程（两道审，TD-1 B）：客户提交 → 2 小时冷静期（客户可撤销）→ 待审核 → 这里审核通过（≥3,000 USDT 再延迟 6 小时，延迟期内可紧急取消）
 * → 1 分钟内交 TopUP，"等待放款审核" → Amos 在 TopUP 后台审核放款 → TopUP 回成功（已完成）/ 失败、驳回（自动退回）/ 未知（红色置顶，只能重新查询，不能退款）。
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, App, Button, Checkbox, Empty, Form, Grid, Input, Select, Skeleton, Tooltip } from 'antd';
import { StopOutlined, FileTextOutlined, WarningOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { approveWithdrawal, emergencyCancelWithdrawal, getAutoApproveSettings, getWithdrawal, getWithdrawalSummary, listWithdrawals, rejectWithdrawal, requeryWithdrawal } from '../../api';
import type { Withdrawal, WithdrawalDetail, WithdrawalQuery } from '../../api/types';
import { CHAINS } from '../../config/constants';
import { usePagedList } from '../../hooks/usePagedList';
import { useApi } from '../../hooks/useApi';
import { useAction, useHighRisk } from '../../hooks/useConfirmDialogs';
import { PageHeader } from '../../components/PageHeader';
import { FilterBar, FilterItem } from '../../components/FilterBar';
import { DataTable } from '../../components/DataTable';
import { ErrorState } from '../../components/ErrorState';
import { PermButton } from '../../components/PermButton';
import { ChainTag, Pill } from '../../components/Pill';
import { Amount, Num } from '../../components/Num';
import { withdrawalTone } from '../../utils/status';
import { compareAmounts, formatAmount, isValidAmountInput } from '../../utils/money';
import { contact, fullTime, shortHash, shortTime } from '../../utils/display';
import { errorText } from '../../utils/errors';
import { AUTO_RULE_CODES } from '../../config/autoApprove';
import { AUTO_RULES_PATH, AutoReviewCard, AutoReviewCell } from './AutoReview';

type Filters = Omit<WithdrawalQuery, 'page' | 'pageSize'>;
const INITIAL: Filters = { status: 'open' };
const STATUSES = ['open', 'pending', 'delayed', 'cooling', 'broadcasting', 'completed', 'rejected', 'cancelled', 'failed'] as const;

/** 状态名：放款中再细分成"等待放款审核 / 放款中"，付款状态未知单独标出（两道审） */
function statusText(t: (k: string, o?: Record<string, unknown>) => string, w: Withdrawal): string {
  if (w.status === 'delayed') return t('withdrawals.delayedLeft', { left: formatRemaining(w.delayRemainingMinutes) });
  if (w.status === 'broadcasting' && w.payoutStatus === 'unknown') return t('payoutStatus.unknownLong');
  if (w.status === 'broadcasting' && (w.payoutStatus === 'pending' || w.payoutStatus === 'submitting')) return t('payoutStatus.awaitingRelease');
  return t(`withdrawalStatus.${w.status}`);
}

export function formatRemaining(minutes: number | null): string {
  if (minutes === null || minutes < 0) return '—';
  return `${Math.floor(minutes / 60)}:${String(minutes % 60).padStart(2, '0')}`;
}

export function WithdrawalsPage() {
  const { t } = useTranslation();
  const list = usePagedList<Withdrawal, Filters>(listWithdrawals, INITIAL, 8);
  const summaryFetcher = useCallback(() => getWithdrawalSummary(), []);
  const summary = useApi(summaryFetcher);
  const [draft, setDraft] = useState<Filters>(INITIAL);
  const [amountErr, setAmountErr] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const detailRef = useRef<HTMLDivElement>(null);
  const screens = Grid.useBreakpoint();

  // 列表加载后默认选中第一条
  const items = list.data?.items;
  useEffect(() => {
    if (!items) return;
    if (!items.length) setSelectedId(null);
    else if (!selectedId || !items.some((w) => w.id === selectedId)) setSelectedId(items[0].id);
  }, [items, selectedId]);

  const search = () => {
    const bad = [draft.minAmount, draft.maxAmount].some((v) => v && !isValidAmountInput(v));
    if (bad) {
      setAmountErr(t('withdrawals.amountFilterInvalid'));
      return;
    }
    setAmountErr(null);
    list.setFilters(draft);
  };

  const refreshAll = () => {
    list.reload();
    summary.reload();
  };

  const select = (id: string) => {
    setSelectedId(id);
    if (!screens.xl) setTimeout(() => detailRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }), 50);
  };

  const s = summary.data;
  const unknown = list.data?.items.find((w) => w.payoutStatus === 'unknown');
  return (
    <>
      <PageHeader title={t('withdrawals.title')} sub={t('withdrawals.sub')} extra={<AutoApproveEntry />} />
      {unknown ? (
        <Alert
          type="error"
          showIcon
          className="wd-unknown"
          message={t('withdrawals.unknownTitle', { id: unknown.id, amount: formatAmount(unknown.amount) })}
          description={t('withdrawals.unknownDesc')}
          action={
            <Button danger type="primary" onClick={() => select(unknown.id)}>
              {t('withdrawals.goRequery')}
            </Button>
          }
        />
      ) : null}
      <Alert type="info" showIcon className="wd-two" message={t('withdrawals.twoReview')} />
      <FilterBar onSearch={search} onReset={() => { setDraft(INITIAL); setAmountErr(null); list.setFilters(INITIAL); }}>
        <FilterItem label={t('withdrawals.status')}>
          <Select
            aria-label={t('withdrawals.status')}
            style={{ width: 150 }}
            placeholder={t('common.all')}
            allowClear
            value={draft.status}
            onChange={(v) => setDraft({ ...draft, status: v })}
            options={STATUSES.map((st) => ({ value: st, label: st === 'open' ? t('withdrawals.openAll') : t(`withdrawalStatus.${st}`) }))}
          />
        </FilterItem>
        <FilterItem label={t('funds.chain')}>
          <Select aria-label={t('funds.chain')} allowClear style={{ width: 100 }} placeholder={t('common.all')} value={draft.chain} onChange={(v) => setDraft({ ...draft, chain: v })} options={CHAINS.map((c) => ({ value: c, label: c }))} />
        </FilterItem>
        <FilterItem label={t('withdrawals.amount')}>
          <span className="range">
            <Input aria-label={t('withdrawals.minAmount')} placeholder={t('withdrawals.minAmount')} inputMode="decimal" value={draft.minAmount} status={amountErr ? 'error' : undefined} onChange={(e) => setDraft({ ...draft, minAmount: e.target.value || undefined })} />
            ~
            <Input aria-label={t('withdrawals.maxAmount')} placeholder={t('withdrawals.maxAmount')} inputMode="decimal" value={draft.maxAmount} status={amountErr ? 'error' : undefined} onChange={(e) => setDraft({ ...draft, maxAmount: e.target.value || undefined })} />
          </span>
        </FilterItem>
        <FilterItem label={t('autoReview.filter')}>
          <Select
            aria-label={t('autoReview.filter')}
            allowClear
            style={{ width: 190 }}
            placeholder={t('common.all')}
            value={draft.autoReview}
            onChange={(v) => setDraft({ ...draft, autoReview: v })}
            popupMatchSelectWidth={false}
            listHeight={480}
            options={[
              {
                label: t('autoReview.filterOutcome'),
                options: (['auto_approved', 'manual', 'master_off', 'none'] as const).map((o) => ({ value: o, label: t(`autoReview.filterOpt.${o}`) })),
              },
              {
                label: t('autoReview.filterRule'),
                options: AUTO_RULE_CODES.map((c) => ({ value: c, label: `${c} ${t(`autoRule.${c}.name`)}` })),
              },
            ]}
          />
        </FilterItem>
        <FilterItem label={t('funds.user')} htmlFor="w-kw">
          <Input id="w-kw" allowClear style={{ width: 200 }} placeholder={t('withdrawals.userPh')} value={draft.keyword} onChange={(e) => setDraft({ ...draft, keyword: e.target.value })} />
        </FilterItem>
        {amountErr ? (
          <div role="alert" className="text-danger" style={{ width: '100%', fontSize: 12 }}>
            {amountErr}
          </div>
        ) : null}
      </FilterBar>

      {summary.error ? (
        <div className="card">
          <ErrorState error={summary.error} onRetry={summary.reload} compact />
        </div>
      ) : (
        <section className="kpis" aria-label={t('withdrawals.todo')}>
          {(
            [
              ['pending', 'orange', t('withdrawals.kpiPending')],
              ['delayed', 'violet', t('withdrawals.kpiDelayed')],
              ['cooling', '', t('withdrawals.kpiCooling')],
              ['completedToday', 'green', t('withdrawals.kpiCompleted')],
            ] as const
          ).map(([key, color, label]) => (
            <div key={key} className={`card kpi ${color}`}>
              <div className="k">{label}</div>
              <div className="v">
                {s ? (
                  <>
                    <b>{t('common.countUnit', { count: s[key].count })}</b>
                    <span>
                      {t('withdrawals.sum')} <span className="num">{formatAmount(s[key].amount)}</span> USDT
                    </span>
                  </>
                ) : (
                  <Skeleton.Input active size="small" />
                )}
              </div>
            </div>
          ))}
        </section>
      )}

      <section className="split">
        <DataTable<Withdrawal>
          rowKey="id"
          loading={list.loading}
          error={list.error}
          onRetry={list.reload}
          dataSource={list.data?.items}
          total={list.data?.total ?? 0}
          page={list.page}
          pageSize={list.pageSize}
          onPageChange={list.onPageChange}
          emptyText={t('withdrawals.empty')}
          rowClassName={(r) => `clickable-row${r.id === selectedId ? ' row-selected' : ''}${r.payoutStatus === 'unknown' ? ' row-danger' : ''}`}
          onRow={(r) => ({
            onClick: () => select(r.id),
            onKeyDown: (e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                select(r.id);
              }
            },
            tabIndex: 0,
            'aria-selected': r.id === selectedId,
          })}
          columns={[
            {
              title: t('funds.user'),
              key: 'u',
              render: (_, r) => (
                <div className="user-cell">
                  <b title={r.customerName}>{r.customerName}</b>
                  <small>{contact(r.email)}</small>
                  {r.ruleType === 'tg' ? (
                    <span>
                      <Pill tone="warn">{t('withdrawals.tg.rule')}</Pill>
                      {r.firstCheck && !r.firstChecked ? <Pill tone="danger">{t('withdrawals.tg.firstCheck')}</Pill> : null}
                    </span>
                  ) : null}
                </div>
              ),
            },
            { title: t('withdrawals.amountUsdt'), dataIndex: 'amount', align: 'right', render: (v: string, r) => <Amount value={v} strong={r.id === selectedId} /> },
            { title: t('funds.chain'), dataIndex: 'chain', render: (v: string) => <ChainTag chain={v} /> },
            {
              title: t('withdrawals.address'),
              dataIndex: 'address',
              render: (v: string, r) => (
                <span className="nowrap">
                  <Num>{shortHash(v, 6, 5)}</Num> {r.firstUse ? <Pill tone="warn">{t('withdrawals.firstUse')}</Pill> : null}
                </span>
              ),
            },
            {
              title: t('withdrawals.status'),
              dataIndex: 'status',
              render: (v: string, r) => <Pill tone={r.payoutStatus === 'unknown' ? 'danger' : withdrawalTone[v]}>{statusText(t, r)}</Pill>,
            },
            {
              title: t('withdrawals.payoutNo'),
              key: 'payout',
              render: (_: unknown, r: Withdrawal) =>
                r.payoutNo ? (
                  <div className="user-cell">
                    <Num>{r.payoutNo}</Num>
                    <small className={r.payoutStatus === 'unknown' ? 'text-danger' : undefined}>{r.payoutStatus ? t(`payoutStatus.${r.payoutStatus}`) : ''}</small>
                  </div>
                ) : (
                  '—'
                ),
            },
            { title: t('autoReview.column'), key: 'autoReview', render: (_: unknown, r: Withdrawal) => <AutoReviewCell review={r.autoReview} /> },
            { title: t('withdrawals.submittedAt'), dataIndex: 'submittedAt', render: (v: string) => <Num>{shortTime(v)}</Num> },
            { title: t('withdrawals.statusAt'), dataIndex: 'statusAt', render: (v: string | null) => (v ? <Num>{fullTime(v)}</Num> : '—') },
          ]}
        />
        <div ref={detailRef}>
          <WithdrawalDetailPanel id={selectedId} onChanged={refreshAll} />
        </div>
      </section>
    </>
  );
}

function WithdrawalDetailPanel({ id, onChanged }: { id: string | null; onChanged: () => void }) {
  const { t } = useTranslation();
  const { modal, message } = App.useApp();
  const run = useAction();
  const hr = useHighRisk();
  const [form] = Form.useForm<{ reason: string }>();
  const [busy, setBusy] = useState(false);
  const [rejectError, setRejectError] = useState<unknown>(null);
  // 第 4 批：首笔核实单要先勾"已人工核实"才能通过
  const [checked, setChecked] = useState(false);
  const fetcher = useCallback(() => (id ? getWithdrawal(id) : Promise.resolve(null)), [id]);
  const { data: w, loading, error, reload } = useApi<WithdrawalDetail | null>(fetcher);

  useEffect(() => {
    form.resetFields();
    setRejectError(null);
    setChecked(false);
  }, [id, form]);

  const after = () => {
    reload();
    onChanged();
  };

  if (!id) {
    return (
      <aside className="card det" aria-label={t('withdrawals.detail')}>
        <h3>{t('withdrawals.detail')}</h3>
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('withdrawals.selectHint')} />
      </aside>
    );
  }
  if (error) {
    return (
      <aside className="card det">
        <ErrorState error={error} onRetry={reload} compact />
      </aside>
    );
  }
  if (!w || loading) {
    return (
      <aside className="card det">
        <Skeleton active paragraph={{ rows: 10 }} />
      </aside>
    );
  }

  const large = compareAmounts(w.amount, '3000') >= 0;
  const needCheck = !!w.firstCheck && !w.firstChecked;
  // 第 4 批审查 🔴4a-1：客户账户冻结或提现冻结时不能通过（后端也拒绝 WITHDRAW_FROZEN）
  const frozen = !!w.safety && (w.safety.accountFrozen || w.safety.withdrawFrozen);
  const approve = () =>
    modal.confirm({
      title: t('withdrawals.approveConfirm'),
      content: large ? t('withdrawals.approveLarge', { amount: formatAmount(w.amount) }) : t('withdrawals.approveSmall', { amount: formatAmount(w.amount), chain: w.chain }),
      okText: t('withdrawals.approve'),
      cancelText: t('common.cancel'),
      onOk: () =>
        run(
          async () => {
            const r = await approveWithdrawal(w.id, needCheck && checked);
            if (r.payoutNo) void message.success(t('withdrawals.approvedToProvider', { no: r.payoutNo }));
            return r;
          },
          t('withdrawals.approved'),
          after,
        ),
    });

  const reject = async () => {
    setRejectError(null);
    let v: { reason: string };
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    setBusy(true);
    try {
      await rejectWithdrawal(w.id, v.reason.trim());
      void message.success(t('withdrawals.rejected'));
      form.resetFields();
      after();
    } catch (e) {
      setRejectError(e);
    } finally {
      setBusy(false);
    }
  };

  const emergency = () =>
    hr.ask({
      title: t('withdrawals.emergencyCancel'),
      description: t('withdrawals.emergencyDesc', { id: w.id, amount: formatAmount(w.amount), name: w.customerName, left: formatRemaining(w.delayRemainingMinutes) }),
      confirmText: t('withdrawals.confirmEmergency'),
      onConfirm: (v) => emergencyCancelWithdrawal(w.id, v.reason),
      successText: t('withdrawals.cancelled'),
      onDone: after,
    });

  return (
    <aside className="card det" aria-label={t('withdrawals.detail')}>
      <h3>
        {t('withdrawals.detail')} <span className="num">{w.id}</span>
      </h3>
      <div className="who2">
        <span className="av" aria-hidden>
          {(w.customerName ?? '').slice(0, 1)}
        </span>
        <div style={{ minWidth: 0 }}>
          <b className="ellipsis" style={{ maxWidth: 200 }} title={w.customerName}>{w.customerName}</b>{' '}
          <Pill tone={w.kycStatus === 'approved' ? 'ok' : 'danger'}>{t(`withdrawals.kyc.${w.kycStatus === 'approved' ? 'ok' : 'no'}`)}</Pill>
          <small>{t('withdrawals.userLine', { email: contact(w.email), id: w.customerId })}</small>
        </div>
      </div>
      <div className="kv-grid">
        <div>
          {t('withdrawals.applyAmount')}
          <span className="num">{formatAmount(w.amount)} USDT</span>
        </div>
        <div>
          {t('withdrawals.chainFee')}
          <span className="num">
            {w.chain} / {formatAmount(w.fee)}
          </span>
        </div>
        <div>
          {t('withdrawals.registeredDays')}
          <span className="num">{t('withdrawals.days', { count: w.registeredDays })}</span>
        </div>
        <div>
          {t('withdrawals.totalDeposit')}
          <span className="num">{formatAmount(w.totalDeposit)}</span>
        </div>
        <div>
          {t('withdrawals.balance')}
          <span className="num">{formatAmount(w.balance)}</span>
        </div>
        <div>
          {t('withdrawals.positions')}
          <span className="num">{t('common.countUnit', { count: w.openPositions })}</span>
        </div>
      </div>
      <div className={`addr-box${w.firstUse ? '' : ' ok'}`}>
        {w.firstUse ? <WarningOutlined /> : null} {t('withdrawals.addressLabel')} <span className="num">{w.address}</span>{' '}
        {w.firstUse ? <b>{t('withdrawals.firstUseLong')}</b> : t('withdrawals.usedBefore')}
        <br />
        {t('withdrawals.whitelistAt', { time: shortTime(w.whitelistAddedAt) })}
      </div>
      {w.ruleType === 'tg' ? (
        <div className="addr-box" role="group" aria-label={t('withdrawals.tg.title')}>
          <b>{t('withdrawals.tg.title')}</b> <Pill tone="warn">{t('withdrawals.tg.rule')}</Pill>{' '}
          <Pill tone="muted">{t(`withdrawals.tg.source.${w.source ?? 'web'}`)}</Pill>
          <div>{t('withdrawals.tg.manualNote')}</div>
          {w.firstCheck ? <div>{w.firstChecked ? t('withdrawals.tg.firstCheckedDone') : <b>{t('withdrawals.tg.firstCheckNote')}</b>}</div> : null}
        </div>
      ) : null}
      {w.safety ? (
        <div className={`addr-box${frozen || w.safety.securityEvents48h.length ? '' : ' ok'}`} role="group" aria-label={t('withdrawals.safety.title')}>
          <b>{t('withdrawals.safety.title')}</b>{' '}
          {w.safety.accountFrozen ? <Pill tone="danger">{t('withdrawals.safety.accountFrozen')}</Pill> : null}{' '}
          {w.safety.withdrawFrozen
            ? (w.safety.withdrawFrozenSources ?? (w.safety.withdrawFrozenSource ? [w.safety.withdrawFrozenSource] : [])).map((src) => (
                <Pill key={src} tone="danger">
                  {t('withdrawals.safety.withdrawFrozen', { source: t(`customers.wdSafety.source.${src}`) })}
                </Pill>
              ))
            : null}
          {frozen ? <div className="text-danger">{t('withdrawals.safety.blocked')}</div> : null}
          <div>
            {w.safety.securityEvents48h.length ? (
              <>
                {t('withdrawals.safety.events')}：
                {w.safety.securityEvents48h.map((e, i) => (
                  <div key={i}>
                    <span className="num">{shortTime(e.at)}</span> · {t(`withdrawals.safety.type.${e.type}`, { defaultValue: e.type })}
                  </div>
                ))}
              </>
            ) : (
              t('withdrawals.safety.noEvents')
            )}
          </div>
        </div>
      ) : null}
      <AutoReviewCard detail={w.autoReviewDetail} />
      {w.payoutNo ? (
        <div className={`addr-box${w.payoutStatus === 'unknown' ? '' : ' ok'}`} role="group" aria-label={t('withdrawals.payoutTitle')}>
          <b>{t('withdrawals.payoutTitle')}</b>
          <div>
            {t('withdrawals.payoutLine', { provider: w.provider ?? 'TopUP', no: w.payoutNo })}{' '}
            <Pill tone={w.payoutStatus === 'unknown' ? 'danger' : w.payoutStatus === 'completed' ? 'ok' : 'warn'}>{w.payoutStatus ? t(`payoutStatus.${w.payoutStatus}`) : '—'}</Pill>
          </div>
          {w.payoutStatus === 'pending' ? <div>{t('withdrawals.goTopup')}</div> : null}
          {w.payoutReason ? <div>{t('withdrawals.reasonLine', { reason: w.payoutReason })}</div> : null}
          {w.status === 'broadcasting' ? (
            <div className="acts">
              <PermButton perm="withdrawal:review" danger={w.payoutStatus === 'unknown'} type={w.payoutStatus === 'unknown' ? 'primary' : 'default'} onClick={() => run(() => requeryWithdrawal(w.id), t('withdrawals.requeried'), after)}>
                {t('withdrawals.requery')}
              </PermButton>
              {w.payoutStatus === 'unknown' ? (
                <Tooltip title={t('withdrawals.refundDisabled')}>
                  <Button disabled>{t('withdrawals.refund')}</Button>
                </Tooltip>
              ) : null}
            </div>
          ) : null}
        </div>
      ) : null}
      <div className="mini">
        <div style={{ fontWeight: 600, color: 'var(--text)', marginBottom: 2 }}>{t('withdrawals.recentDeposits')}</div>
        {w.recentDeposits.length ? (
          w.recentDeposits.map((d, i) => (
            <div className="row" key={i}>
              <span>
                {shortTime(d.time)} · {d.chain}
                {d.explorerUrl ? (
                  <>
                    {' · '}
                    <a href={d.explorerUrl} target="_blank" rel="noreferrer noopener" title={d.txHash ?? ''}>
                      {t('withdrawals.viewTx')}
                    </a>
                  </>
                ) : null}
              </span>
              <span className="num up">+{formatAmount(d.amount)}</span>
            </div>
          ))
        ) : (
          <div className="row">
            <span>{t('common.none')}</span>
          </div>
        )}
        <div style={{ marginTop: 4 }}>
          {t('withdrawals.recentWithdrawals')}
          {w.recentWithdrawals.length ? (
            w.recentWithdrawals.map((d, i) => (
              <div className="row" key={i}>
                <span>
                  {shortTime(d.time)} · {d.chain}
                </span>
                <span className="num down">-{formatAmount(d.amount)}</span>
              </div>
            ))
          ) : (
            <b style={{ color: 'var(--text)' }}>{t('common.none')}</b>
          )}
        </div>
      </div>

      {w.status === 'pending' ? (
        <>
          <Form form={form} name={`reject-${w.id}`} layout="vertical">
            <Form.Item
              name="reason"
              label={t('withdrawals.rejectReason')}
              style={{ marginBottom: 0 }}
              rules={[{ required: true, whitespace: true, message: t('withdrawals.rejectReasonRequired') }]}
            >
              <Input.TextArea rows={2} placeholder={t('withdrawals.rejectPh')} maxLength={200} />
            </Form.Item>
          </Form>
          {rejectError ? <div role="alert" className="text-danger">{errorText(t, rejectError)}</div> : null}
          {needCheck ? (
            <Checkbox checked={checked} onChange={(e) => setChecked(e.target.checked)}>
              {t('withdrawals.tg.firstCheckBox')}
            </Checkbox>
          ) : null}
          <div className="acts">
            <PermButton perm="withdrawal:review" type="primary" onClick={approve} disabled={frozen || (needCheck && !checked)} disabledReason={frozen ? t('errors.customerWithdrawFrozen') : t('withdrawals.tg.firstCheckNote')}>
              {t('withdrawals.approve')}
            </PermButton>
            <PermButton perm="withdrawal:review" className="btn-reject" loading={busy} onClick={() => void reject()}>
              {t('withdrawals.reject')}
            </PermButton>
          </div>
        </>
      ) : (
        <div className="addr-box ok">
          {t('withdrawals.currentStatus')} <Pill tone={withdrawalTone[w.status]}>{t(`withdrawalStatus.${w.status}`)}</Pill>
          {w.reviewer ? <div>{t('withdrawals.reviewerLine', { name: w.reviewer })}</div> : null}
          {w.autoReview?.phase === 'final' && w.autoReview.outcome === 'auto_approved' ? <div>{t('autoReview.reviewerAuto')}</div> : null}
          {w.reason ? <div>{t('withdrawals.reasonLine', { reason: w.reason })}</div> : null}
          {w.status === 'cooling' ? <div>{t('withdrawals.coolingHint')}</div> : null}
          {w.status === 'delayed' ? <div>{t('withdrawals.delayedHint', { left: formatRemaining(w.delayRemainingMinutes) })}</div> : null}
        </div>
      )}

      <PermButton
        perm="withdrawal:emergency_cancel"
        className="emg-btn"
        icon={<StopOutlined />}
        disabled={w.status !== 'delayed'}
        disabledReason={t('withdrawals.emergencyOnlyDelayed')}
        onClick={emergency}
      >
        {w.status === 'delayed' ? t('withdrawals.emergencyWithLeft', { left: formatRemaining(w.delayRemainingMinutes) }) : t('withdrawals.emergencyNA')}
      </PermButton>
      <div className="det-foot">
        <FileTextOutlined /> {t('withdrawals.auditNote')}
      </div>
      {hr.element}
    </aside>
  );
}

/** v2 自动审批：页头右侧显示总开关状态和规则设置入口 */
function AutoApproveEntry() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const fetcher = useCallback(() => getAutoApproveSettings(), []);
  const { data } = useApi(fetcher);
  return (
    <span className="aa-entry">
      {data ? (
        <span className="muted">
          {t('autoReview.masterIs')}{' '}
          <Pill tone={data.masterEnabled ? 'ok' : 'muted'}>{data.masterEnabled ? t('autoApprove.masterOn') : t('autoReview.masterOffShort')}</Pill>
        </span>
      ) : null}
      <Button icon={<SafetyCertificateOutlined />} onClick={() => navigate(AUTO_RULES_PATH)}>
        {t('autoApprove.title')}
      </Button>
    </span>
  );
}
