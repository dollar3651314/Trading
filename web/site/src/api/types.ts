/**
 * 官网公开行情 `GET /api/public/quotes` 的返回（后端 market/web/PublicQuoteController）。
 * 只有这些字段：品种、中英文名、卖价、当日涨跌幅、时间、状态（AC2-03）。价格、涨跌幅、时间只在 OPEN 时有值。
 */
export type QuoteStatus = 'OPEN' | 'CLOSED' | 'NO_QUOTE';

export interface PublicQuote {
  symbol: string;
  nameZh: string;
  nameEn: string;
  /** 卖价，已按品种小数位格式化的字符串 */
  bid: string | null;
  /** 当日涨跌幅（%），两位小数，例如 "0.62"、"-1.35" */
  changePct: string | null;
  /** 报价时间（ISO） */
  time: string | null;
  status: QuoteStatus;
}

export interface PublicQuotes {
  /** 报价来自模拟报价源（测试环境）：页面标"示例行情" */
  sample: boolean;
  /**
   * 第 3 批（TD-9 A、TY-4）：生产不显示价格（没有公开展示授权）：每行只列品种名和"登录后查看实时行情"，不闪动。
   * 接口在这种情况下不返回任何价格
   */
  pricesHidden?: boolean;
  items: PublicQuote[];
}
