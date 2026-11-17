/**
 * 报价内部 WebSocket 协议（技术方案 02 页第 2 节、TQ-2 A：内部和以后对外用同一套协议）。
 * <p>连接：{@code ws://<quote-app>/internal/ws/quotes}，请求头 {@code X-Quote-Key} 带内部 Key（只从环境变量来）。
 * <p>服务器 → 客户端（每条是一个 JSON 对象，{@code type} 区分）：
 * <ul>
 *   <li>{@code hello}：连上后第一条。{@code epoch} 是报价进程这次启动的编号，序号只在同一个 epoch 里连续；{@code mode} 是报价模式。</li>
 *   <li>{@code quote}：一个品种的一条报价（{@link QuoteFrame}）。<b>每个品种一个递增序号</b>，从 1 开始、每条加 1；
 *       每条都带完整的买卖价和状态（不是增量），{@code snapshot=true} 的是快照（客户端以它为新的起点）。</li>
 *   <li>{@code sample}：官网"示例行情"（模拟价，不带序号，不能用来成交）。</li>
 *   <li>{@code heartbeat}：每秒一条，带报价进程自己的告警项（报价源断线、切备源、偏差）。</li>
 * </ul>
 * 客户端 → 服务器：
 * <ul>
 *   <li>{@code subscribe}：收到 hello 后发。带上次收到的 epoch 和每个品种最后的序号（{@code since}）：epoch 相同且服务器缓存里还有，
 *       就按序号<b>补齐</b>断线期间的报价；补不上的品种发快照。第一次连接不带 since，全部发快照。</li>
 *   <li>{@code resync}：连接中发现序号跳号时发，服务器按同样的规则补齐或发快照。</li>
 * </ul>
 * 价格一律用字符串（不丢小数位）；时间是毫秒时间戳。
 */
package app.quote.protocol;
