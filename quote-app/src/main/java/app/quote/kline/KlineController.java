package app.quote.kline;

import app.quote.protocol.Timeframe;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * K 线历史的内部接口（要内部 Key；trading-app 的 {@code /api/market/klines}、{@code /api/market/sparklines} 转到这里）。
 * 价格原样返回（字符串，不按小数位截断，trading-app 按品种小数位格式化）。实时变化走内部 WebSocket 的 {@code bar}。
 */
@RestController
class KlineController {

  record BarView(long time, String open, String high, String low, String close, int ticks) {}

  private final KlineService klines;

  KlineController(KlineService klines) {
    this.klines = klines;
  }

  /** 最近 limit 根（含当前未收盘的一根），从旧到新；limit 1～1000 */
  @GetMapping("/internal/klines")
  ResponseEntity<Object> history(@RequestParam String symbol, @RequestParam String tf, @RequestParam(defaultValue = "300") int limit) {
    var t = Timeframe.of(tf);
    if (t.isEmpty()) return ResponseEntity.badRequest().body(Map.of("code", "TIMEFRAME_INVALID", "message", "K 线周期不支持"));
    int n = Math.max(1, Math.min(limit, 1000));
    List<BarView> out = klines.history(symbol, t.get(), n).stream()
        .map(b -> new BarView(b.time(), b.open().toPlainString(), b.high().toPlainString(), b.low().toPlainString(), b.close().toPlainString(), b.ticks()))
        .toList();
    return ResponseEntity.ok(out);
  }

  /** 每个品种最近 limit 根的收盘价（今日走势小图）；symbols 逗号分隔 */
  @GetMapping("/internal/klines/closes")
  ResponseEntity<Object> closes(@RequestParam List<String> symbols, @RequestParam String tf, @RequestParam(defaultValue = "24") int limit) {
    var t = Timeframe.of(tf);
    if (t.isEmpty()) return ResponseEntity.badRequest().body(Map.of("code", "TIMEFRAME_INVALID", "message", "K 线周期不支持"));
    int n = Math.max(1, Math.min(limit, 1000));
    Map<String, List<String>> out = new LinkedHashMap<>();
    for (String s : symbols) out.put(s, klines.history(s, t.get(), n).stream().map(Bar::close).map(BigDecimal::toPlainString).toList());
    return ResponseEntity.ok(out);
  }
}
