package app.quote.open;

import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 对外 WebSocket {@code /v1/ws?ticket=…} 的握手和消息转发。握手：票据没有、过期、已用过、Key 已停用 → 401；外部 Key 遇到总开关关着 → 403；
 * 已经有"WebSocket 连接数限额"那么多条连接 → 429（第 N+1 条被拒，已有的不受影响）。
 */
@Configuration
class OpenSocket extends TextWebSocketHandler implements WebSocketConfigurer, HandshakeInterceptor {

  static final String PATH = "/v1/ws";
  private static final String KEY_ATTR = "openKeyId";
  private static final String LEASE_ATTR = "openLease";

  private final OpenStream stream;
  private final Tickets tickets;
  private final ApiKeys keys;
  private final Entitlements ent;
  private final Usage usage;

  OpenSocket(OpenStream stream, Tickets tickets, ApiKeys keys, Entitlements ent, Usage usage) {
    this.stream = stream;
    this.tickets = tickets;
    this.keys = keys;
    this.ent = ent;
    this.usage = usage;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(this, PATH).addInterceptors(this).setAllowedOriginPatterns("*");
  }

  @Override
  public boolean beforeHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h, Map<String, Object> attrs) {
    String ticket = UriComponentsBuilder.fromUri(req.getURI()).build().getQueryParams().getFirst("ticket");
    var keyId = tickets.consume(ticket);
    var key = keyId.flatMap(keys::find);
    if (key.isEmpty() || !key.get().active()) {
      res.setStatusCode(HttpStatus.UNAUTHORIZED);
      return false;
    }
    if (!ent.gatePasses(key.get())) {
      res.setStatusCode(HttpStatus.FORBIDDEN);
      return false;
    }
    String lease = usage.wsOpen(key.get());
    if (lease == null) {
      res.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
      return false;
    }
    attrs.put(KEY_ATTR, key.get().keyId());
    attrs.put(LEASE_ATTR, lease);
    if (req instanceof org.springframework.http.server.ServletServerHttpRequest r) {
      r.getServletRequest().setAttribute(KEY_ATTR, key.get().keyId());
      r.getServletRequest().setAttribute(LEASE_ATTR, lease);
    }
    return true;
  }

  @Override
  public void afterHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h, Exception e) {
    // 握手失败时连接数要还回去：afterConnectionEstablished 没有执行，下面的 afterConnectionClosed 也不会执行
    if (e != null) {
      if (req instanceof org.springframework.http.server.ServletServerHttpRequest r && r.getServletRequest().getAttribute(KEY_ATTR) instanceof String s) {
        usage.wsClose(s, (String) r.getServletRequest().getAttribute(LEASE_ATTR));
      }
    }
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession s) {
    stream.open(s, (String) s.getAttributes().get(KEY_ATTR), (String) s.getAttributes().get(LEASE_ATTR));
  }

  @Override
  protected void handleTextMessage(WebSocketSession s, TextMessage msg) {
    stream.handle(s.getId(), msg.getPayload());
  }

  @Override
  public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
    stream.close(s.getId());
  }
}
