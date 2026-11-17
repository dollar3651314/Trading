/**
 * 充值（原型 P1，第 1 批）：先选网络；第一次选某条网络时才向钱包提供方要地址（"正在生成…通常 5 秒内"），以后一直是同一个（AC-W1）。
 * 拿不到地址时写"暂时无法获取充值地址，请稍后再试"并可重试，已生成的网络照常可用；后台临时关掉的网络不出现。
 * 地址下方固定写：只能转 USDT、选错网络无法找回、低于 1 USDT 的转账不入账、无法找回（AC-W11）、到账会通知。不再写确认数和"ERC20 和 BSC 相同"。
 * 下方是最近的充值记录。
 */
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { getDepositAddresses, listDeposits, requestDepositAddress } from '../../api/wallet';
import type { DepositRecord, Network } from '../../api/types';
import { errorKey, isWalletNotReady } from '../../api/errors';
import { useToast } from '../../app/contexts';
import { AsyncView, EmptyState, ErrorState, Loading } from '../../components/AsyncView';
import { Icon } from '../../components/Icon';
import { QrCode } from '../../components/QrCode';
import { useAsync } from '../../hooks/useAsync';
import { useServerEvent } from '../../hooks/useServerEvent';
import { formatAmount, formatDateTime, groupAddress } from '../../lib/format';

async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    // 旧浏览器或非安全上下文的兜底
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand('copy');
    ta.remove();
    return ok;
  }
}

