package app.quote.feed;

/**
 * 品种当前的报价源。
 *
 * @param feed alltick / itick / binance / simulated
 * @param state primary（主源）/ backup（正在用备源）/ deviation（两家偏差过大、暂停开仓，用主源价格）；模拟源为空
 */
public record QuoteSource(String feed, String state) {}
