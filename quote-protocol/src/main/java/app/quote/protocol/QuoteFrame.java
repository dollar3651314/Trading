package app.quote.protocol;

import java.math.BigDecimal;

/**
 * 一个品种的一条报价（协议里 {@code type=quote}）。价格是报价进程整合后的<b>干净价</b>（没有加点，TQ-3 B）。
 *
 * @param seq 这个品种的序号（同一个 epoch 里从 1 开始连续递增）
 * @param bid 买价（客户卖出价，未加点）
 * @param ask 卖价（客户买入价，未加点）
 * @param time 报价时间（毫秒）
 * @param status 报价质量：FRESH / STALE / NO_QUOTE / MARKET_CLOSED / ABNORMAL；不是 FRESH 时价格是上一条正常报价，不能成交
 * @param state 当前用哪一路：primary（主源）/ backup（备源）/ deviation（两家偏差过大，暂停开仓、固定用主源）/ simulated（模拟源）；没有为空
 * @param feed 当前报价源的名字（alltick / itick / binance / simulated）。只发给内部 Key；对外时这个字段不发（需求 03 页字段白名单）
 * @param tradable 这个报价能不能用来<b>开仓</b>：报价源授权登记里"交易用途"已授权，或者没有启用授权检查（测试环境）。
 *     为 false 时只显示、不开仓（TQ-11 A）；平仓、止盈止损、强平不看这一项
 * @param snapshot 是快照（断线重连或跳号后，以这条为新的起点），不是按序号接上的那一条
 */
public record QuoteFrame(String symbol, long seq, BigDecimal bid, BigDecimal ask, long time, String status, String state, String feed, boolean tradable,
    boolean snapshot) implements Message {

  public static final String FRESH = "FRESH";

  /** 同一条报价，标成快照 */
  public QuoteFrame asSnapshot() {
    return snapshot ? this : new QuoteFrame(symbol, seq, bid, ask, time, status, state, feed, tradable, true);
  }

  /** 同一条报价，去掉内部字段（报价源名字），给外部 Key 用 */
  public QuoteFrame withoutFeed() {
    return feed == null ? this : new QuoteFrame(symbol, seq, bid, ask, time, status, state, null, tradable, snapshot);
  }

  public boolean fresh() {
    return FRESH.equals(status);
  }
}
