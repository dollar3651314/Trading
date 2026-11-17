package app.quote.open;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * WebSocket 连接票据（技术方案 02 页第 4 节）：先用签名的 REST 换一张 5 分钟有效、只能用一次的票据，再连 WebSocket（浏览器里不暴露 Secret）。
 * 第 3 批审查后放在 Redis（{@code fxq:ticket:…}，GETDEL 用掉），多实例时在哪个实例换的票据都能连。
 */
@Component
class Tickets {

  record Ticket(String ticket, String keyId, Instant expiresAt) {}

  private final OpenProperties props;
  private final Clock clock;
  private final org.springframework.data.redis.core.StringRedisTemplate redis;

  Tickets(OpenProperties props, Clock clock, org.springframework.data.redis.core.StringRedisTemplate redis) {
    this.props = props;
    this.clock = clock;
    this.redis = redis;
  }

  /** 发一张票据，返回票据和过期时间；Redis 连不上抛 DataAccessException */
  Ticket issue(String keyId) {
    String t = "qt_" + SecretBox.random(32);
    Instant exp = clock.instant().plus(props.ticketTtl());
    redis.opsForValue().set("fxq:ticket:" + t, keyId + "|" + exp.toEpochMilli(), props.ticketTtl());
    return new Ticket(t, keyId, exp);
  }

  /** 用掉一张票据：没有、过期、Redis 连不上都为空 */
  Optional<String> consume(String ticket) {
    if (ticket == null || !ticket.matches("qt_[A-Za-z0-9]{1,64}")) return Optional.empty();
    String v;
    try {
      v = redis.opsForValue().getAndDelete("fxq:ticket:" + ticket);
    } catch (org.springframework.dao.DataAccessException e) {
      return Optional.empty();
    }
    if (v == null) return Optional.empty();
    int i = v.lastIndexOf('|');
    if (i < 0 || clock.instant().toEpochMilli() > Long.parseLong(v.substring(i + 1))) return Optional.empty();
    return Optional.of(v.substring(0, i));
  }
}