export function DepositCard() {
  const { t } = useTranslation();
  const toast = useToast();
  const s = useAsync(getDepositAddresses, 'deposit');
  const [net, setNet] = useState<Network | null>(null);
  /** 这次打开页面后新生成的地址 */
  const [made, setMade] = useState<Partial<Record<Network, string>>>({});
  const [busy, setBusy] = useState<Network | null>(null);
  const [failed, setFailed] = useState<Partial<Record<Network, string>>>({});
  const nets = s.data ?? ALL.map((network) => ({ network, address: null, minDeposit: '1' }));
  const addressOf = (n: Network) => made[n] ?? nets.find((x) => x.network === n)?.address ?? null;
  const min = nets[0]?.minDeposit ?? '1';

  const generate = async (n: Network) => {
    setBusy(n);
    setFailed((f) => ({ ...f, [n]: undefined }));
    try {
      const a = await requestDepositAddress(n);
      if (a.address) setMade((m) => ({ ...m, [n]: a.address! }));
      toast.show({ tone: 'success', title: t('deposit.generated', { network: n }), body: t('deposit.generatedBody') });
    } catch (e) {
      setFailed((f) => ({ ...f, [n]: errorKey(e) }));
    } finally {
      setBusy(null);
    }
  };
  // 选了一条还没有地址的网络：等网络列表加载好以后向钱包提供方要（失败了等客户点"重试"）
  useEffect(() => {
    if (net && s.data && !addressOf(net) && busy === null && !failed[net]) void generate(net);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [net, s.data]);

  const address = net ? addressOf(net) : null;
  return (
    <section className="card" id="dep-card" aria-labelledby="dep-title">
      <div className="card-head">
        <h2 id="dep-title">{t('deposit.chooseNetwork')}</h2>
        <span className="muted small">{t('deposit.onlyUsdt')}</span>
      </div>
      <div className="net-grid" role="radiogroup" aria-label={t('deposit.chooseNetwork')}>
        {nets.map(({ network: n }) => (
          <button key={n} type="button" role="radio" aria-checked={net === n} className={`net${net === n ? ' on' : ''}`} onClick={() => setNet(n)}>
            <b>{n}</b>
            <small>{t(`deposit.chain.${n}`)}</small>
            {addressOf(n) && <span className="gen">{t('deposit.generatedTag')}</span>}
          </button>
        ))}
      </div>
      {!net ? (
        <p className="pick-first">{t('deposit.pickFirst')}</p>
      ) : isWalletNotReady(s.error) && !s.data ? (
        // 钱包服务还没开通：说明"暂未开通"，不显示成"加载失败 + 重试"
        <EmptyState compact title={t('deposit.notOpenTitle')} hint={t('deposit.notOpenHint')} />
      ) : s.error && !s.data ? (
        <ErrorState error={s.error} onRetry={s.reload} compact />
      ) : !s.data ? (
        <Loading rows={3} />
      ) : busy === net ? (
        <div className="gen-box" role="status">
          <span className="spin" aria-hidden="true" />
          <b>{t('deposit.generating', { network: net })}</b>
          <span className="small muted">{t('deposit.generatingHint')}</span>
        </div>
      ) : failed[net] ? (
        <div className="notice error dep-fail" role="alert">
          <b>{t('deposit.unavailableTitle')}</b>
          <p className="small">
            {nets.some((x) => addressOf(x.network)) && t('deposit.unavailableOthers', { list: nets.filter((x) => addressOf(x.network)).map((x) => x.network).join(' / ') })}
            {t('deposit.unavailableHint')}
          </p>
          <button type="button" className="btn btn-primary btn-sm" onClick={() => void generate(net)}>
            {t('common.retry')}
          </button>
        </div>
      ) : address ? (
        <>
          <div className="qr-wrap">
            <QrCode value={address} label={t('deposit.qrLabel', { network: net })} />
            <div className="addr">
              <span className="num">{groupAddress(address)}</span>
              <button
                type="button"
                className="btn btn-primary btn-sm"
                onClick={async () => {
                  const ok = await copyText(address);
                  toast.show({ tone: ok ? 'success' : 'error', title: ok ? t('deposit.copied') : t('deposit.copyFailed') });
                }}
              >
                <Icon name="copy" size={14} />
                {t('common.copy')}
              </button>
            </div>
            <p className="muted small">{t('deposit.warnMin', { min })}</p>
          </div>
          <div className="warn-box" role="note">
            <h3>
              <Icon name="warn" size={16} strokeWidth={2.2} />
              {t('deposit.warnTitle')}
            </h3>
            <ul>
              <li>{t('deposit.warnOnly', { network: net })}</li>
              <li>{t('deposit.warnWrong')}</li>
              <li>
                <b>{t('deposit.warnBelow', { min })}</b>
              </li>
              <li>{t('deposit.warnNotify')}</li>
            </ul>
          </div>
        </>
      ) : null}
      <DepositRecords />
    </section>
  );
}

/** 网络列表加载好之前先按三条显示（加载好以后只留开着的） */
const ALL: Network[] = ['ERC20', 'TRC20', 'BSC'];

/** 只显示最近几条，完整的记录在资金记录页 */
const RECENT = 5;

function DepositRecords() {
  const { t } = useTranslation();
  const s = useAsync(listDeposits, 'deposits');
  // 到账时后端推 deposit 事件，不用刷新页面就能看到
  useServerEvent((e) => {
    if (e.type === 'deposit' || e.type === 'account' || (e.type === 'notification' && e.item.kind === 'deposit')) s.reload();
  });
  const label = (d: DepositRecord) => (d.status === 'pending' ? t('deposit.status.pending', { n: d.confirmations, m: d.requiredConfirmations }) : t(`deposit.status.${d.status}`));
  return (
    <>
      <div className="card-head dep-records">
        <h2>{t('deposit.records')}</h2>
        <Link to="/assets/records" className="more small">
          {t('records.viewAll')}
          <Icon name="chevronRight" size={14} />
        </Link>
      </div>
      <AsyncView state={s} rows={2} isEmpty={(d) => d.length === 0} empty={<EmptyState compact title={t('deposit.recordsEmpty')} hint={t('deposit.recordsEmptyHint')} />}>
        {(list) => (
          <ul className="ledger">
            {list.slice(0, RECENT).map((d) => (
              <li key={d.id}>
                <span className={`lg-ic ${d.status === 'credited' ? 'in' : 'fee'}`} aria-hidden="true">
                  {t('ledger.icon.deposit')}
                </span>
                <div className="lg-t">
                  <b>
                    {label(d)}
                    <span className="num"> · {d.network}</span>
                  </b>
                  <small className="num">{formatDateTime(d.time, { seconds: true })}</small>
                </div>
                <div className={`lg-amt num${d.status === 'credited' ? ' up' : ' muted'}`}>{formatAmount(d.amount)}</div>
              </li>
            ))}
          </ul>
        )}
      </AsyncView>
    </>
  );
}
