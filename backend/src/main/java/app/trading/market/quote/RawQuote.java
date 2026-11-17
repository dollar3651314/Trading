package app.trading.market.quote;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 一条干净的报价（报价服务整合后、没有加点），symbol 是平台品种代码。
 * 第 2 批拆进程后报价从内部 WebSocket 来（{@link app.quote.protocol.QuoteFrame}）；这个类型留给测试和端到端直接喂报价（{@link QuoteProcessor#process(RawQuote)}）。
 */
public record RawQuote(String symbol, BigDecimal bid, BigDecimal ask, Instant time) {}
