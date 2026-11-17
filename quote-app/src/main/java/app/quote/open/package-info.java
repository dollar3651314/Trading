/**
 * 报价对外服务（第 3 批，需求 03 页第二～五节，技术方案 02 页第 4 节）：外部客户和 Key、HMAC 签名鉴权、限额（429）、按 Key / 按天 / 按品种计量
 * （只计量不收费，TD-6 A）、两道闸（对外开放总开关默认关 + 报价源"转售"授权登记）、对外 REST {@code /v1/**} 和 WebSocket {@code /v1/ws}
 * （和内部同一套协议，TQ-2 A；只出干净价，TQ-3 B）、开发者文档 {@code /docs/}、后台用的内部接口 {@code /internal/open/**}。
 * <p>后台操作（审计、二次确认、只有超管能开总开关）在 trading-app 后台做，经内部接口调到这里。
 */
package app.quote.open;
