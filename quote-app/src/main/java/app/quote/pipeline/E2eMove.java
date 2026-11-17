package app.quote.pipeline;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 端到端测试推动模拟报价（只在 profile e2e 下存在，要内部 Key）：trading-app 的 {@code /api/e2e/market/move} 转到这里。
 * 报价照常经过交易时段、质量守卫、内部 WebSocket，只是价格由测试指定，用来触发止盈止损。没有模拟源时返回 404。
 */
@RestController
@Profile("e2e")
class E2eMove {

  record Move(String symbol, BigDecimal bid) {}

  private final ObjectProvider<SimulatedQuoteSource> source;

  E2eMove(ObjectProvider<SimulatedQuoteSource> source) {
    this.source = source;
  }

  /** 把原始卖价（不含加点）移到 bid，之后从这里继续随机游走 */
  @PostMapping("/internal/e2e/move")
  ResponseEntity<Map<String, Object>> move(@RequestBody Move m) {
    if (m == null || m.symbol() == null || m.bid() == null || m.bid().signum() <= 0) return ResponseEntity.badRequest().build();
    SimulatedQuoteSource s = source.getIfAvailable();
    if (s == null) return ResponseEntity.notFound().build();
    return s.moveTo(m.symbol(), m.bid()).map(q -> {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("symbol", q.symbol());
      out.put("rawBid", q.bid().toPlainString());
      out.put("rawAsk", q.ask().toPlainString());
      return ResponseEntity.ok(out);
    }).orElse(ResponseEntity.notFound().build());
  }
}
