/**
 * 官网的固定配置：客户端地址、品种的短名称和分类。（联系邮箱、公司信息、牌照都不写：Amos 11/19，生产方案 v1 07 页 R-6）
 * 行情显示哪些品种由后端配置决定（application.yml 的 trading.market.public-quotes.symbols），这里只补充展示用的短名称和分类；
 * 后端返回了这里没有的代码时，名称用接口返回的中英文名，只出现在"热门"分类。
 */
import type { Lang } from './i18n/lang';

/** 分类（顺序就是行情面板的分类标签顺序，"热门"是全部） */
export const CATEGORIES = ['hot', 'fx', 'metal', 'index', 'energy', 'crypto'] as const;
export type Category = (typeof CATEGORIES)[number];

export interface SymbolMeta {
  zh: string;
  en: string;
  category: Exclude<Category, 'hot'>;
}

/** 品种的短名称（品种表里的全称太长，例如原油），和 V4 品种表的代码一致 */
export const SYMBOL_META: Record<string, SymbolMeta> = {
  XAUUSD: { zh: '黄金', en: 'Gold', category: 'metal' },
  XAGUSD: { zh: '白银', en: 'Silver', category: 'metal' },
  EURUSD: { zh: '欧元/美元', en: 'EUR/USD', category: 'fx' },
  GBPUSD: { zh: '英镑/美元', en: 'GBP/USD', category: 'fx' },
  USDJPY: { zh: '美元/日元', en: 'USD/JPY', category: 'fx' },
  USDCNH: { zh: '美元/离岸人民币', en: 'USD/CNH', category: 'fx' },
  NAS100: { zh: '纳指 100', en: 'Nasdaq 100', category: 'index' },
  XTIUSD: { zh: '美国原油', en: 'US Crude Oil', category: 'energy' },
  BTCUSD: { zh: '比特币', en: 'Bitcoin', category: 'crypto' },
  ETHUSD: { zh: '以太坊', en: 'Ethereum', category: 'crypto' },
  // 生产只开加密货币（生产方案 v1 07 页 R-1）：官网列 10 个币安品种（生产 FX_PUBLIC_QUOTES_SYMBOLS），其余 8 个的短名称和分类
  SOLUSD: { zh: 'Solana', en: 'Solana', category: 'crypto' },
  XRPUSD: { zh: '瑞波币', en: 'XRP', category: 'crypto' },
  BNBUSD: { zh: '币安币', en: 'BNB', category: 'crypto' },
  DOGEUSD: { zh: '狗狗币', en: 'Dogecoin', category: 'crypto' },
  ADAUSD: { zh: '艾达币', en: 'Cardano', category: 'crypto' },
  LTCUSD: { zh: '莱特币', en: 'Litecoin', category: 'crypto' },
  DOTUSD: { zh: '波卡', en: 'Polkadot', category: 'crypto' },
  BCHUSD: { zh: '比特币现金', en: 'Bitcoin Cash', category: 'crypto' },
};


/** 品种的展示名：优先用短名称，没有时用接口返回的名称 */
export function symbolName(code: string, lang: Lang, fromApi?: { nameZh?: string; nameEn?: string }): string {
  const m = SYMBOL_META[code];
  if (m) return lang === 'en' ? m.en : m.zh;
  return (lang === 'en' ? fromApi?.nameEn : fromApi?.nameZh) || code;
}

const PLACEHOLDER = '__TR_CLIENT_URL__';

/**
 * 客户端地址（不带结尾的 /）。优先级：构建变量 VITE_CLIENT_URL → 页面 <meta name="tr-client-url">（部署时由 Nginx 替换）→ 空串（同源相对地址）。
 */
export function resolveClientUrl(buildValue: string | undefined, metaValue: string | null | undefined): string {
  const pick = (v: string | null | undefined) => {
    const s = (v || '').trim();
    return s && s !== PLACEHOLDER && /^https?:\/\/[^\s"'<>]+$/.test(s) ? s.replace(/\/+$/, '') : '';
  };
  return pick(buildValue) || pick(metaValue);
}

let cachedClientUrl: string | undefined;
export function clientUrl(): string {
  if (cachedClientUrl === undefined) {
    const meta = typeof document !== 'undefined' ? document.querySelector('meta[name="tr-client-url"]')?.getAttribute('content') : null;
    cachedClientUrl = resolveClientUrl(import.meta.env.VITE_CLIENT_URL, meta);
  }
  return cachedClientUrl;
}

/**
 * 机器人地址（TG 机器人第 2 批，技术方案 11 页第 2b 节）：只有"第一阶段只走 Telegram"打开（meta tr-tg-only = true）、
 * 并且是合法的 https://t.me/机器人用户名 时才用；否则为空串（照旧去客户端注册、登录）。
 */
export function resolveBotUrl(botMeta: string | null | undefined, tgOnlyMeta: string | null | undefined): string {
  const url = (botMeta || '').trim();
  return (tgOnlyMeta || '').trim() === 'true' && /^https:\/\/t\.me\/[A-Za-z][A-Za-z0-9_]{3,31}$/.test(url) ? url : '';
}

let cachedBotUrl: string | undefined;
export function botUrl(): string {
  if (cachedBotUrl === undefined) {
    const meta = (name: string) => (typeof document !== 'undefined' ? document.querySelector(`meta[name="${name}"]`)?.getAttribute('content') : null);
    cachedBotUrl = resolveBotUrl(meta('tr-bot-url'), meta('tr-tg-only'));
  }
  return cachedBotUrl;
}

/**
 * 注册、登录的地址：客户端的 /register、/login，带上当前语言（客户端读 ?lang=，web/client/src/i18n/urlLang.ts）。
 * 第一阶段只走 Telegram 时（{@link botUrl} 不为空）一律去机器人：网页注册、邮箱登录都关了
 */
export function clientLink(page: 'register' | 'login', lang: Lang, base = clientUrl(), bot = botUrl()): string {
  if (bot) return bot;
  return `${base}/${page}?lang=${lang}`;
}
