package app.trading.common.web;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 方案 B（11/28 Amos）"非 Telegram 打开被拦次数"的匿名计数：客户端网页的引导页显示一次计一次，按平台大类（IOS / ANDROID / OTHER）、按天（UTC）分桶。
 * <ul>
 *   <li><b>只存计数</b>：Redis 键 {@code fx:tggate:<日期>:<平台>}，值是次数，9 天后自动过期；不存 IP、客户号、UA、Cookie；</li>
 *   <li>后台仪表盘看近 7 天合计（{@link #lastDays}），Amos 据此判断"反馈多不多"；</li>
 *   <li>Redis 不可用时不计、不报错（计数不能影响页面）。</li>
 * </ul>
 */
@Component
public class TgGateCounter {

  private static final Logger log = LoggerFactory.getLogger(TgGateCounter.class);

  public static final List<String> PLATFORMS = List.of("IOS", "ANDROID", "OTHER");
  static final String PREFIX = "fx:tggate:";
  static final Duration KEEP = Duration.ofDays(9);

  private final StringRedisTemplate redis;
  private final Clock clock;

  public TgGateCounter(StringRedisTemplate redis, Clock clock) {
    this.redis = redis;
    this.clock = clock;
  }

  static String key(LocalDate day, String platform) {
    return PREFIX + day + ":" + platform;
  }

  /** 计一次；不认识的平台算 OTHER */
  public void hit(String platform) {
    String p = platform != null && PLATFORMS.contains(platform) ? platform : "OTHER";
    String k = key(LocalDate.now(clock.withZone(ZoneOffset.UTC)), p);
    try {
      Long n = redis.opsForValue().increment(k);
      if (n != null && n == 1) redis.expire(k, KEEP);
    } catch (RuntimeException e) {
      log.debug("拦截计数没记上：{}", e.toString());
    }
  }

  /** 近 days 天（含今天，UTC）每个平台的合计 */
  public Map<String, Long> lastDays(int days) {
    Map<String, Long> out = new LinkedHashMap<>();
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    for (String p : PLATFORMS) {
      long sum = 0;
      for (int i = 0; i < days; i++) {
        try {
          String v = redis.opsForValue().get(key(today.minusDays(i), p));
          if (v != null) sum += Long.parseLong(v);
        } catch (RuntimeException e) {
          log.debug("拦截计数读不到：{}", e.toString());
        }
      }
      out.put(p, sum);
    }
    return out;
  }
}
