package app.quote.protocol;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 协议里的一条消息。服务器发的和客户端发的都在这里，编解码见 {@link QuoteCodec}。 */
public sealed interface Message permits QuoteFrame, Message.Hello, Message.Sample, Message.Heartbeat, Message.Error, Message.Subscribe,
    Message.Resync, Message.Bar, Message.WatchBars {

  /**
   * 服务器：连上后的第一条。
   *
   * @param epoch 报价进程这次启动的编号；变了说明报价进程重启过，序号重新从 1 开始，客户端要丢掉旧的序号
   * @param mode 报价模式：none / simulated / live
   * @param sources 每个品种连上这一刻在用的报价源（还没有报价的品种也有，后台品种页用）；只发给内部 Key
   */
  record Hello(String epoch, String mode, long serverTime, List<Source> sources) implements Message {
    public Hello {
      sources = sources == null ? List.of() : List.copyOf(sources);
    }
  }

  /**
   * 一个品种在用的报价源（只发给内部 Key）。
   *
   * @param feed alltick / itick / binance / simulated
   * @param state primary / backup / deviation；模拟源为空
   */
  record Source(String symbol, String feed, String state) {}

  /** 服务器：官网"示例行情"（模拟价，不带序号，不能成交） */
  record Sample(String symbol, BigDecimal bid, BigDecimal ask, long time) implements Message {}

  /**
   * 服务器：心跳（每秒一条），带报价进程自己的告警项。
   *
   * @param alerts 报价源断线、切到备源、两家偏差等；没有配置报价源时为空
   */
  record Heartbeat(long serverTime, List<Alert> alerts) implements Message {}

  /** 一项告警（和 trading-app 的告警项同样的字段，trading-app 收到后照样发告警邮件） */
  record Alert(String name, String titleZh, String titleEn, boolean ok, String detail) {}

  /**
   * 服务器：一根 K 线有变化（新开或更新；只发给 {@link WatchBars} 关注了这个"品种|周期"的连接，不带序号，只用来显示）。
   * K 线用干净的中间价（不含加点），规则见报价服务的 {@code KlineService}。
   *
   * @param tf 周期代码：1m / 5m / 15m / 1h / 4h / 1d
   * @param time 这根 K 线的起点（UTC 秒）
   * @param ticks 这根 K 线收到的报价条数（不是成交量）
   */
  record Bar(String symbol, String tf, long time, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, int ticks) implements Message {}

  /**
   * 客户端：要推哪些 K 线的实时变化（整份替换；空 = 都不要）。
   *
   * @param keys "品种|周期"，例如 "XAUUSD|15m"
   */
  record WatchBars(java.util.Set<String> keys) implements Message {
    public WatchBars {
      keys = keys == null ? java.util.Set.of() : java.util.Set.copyOf(keys);
    }
  }

  /** 服务器：错误（例如消息格式不对） */
  record Error(String code, String message) implements Message {}

  /**
   * 客户端：订阅全部品种。
   *
   * @param epoch 上次连接收到的 epoch；第一次连接为空
   * @param since 每个品种最后收到的序号；epoch 相同时服务器从下一条开始补齐，补不上的发快照
   */
  record Subscribe(String epoch, Map<String, Long> since) implements Message {}

  /**
   * 客户端：连接中发现跳号，要求补齐。
   *
   * @param symbols 品种 → 最后连续收到的序号；-1 表示直接要快照
   */
  record Resync(Map<String, Long> symbols) implements Message {}
}
