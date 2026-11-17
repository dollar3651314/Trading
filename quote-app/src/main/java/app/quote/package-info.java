/**
 * 报价进程（quote-app）：报价源接入和主备切换（{@code feed}）、交易时段和质量守卫（{@code pipeline}）、品种和时段（{@code catalog}）、
 * 内部 WebSocket（{@code stream}，协议在 quote-protocol 模块）、报价源授权登记（{@code license}）、健康检查和告警（{@code health}）。
 * <p>只出干净的原始买卖价（没有加点，TQ-3 B）；加点、撮合、K 线、客户推送都在 trading-app。trading-app 不能 import 这里的类，
 * 只能通过 quote-protocol 收报价（trading-app 的 ArchUnit 测试检查）。
 */
package app.quote;
