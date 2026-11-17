package app.quote.pipeline;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 报价进程处理后的一条报价：干净价（没有加点，TQ-3 B）+ 质量状态。不是 FRESH 时价格是上一条正常报价。
 *
 * @param executable 可以成交：状态 FRESH、没有过期（{@link QuoteProcessor#latest} 按 staleAfter 判断）
 */
public record CleanQuote(String symbol, BigDecimal bid, BigDecimal ask, Instant time, QuoteStatus status, boolean executable) {

  public CleanQuote withStatus(QuoteStatus s) {
    return new CleanQuote(symbol, bid, ask, time, s, s == QuoteStatus.FRESH);
  }
}
