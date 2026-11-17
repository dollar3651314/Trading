package app.trading.market.quote;

import app.trading.common.e2e.E2eGuard;
import app.trading.market.api.MarketQuote;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 端到端测试推动报价（S19，只在 profile e2e 下存在；e2e 里连着模拟模式的报价服务）。
 * 推动转给报价服务的 {@code /internal/e2e/move}（第 2 批拆进程后模拟源在报价服务里），报价照常经过报价服务（交易时段、质量守卫）、
 * 内部 WebSocket、{@link QuoteProcessor}（加点、推送、撮合），只是价格由测试指定，用来触发止盈止损。
 * 没连报价服务（{@code trading.market.source} 不是 remote）或报价服务不是模拟模式时返回 404。
 */
@RestController
@Profile(E2eGuard.PROFILE)
class E2eMarket {

  record Move(String symbol, BigDecimal bid) {}

  private final ObjectProvider<RemoteQuotes> source;
  private final QuoteProcessor processor;

  E2eMarket(ObjectProvider<RemoteQuotes> source, QuoteProcessor processor) {
    this.source = source;
    this.processor = processor;
  }

  /** 最新报价（含加点，和客户看到的一样） */
  @GetMapping("/api/e2e/market/quote")
  ResponseEntity<Map<String, Object>> quote(@RequestParam String symbol) {
    return processor.latest(symbol).map(E2eMarket::view).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
  }

  /** 把原始卖价（不含加点）移到 bid，之后从这里继续随机游走 */
  @PostMapping("/api/e2e/market/move")
  ResponseEntity<Map<String, Object>> move(@RequestBody Move m) {
    if (m == null || m.symbol() == null || m.bid() == null || m.bid().signum() <= 0) return ResponseEntity.badRequest().build();
    RemoteQuotes s = source.getIfAvailable();
    if (s == null) return ResponseEntity.notFound().build();
    return s.client().e2eMove(m.symbol(), m.bid())
        .map(n -> {
          Map<String, Object> out = new LinkedHashMap<>();
          out.put("symbol", n.path("symbol").asString());
          out.put("rawBid", n.path("rawBid").asString());
          out.put("rawAsk", n.path("rawAsk").asString());
          return ResponseEntity.ok(out);
        })
        .orElse(ResponseEntity.notFound().build());
  }

  private static Map<String, Object> view(MarketQuote q) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("symbol", q.symbol());
    out.put("bid", q.bid().toPlainString());
    out.put("ask", q.ask().toPlainString());
    out.put("executable", q.executable());
    out.put("time", q.time().toString());
    return out;
  }
}
