package app.quote.open;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** WebSocket 连接票据（技术方案 02 页第 4 节）：先用签名的 REST 换一张 5 分钟有效、只能用一次的票据，再连 WebSocket（浏览器里不暴露 Secret）。 */
@Component
class Tickets {

  record Ticket(String keyId, Instant expiresAt) {}

  private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
  private final OpenProperties props;
  private final Clock clock;

  Tickets(OpenProperties props, Clock clock) {
    this.props = props;
    this.clock = clock;
  }

  String issue(String keyId) {
    String t = "qt_" + SecretBox.random(32);
    tickets.put(t, new Ticket(keyId, clock.instant().plus(props.ticketTtl())));
    return t;
  }

  Instant expiresAt(String ticket) {
    Ticket t = tickets.get(ticket);
    return t == null ? null : t.expiresAt();
  }

  /** 用掉一张票据：没有、过期都为空 */
  Optional<String> consume(String ticket) {
    if (ticket == null) return Optional.empty();
    Ticket t = tickets.remove(ticket);
    if (t == null || clock.instant().isAfter(t.expiresAt())) return Optional.empty();
    return Optional.of(t.keyId());
  }

  @Scheduled(fixedDelay = 60_000)
  void purge() {
    Instant now = clock.instant();
    tickets.values().removeIf(t -> now.isAfter(t.expiresAt()));
  }
}
