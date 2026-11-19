/**
 * 行情面板（v2 需求 4.2，方向 A：和客户端行情表同一个样子）。电脑版在首屏右栏，有"走势"小图；手机版在第二屏，三列。
 * 每一行都是去注册页的链接（"点品种跳到注册页"）。休市显示"休市"、没有报价显示"暂无报价"，不显示价格。
 * 第 3 批（TD-9 A、TY-4，原型 P12）：生产没有公开展示授权，接口标 pricesHidden：标题改"交易品种"，每行只列品种名和一个"登录后查看实时行情"按钮（去登录），
 * 不显示价格、走势、涨跌，没有闪动。测试环境照旧显示模拟价并标"示例行情"。
 */
import { useState } from 'react';
import { CATEGORIES, clientLink, SYMBOL_META, symbolName, type Category } from '../config';
import { useQuotes } from '../hooks/useQuotes';
import { useI18n } from '../i18n';
import { direction, formatPct, formatPrice } from '../lib/format';
import type { Row } from '../lib/quotes';

function Spark({ values, up }: { values: number[]; up: boolean }) {
  const w = 90;
  const h = 22;
  if (values.length < 2) return <svg className="spark" width={w} height={h} aria-hidden="true" />;
  const max = Math.max(...values);
  const min = Math.min(...values);
  const d = values
    .map((v, i) => `${i ? 'L' : 'M'}${((i / (values.length - 1)) * w).toFixed(1)},${(2 + (h - 4) * (1 - (v - min) / (max - min || 1))).toFixed(1)}`)
    .join(' ');
  return (
    <svg className="spark" width={w} height={h} viewBox={`0 0 ${w} ${h}`} aria-hidden="true">
      <path d={d} fill="none" stroke={up ? 'var(--long)' : 'var(--short)'} strokeWidth={1.5} />
    </svg>
  );
}

function QuoteRow({ row, desktop }: { row: Row; desktop: boolean }) {
  const { t, lang } = useI18n();
  const name = symbolName(row.symbol, lang, row);
  const open = row.status === 'OPEN' && row.bid !== null;
  const dir = open && row.changePct !== null ? direction(row.changePct) : 'flat';
  const offText = row.status === 'CLOSED' ? t('board.closed') : row.status === 'LOADING' ? '—' : t('board.noQuote');
  return (
    <a
      className="q-row"
      href={clientLink('register', lang)}
      data-testid="quote-row"
      data-symbol={row.symbol}
      data-status={row.status}
      data-flash={row.flash ?? ''}
      aria-label={t('board.rowLabel', { name: `${row.symbol} ${name}` })}
    >
      {row.flash && <span key={row.flashSeq} className={`flash-layer flash-${row.flash}`} data-testid="flash" />}
      <div className="q-name">
        <b>{row.symbol}</b>
        <span>{name}</span>
      </div>
      {desktop && <div className="q-spark">{open && <Spark values={row.history} up={dir !== 'down'} />}</div>}
      {open ? (
        <div className="q-px num" data-testid="bid">
          {formatPrice(row.bid as string)}
        </div>
      ) : (
        <div className="q-px off" data-testid="bid-off">
          {offText}
        </div>
      )}
      <div className="q-chg num">
        {open && row.changePct !== null ? <span className={`chip ${dir}`}>{formatPct(row.changePct)}</span> : <span className="chip off">—</span>}
      </div>
    </a>
  );
}

/** 生产（pricesHidden）：只列品种名 + 每行一个"登录后查看实时行情" */
function HiddenRow({ row }: { row: Row }) {
  const { t, lang } = useI18n();
  const name = symbolName(row.symbol, lang, row);
  return (
    <div className="q-row q-row-hidden" data-testid="quote-row" data-symbol={row.symbol}>
      <div className="q-name">
        <b>{row.symbol}</b>
        <span>{name}</span>
      </div>
      <a className="btn-login-quote" href={clientLink('login', lang)} data-testid="login-to-view">
        {t('board.loginToView')}
      </a>
    </div>
  );
}

export function QuoteBoard({ desktop }: { desktop: boolean }) {
  const { t, lang } = useI18n();
  const { rows, sample, pricesHidden } = useQuotes();
  const [cat, setCat] = useState<Category>('hot');
  // 只显示有品种的分类标签（生产只开加密货币时只有"热门"和"加密货币"，生产方案 v1 07 页 R-1）；选中的分类没有品种了就回到"热门"
  const tabs = CATEGORIES.filter((c) => c === 'hot' || rows.some((r) => SYMBOL_META[r.symbol]?.category === c));
  const current: Category = tabs.includes(cat) ? cat : 'hot';
  const shown = current === 'hot' ? rows : rows.filter((r) => SYMBOL_META[r.symbol]?.category === current);
  return (
    <div className="card board" data-testid="quote-board">
      <div className="board-head">
        <h2>
          <i className="live" aria-hidden="true" />
          {pricesHidden ? t('board.titleHidden') : t('board.title')}
        </h2>
        {sample && !pricesHidden && (
          <span className="tag-sample" title={t('board.sampleNote')} data-testid="sample-tag">
            {t('board.sample')}
          </span>
        )}
      </div>
      <div className="tabs" role="tablist" aria-label={t('board.tabsLabel')}>
        {tabs.map((c) => (
          <button key={c} type="button" role="tab" aria-selected={current === c} onClick={() => setCat(c)}>
            {t(`cat.${c}`)}
          </button>
        ))}
      </div>
      {desktop && !pricesHidden && (
        <div className="q-head" aria-hidden="true">
          <span>{t('board.colSymbol')}</span>
          <span>{t('board.colTrend')}</span>
          <span>{t('board.colBid')}</span>
          <span>{t('board.colChange')}</span>
        </div>
      )}
      <div role="tabpanel">
        {shown.length === 0 ? (
          <div className="q-empty">{t('board.empty')}</div>
        ) : pricesHidden ? (
          shown.map((r) => <HiddenRow key={r.symbol} row={r} />)
        ) : (
          shown.map((r) => <QuoteRow key={r.symbol} row={r} desktop={desktop} />)
        )}
      </div>
      <div className="board-foot">
        {desktop && <span>{t('board.footHint')}</span>}
        <a href={clientLink('register', lang)}>{t('board.all')}</a>
      </div>
      <p className="board-note" data-testid="board-note">
        {t(pricesHidden ? 'board.hiddenNote' : sample ? 'board.sampleNote' : 'board.liveNote')}
      </p>
    </div>
  );
}
