package app.quote.open;

import static org.assertj.core.api.Assertions.assertThat;

import app.quote.QuoteProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 第 3 批复审：换 WebSocket 票据时 Redis 挂了，返回和对外 REST 一样格式的 503（code、message、traceId，带 Retry-After）；
 * 开发者文档在配了真实报价源时不给看（不只看报价模式）。
 */
class OpenRedisDownTest {

  @Test
  @SuppressWarnings("unchecked")
  void 换票据时Redis挂了返回统一格式的503() {
    StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
    Mockito.when(redis.opsForValue()).thenReturn(ops);
    Mockito.doThrow(new QueryTimeoutException("Redis 超时")).when(ops).set(Mockito.anyString(), Mockito.anyString(), Mockito.any(Duration.class));
    OpenProperties props = new OpenProperties("x", null, null, null, 0, null, null, null, null);
    Tickets tickets = new Tickets(props, Clock.systemUTC(), redis);
    OpenRestController ctl = new OpenRestController(null, null, null, null, null, tickets, Clock.systemUTC());
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/ws-ticket");
    req.setAttribute(OpenAuthFilter.KEY_ATTR, new ApiKeys.Key("qk_test", 1, ApiKeys.Kind.INTERNAL_TEST, true, Set.of(), Limits.DEFAULT, null, null, null, null));
    ResponseEntity<Object> r = ctl.ticket(req);
    assertThat(r.getStatusCode().value()).isEqualTo(503);
    assertThat(r.getHeaders().getFirst("Retry-After")).isEqualTo("5");
    assertThat(r.getHeaders().getFirst("X-Trace-Id")).isNotBlank();
    assertThat(((java.util.Map<String, Object>) r.getBody())).containsOnlyKeys("code", "message", "traceId").containsEntry("code", "SERVICE_UNAVAILABLE");
  }

  @Test
  void 配了真实报价源时开发者文档不给看() throws Exception {
    QuoteProperties sim = new QuoteProperties("simulated", null, null, null, null, null, null, null);
    OpenProperties auto = new OpenProperties("x", null, null, null, 0, null, "auto", null, null);
    app.quote.feed.QuoteFeedsProperties none = new app.quote.feed.QuoteFeedsProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    app.quote.feed.QuoteFeedsProperties real = new app.quote.feed.QuoteFeedsProperties(null, null, null, null, null, null, null, null, null, null,
        new app.quote.feed.QuoteFeedsProperties.AllTick("fake_token_for_test", null, null, null, null), null, null, null);
    assertThat(new OpenDocsController(auto, sim, none).docs().getStatusCode().value()).as("只用模拟源").isEqualTo(200);
    assertThat(new OpenDocsController(auto, sim, real).docs().getStatusCode().value()).as("配了真实报价源").isEqualTo(404);
    QuoteProperties live = new QuoteProperties("live", null, null, null, null, null, null, null);
    assertThat(new OpenDocsController(auto, live, none).docs().getStatusCode().value()).isEqualTo(404);
  }
}
