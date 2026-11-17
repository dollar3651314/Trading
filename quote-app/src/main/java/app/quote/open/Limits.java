package app.quote.open;

/**
 * 一个 Key 的限额（需求 03 页第三节，TD-8）。
 *
 * @param restPerSecond REST 每秒请求数
 * @param restPerDay REST 每天请求数（UTC 日）
 * @param wsConnections WebSocket 同时连接数
 * @param wsSymbols 每条 WebSocket 连接最多订阅几个品种
 */
public record Limits(int restPerSecond, int restPerDay, int wsConnections, int wsSymbols) {

  /** 全平台默认值（TD-8）：REST 每秒 10 次、每天 10 万次，WebSocket 2 条、每条 50 个品种；后台可改，改了只影响以后新建的 Key */
  public static final Limits DEFAULT = new Limits(10, 100_000, 2, 50);

  /** 每项都要是正整数，且不超过合理上限；不对抛 {@link IllegalArgumentException} */
  public Limits validate() {
    if (restPerSecond < 1 || restPerSecond > 10_000) throw new IllegalArgumentException("REST 每秒请求数要在 1～10000 之间");
    if (restPerDay < 1 || restPerDay > 1_000_000_000) throw new IllegalArgumentException("REST 每天请求数要在 1～1000000000 之间");
    if (wsConnections < 1 || wsConnections > 100) throw new IllegalArgumentException("WebSocket 连接数要在 1～100 之间");
    if (wsSymbols < 1 || wsSymbols > 1000) throw new IllegalArgumentException("每条连接订阅品种数要在 1～1000 之间");
    return this;
  }
}
