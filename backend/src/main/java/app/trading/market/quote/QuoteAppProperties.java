package app.trading.market.quote;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 连报价服务（quote-app）的参数（application.yml 的 trading.market.quote-app），只在 {@code trading.market.source=remote} 时用。
 *
 * @param url 报价服务的地址，例如 http://127.0.0.1:8090（内部 WebSocket 是同一地址的 /internal/ws/quotes）
 * @param key 内部 Key：<b>只从环境变量 FX_QUOTE_INTERNAL_KEY 来</b>，和报价服务配同一个值
 * @param reconnectInitial 断线重连第一次等多久，之后每次翻倍
 * @param reconnectMax 断线重连最长等多久
 * @param idleTimeout 多久没收到任何消息（服务器每秒一条心跳）就断开重连
 * @param syncRetry 同步品种和时段失败后多久重试
 * @param sendTimeout 给报价服务发一条消息最多等多久，超时断开重连
 */
@ConfigurationProperties(prefix = "trading.market.quote-app")
public record QuoteAppProperties(String url, String key, Duration reconnectInitial, Duration reconnectMax, Duration idleTimeout, Duration syncRetry, Duration sendTimeout) {
  public QuoteAppProperties {
    url = url == null ? "" : url.strip().replaceAll("/+$", "");
    key = key == null ? "" : key.strip();
    reconnectInitial = reconnectInitial == null ? Duration.ofMillis(500) : reconnectInitial;
    reconnectMax = reconnectMax == null ? Duration.ofSeconds(30) : reconnectMax;
    idleTimeout = idleTimeout == null ? Duration.ofSeconds(10) : idleTimeout;
    syncRetry = syncRetry == null ? Duration.ofSeconds(30) : syncRetry;
    sendTimeout = sendTimeout == null ? Duration.ofSeconds(3) : sendTimeout;
  }
}
