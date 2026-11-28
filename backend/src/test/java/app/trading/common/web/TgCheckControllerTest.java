package app.trading.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.servlet.http.Cookie;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Telegram 内置浏览器识别诊断接口 {@code GET /api/public/tg-check}（实际执行，真实 MySQL、Redis）：
 * 不登录能用；只回 5 个请求头原样，不回 Cookie、IP；no-store；什么都不写库；同一 IP 每分钟超过 30 次回 429。
 * 限流计数 key 带本测试独有的前缀，测试后只删自己的。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TgCheckControllerTest {

  static final String PREFIX = "fx:tgcheck-test-" + UUID.randomUUID() + ":";

  @DynamicPropertySource
  static void prefix(DynamicPropertyRegistry r) {
    r.add("trading.rate-limit.key-prefix", () -> PREFIX);
  }

  @Autowired MockMvc mvc;
  @Autowired StringRedisTemplate redis;

  @AfterEach
  void cleanOwnKeys() {
    Set<String> keys = redis.keys(PREFIX + "*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  MockHttpServletResponse call(MockHttpServletRequestBuilder b, String ip) throws Exception {
    return mvc.perform(b.with(r -> {
      r.setRemoteAddr(ip);
      return r;
    })).andReturn().getResponse();
  }

  @Test
  void 不登录_只回几个请求头原样_不回Cookie和IP_不缓存() throws Exception {
    String ua = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Telegram-iOS/11.4";
    MockHttpServletResponse r = call(get("/api/public/tg-check").header("User-Agent", ua).header("Sec-CH-UA", "\"Chromium\";v=\"130\"")
        .header("Sec-CH-UA-Mobile", "?1").header("Sec-CH-UA-Platform", "\"Android\"").header("X-Requested-With", "org.telegram.messenger")
        .header("X-Forwarded-For", "203.0.113.9").cookie(new Cookie("fx_session", "secret-session-value")), "10.250.1.1");
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(r.getHeader("Cache-Control")).contains("no-store");
    String body = r.getContentAsString();
    assertThat(body).contains("\"User-Agent\":\"" + ua + "\"").contains("\"Sec-CH-UA-Mobile\":\"?1\"")
        .contains("\"X-Requested-With\":\"org.telegram.messenger\"").contains("\"Sec-CH-UA-Platform\":\"\\\"Android\\\"\"");
    assertThat(body).doesNotContain("secret-session-value").doesNotContain("10.250.1.1").doesNotContain("203.0.113.9").doesNotContain("fx_session");
    assertThat(r.getHeader("Set-Cookie")).isNull();

    // 没有的头为 null，字段只有这 5 个
    String empty = call(get("/api/public/tg-check"), "10.250.1.2").getContentAsString();
    assertThat(empty).isEqualTo("{\"User-Agent\":null,\"Sec-CH-UA\":null,\"Sec-CH-UA-Mobile\":null,\"Sec-CH-UA-Platform\":null,\"X-Requested-With\":null}");
  }

  @Test
  void 同一IP每分钟超过30次回429_别的IP不受影响() throws Exception {
    for (int i = 0; i < TgCheckController.PER_MINUTE; i++) assertThat(call(get("/api/public/tg-check"), "10.250.2.1").getStatus()).isEqualTo(200);
    MockHttpServletResponse r = call(get("/api/public/tg-check"), "10.250.2.1");
    assertThat(r.getStatus()).isEqualTo(429);
    assertThat(r.getContentAsString()).contains("RATE_LIMITED");
    assertThat(call(get("/api/public/tg-check"), "10.250.2.2").getStatus()).isEqualTo(200);
  }
}
