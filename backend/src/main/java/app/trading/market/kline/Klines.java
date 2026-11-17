package app.trading.market.kline;

import app.quote.protocol.Message;
import app.quote.protocol.Timeframe;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * K 线（第 2 批第二步：K 线只在报价服务里算，技术方案 02 页第 2 节第 4 条）。trading-app 只转发：历史走报价服务的内部接口，
 * 实时变化走内部 WebSocket 的 {@code bar}（按客户端图表订阅的"品种|周期"关注）。数值是干净的中间价（不含加点），和拆分前一样。
 * 没连报价服务（{@code trading.market.source=none}，后端测试）时什么都没有（{@link #NONE}）。
 */
public interface Klines {

  /** 最近 limit 根（含当前未收盘的一根），从旧到新；报价服务连不上时为空 */
  List<Message.Bar> history(String symbol, Timeframe tf, int limit);

  /** 每个品种最近 limit 根的收盘价（今日走势小图）；连不上时每个品种都是空列表 */
  Map<String, List<BigDecimal>> closes(List<String> symbols, Timeframe tf, int limit);

  /** 关注的 K 线有变化时回调（只有一个回调，客户端行情推送用） */
  void onUpdate(Consumer<Message.Bar> listener);

  /** 关注哪些 K 线（"品种|周期"，整份替换） */
  void watch(Set<String> keys);

  /** 没连报价服务：没有 K 线 */
  Klines NONE = new Klines() {
    @Override
    public List<Message.Bar> history(String symbol, Timeframe tf, int limit) {
      return List.of();
    }

    @Override
    public Map<String, List<BigDecimal>> closes(List<String> symbols, Timeframe tf, int limit) {
      Map<String, List<BigDecimal>> out = new java.util.LinkedHashMap<>();
      for (String s : symbols) out.put(s, List.of());
      return out;
    }

    @Override
    public void onUpdate(Consumer<Message.Bar> listener) {}

    @Override
    public void watch(Set<String> keys) {}
  };
}
