package app.quote.stream;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 内部 WebSocket 参数（quote.yml 的 quote.stream）。
 *
 * @param internalKey 内部 Key（trading-app 连接时放在请求头 X-Quote-Key）。<b>只从环境变量 FX_QUOTE_INTERNAL_KEY 来</b>；为空时拒绝所有连接和内部接口
 * @param heartbeat 心跳间隔（带报价进程的告警项）
 * @param replayBuffer 每个品种在内存里留最近多少条报价，用来给断线重连的客户端补齐；补不上就发快照
 * @param sendTimeLimit 给一条连接发消息最多等多久，超过就断开这条连接（客户端会重连补齐）
 * @param sendBufferLimit 一条连接积压的消息最多多少字节，超过就断开
 */
@ConfigurationProperties(prefix = "quote.stream")
public record StreamProperties(String internalKey, Duration heartbeat, int replayBuffer, Duration sendTimeLimit, int sendBufferLimit) {
  public StreamProperties {
    internalKey = internalKey == null ? "" : internalKey.strip();
    heartbeat = heartbeat == null ? Duration.ofSeconds(1) : heartbeat;
    replayBuffer = replayBuffer <= 0 ? 512 : replayBuffer;
    sendTimeLimit = sendTimeLimit == null ? Duration.ofSeconds(5) : sendTimeLimit;
    sendBufferLimit = sendBufferLimit <= 0 ? 4 * 1024 * 1024 : sendBufferLimit;
  }
}
