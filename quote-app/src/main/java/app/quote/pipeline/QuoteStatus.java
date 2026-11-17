package app.quote.pipeline;

/** 报价质量（沿用拆分前的 5 态，功能地图 M-06）。只有 FRESH 可以成交。协议里用名字传（{@code QuoteFrame#status}）。 */
public enum QuoteStatus {
  FRESH,
  STALE,
  NO_QUOTE,
  MARKET_CLOSED,
  ABNORMAL
}
