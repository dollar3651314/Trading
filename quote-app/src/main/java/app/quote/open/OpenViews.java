package app.quote.open;

import app.quote.kline.Bar;
import app.quote.protocol.QuoteFrame;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对外返回的字段（白名单，需求 03 页第二节、AC-Q8）：只有品种、买价、卖价、时间、状态等；<b>不含</b>报价商名称、能不能开仓、Trading 加点后的价格、
 * 客户、持仓、敞口、阈值。价格是干净价（TQ-3 B）。字段和开发者文档 /docs/ 逐字段一致（测试核对）。
 * <p>状态：OPEN 开市有价 / DEVIATION 两家报价源偏差过大（照常给主源价格）/ CLOSED 休市 / NO_QUOTE 暂无报价（含"现在的源没有转售授权"）。
 * 休市和暂无报价时<b>不给价格</b>（不推过期价）。
 */
final class OpenViews {

  private OpenViews() {}

  static String status(QuoteFrame f, boolean licensed) {
    if (f == null || !licensed) return "NO_QUOTE";
    return switch (f.status()) {
      case QuoteFrame.FRESH -> "deviation".equals(f.state()) ? "DEVIATION" : "OPEN";
      case "MARKET_CLOSED" -> "CLOSED";
      default -> "NO_QUOTE";
    };
  }

  static boolean priced(String status) {
    return "OPEN".equals(status) || "DEVIATION".equals(status);
  }

  /** REST 最新报价的一项 */
  static Map<String, Object> quote(String symbol, QuoteFrame f, boolean licensed, boolean closedNow) {
    String st = f == null && closedNow ? "CLOSED" : status(f, licensed);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("symbol", symbol);
    if (priced(st)) {
      m.put("bid", f.bid().toPlainString());
      m.put("ask", f.ask().toPlainString());
    }
    m.put("time", f == null ? null : f.time());
    m.put("status", st);
    return m;
  }

  /** WebSocket 的一条报价（type=quote，和内部协议同名字段，去掉 feed、state、tradable） */
  static Map<String, Object> wsQuote(QuoteFrame f, boolean licensed) {
    String st = status(f, licensed);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", "quote");
    m.put("symbol", f.symbol());
    m.put("seq", f.seq());
    if (priced(st)) {
      m.put("bid", f.bid().toPlainString());
      m.put("ask", f.ask().toPlainString());
    }
    m.put("time", f.time());
    m.put("status", st);
    if (f.snapshot()) m.put("snapshot", true);
    return m;
  }

  /** K 线一根（REST 和 WebSocket 共用字段；价格按品种小数位，和交易应用图表一样） */
  static Map<String, Object> bar(Bar b, int digits) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("time", b.time());
    m.put("open", price(b.open(), digits));
    m.put("high", price(b.high(), digits));
    m.put("low", price(b.low(), digits));
    m.put("close", price(b.close(), digits));
    return m;
  }

  static Map<String, Object> wsBar(Bar b, int digits) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", "bar");
    m.put("symbol", b.symbol());
    m.put("tf", b.tf().code);
    m.putAll(bar(b, digits));
    return m;
  }

  static String price(BigDecimal v, int digits) {
    return v.setScale(digits, RoundingMode.HALF_UP).toPlainString();
  }

  static String hhmm(int minute) {
    return String.format("%02d:%02d", minute / 60, minute % 60);
  }
}
