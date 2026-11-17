package app.quote.stream;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** 内部 WebSocket {@code /internal/ws/quotes}（握手由 {@link InternalKeyFilter} 校验内部 Key）。 */
@Configuration
@EnableWebSocket
class StreamConfig implements WebSocketConfigurer {

  public static final String PATH = "/internal/ws/quotes";

  private final InternalQuoteSocket socket;

  StreamConfig(InternalQuoteSocket socket) {
    this.socket = socket;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(socket, PATH);
  }
}
