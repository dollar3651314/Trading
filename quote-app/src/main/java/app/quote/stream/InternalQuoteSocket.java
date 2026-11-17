package app.quote.stream;

import app.quote.protocol.Message;
import app.quote.protocol.QuoteCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** 内部 WebSocket 的连接处理：连上发 hello，收 subscribe / resync，转给 {@link QuoteStream}。 */
@Component
class InternalQuoteSocket extends TextWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(InternalQuoteSocket.class);

  private final QuoteStream stream;

  InternalQuoteSocket(QuoteStream stream) {
    this.stream = stream;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession s) {
    stream.open(s);
  }

  @Override
  protected void handleTextMessage(WebSocketSession s, TextMessage msg) {
    Message m;
    try {
      m = QuoteCodec.decode(msg.getPayload());
    } catch (IllegalArgumentException e) {
      stream.error(s.getId(), "BAD_MESSAGE", e.getMessage());
      return;
    }
    switch (m) {
      case Message.Subscribe sub -> stream.subscribe(s.getId(), sub);
      case Message.Resync r -> stream.resync(s.getId(), r);
      default -> stream.error(s.getId(), "BAD_MESSAGE", "客户端只能发 subscribe、resync");
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
    stream.close(s.getId());
  }

  @Override
  public void handleTransportError(WebSocketSession s, Throwable e) {
    log.debug("内部报价连接出错 {}", e.getClass().getSimpleName());
  }
}
