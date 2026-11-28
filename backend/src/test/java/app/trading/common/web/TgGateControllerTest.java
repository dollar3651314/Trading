package app.trading.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 方案 B 引导页的匿名计数 {@code POST /api/public/tg-gate-hit}（实际执行，真实 Redis）：不登录能用；按天、按平台大类计数；
 * Redis 里只有"日期:平台 → 次数"，没有 IP、UA、Cookie、客户号；不认识的平台算 OTHER；同一 IP 每分钟超过 20 次回 429。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TgGateControllerTest {

  static final String PREFIX = "fx:tggate-rl-test-" + UUID.randomUUID() + ":";

  @DynamicPropertySource
  static void prefix(DynamicPropertyRegistry r) {
    r.add("trading.rate-limit.key-prefix", () -> PREFIX);
  }

  @Autowired MockMvc mvc;
  @Autowired StringRedisTemplate redis;
  @Autowired TgGateCounter counter;
  @Autowired Clock clock;

  @AfterEach
  void cleanOwnKeys() {
    Set<String> keys = redis.keys(PREFIX + "*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  MockHttpServletResponse hit(String body, String ip) throws Exception {
    MockHttpServletRequestBuilder b = post("/api/public/tg-gate-hit").contentType(MediaType.APPLICATION_JSON).content(body)
        .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) UNIQUE-UA-MARKER").header("X-Forwarded-For", "198.51.100.77")
        .cookie(new Cookie("fx_session", "secret-session-value"));
    return mvc.perform(b.with(r -> {
      r.setRemoteAddr(ip);
      return r;
    })).andReturn().getResponse();
  }

  long today(String platform) {
    String v = redis.opsForValue().get(TgGateCounter.key(LocalDate.now(clock.withZone(ZoneOffset.UTC)), platform));
    return v == null ? 0 : Long.parseLong(v);
  }

  @Test
  void 不登录_按平台大类计数_只存日期和平台_没有IP和UA() throws Exception {
    long ios = today("IOS");
    long other = today("OTHER");
    assertThat(hit("{\"platform\":\"IOS\"}", "10.251.1.1").getStatus()).isEqualTo(204);
    assertThat(hit("{\"platform\":\"<script>\"}", "10.251.1.1").getStatus()).as("不认识的算 OTHER").isEqualTo(204);
    assertThat(today("IOS")).isEqualTo(ios + 1);
    assertThat(today("OTHER")).isEqualTo(other + 1);
    assertThat(counter.lastDays(7).get("IOS")).isGreaterThanOrEqualTo(ios + 1);

    Set<String> keys = redis.keys("fx:tggate:*");
    assertThat(keys).isNotEmpty().allMatch(k -> k.matches("fx:tggate:\\d{4}-\\d{2}-\\d{2}:(IOS|ANDROID|OTHER)"));
    for (String k : keys) {
      assertThat(redis.opsForValue().get(k)).matches("\\d+");
      assertThat(redis.getExpire(k)).as("自动过期").isPositive();
    }
    // 整个 Redis 里没有这次请求的 IP、UA、Cookie（限流计数用自己的前缀、按 IP，1 分钟过期，和全站按 IP 限流同一套，不算这里的存储）
    for (String k : redis.keys("*")) {
      if (k.startsWith(PREFIX)) continue;
      assertThat(k).doesNotContain("10.251.1.1").doesNotContain("198.51.100.77").doesNotContain("UNIQUE-UA-MARKER").doesNotContain("secret-session-value");
    }
  }

  @Test
  void 同一IP每分钟超过20次回429() throws Exception {
    for (int i = 0; i < TgGateController.PER_MINUTE; i++) assertThat(hit("{\"platform\":\"ANDROID\"}", "10.251.2.1").getStatus()).isEqualTo(204);
    assertThat(hit("{\"platform\":\"ANDROID\"}", "10.251.2.1").getStatus()).isEqualTo(429);
    assertThat(hit("{\"platform\":\"ANDROID\"}", "10.251.2.2").getStatus()).isEqualTo(204);
  }
}
