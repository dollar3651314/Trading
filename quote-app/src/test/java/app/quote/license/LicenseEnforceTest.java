package app.quote.license;

import static org.assertj.core.api.Assertions.assertThat;

import app.quote.QuoteProperties;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 第 3 批审查：授权检查在生产（报价模式 live）一律启用，FX_QUOTE_LICENSE_ENFORCE=false 只在非生产有效，
 * 不能用一个变量关掉生产的"交易用途"和"转售"检查。
 */
class LicenseEnforceTest {

  static LicenseRegistry registry(String source, String enforce) {
    return registry(source, enforce, null);
  }

  /** alltickToken 不为空 = 配了真实报价源 */
  static LicenseRegistry registry(String source, String enforce, String alltickToken) {
    QuoteProperties q = new QuoteProperties(source, null, null, null, null, null, null, null);
    app.quote.feed.QuoteFeedsProperties f = new app.quote.feed.QuoteFeedsProperties(null, null, null, null, null, null, null, null, null, null,
        new app.quote.feed.QuoteFeedsProperties.AllTick(alltickToken, null, null, null, null), null, null, null);
    return new LicenseRegistry(Mockito.mock(JdbcTemplate.class), Clock.systemUTC(), new LicenseProperties(enforce), q, f);
  }

  @Test
  void 非live但配了真实报价源_也一律检查_文档不给看() {
    assertThat(registry("simulated", "false", "fake_token_for_test").enforced()).isTrue();
    assertThat(registry("simulated", "auto", "fake_token_for_test").enforced()).isTrue();
    assertThat(registry("simulated", "auto", null).enforced()).as("只用模拟源").isFalse();
    QuoteProperties q = new QuoteProperties("simulated", null, null, null, null, null, null, null);
    app.quote.feed.QuoteFeedsProperties withToken = new app.quote.feed.QuoteFeedsProperties(null, null, null, null, null, null, null, null, null, null,
        new app.quote.feed.QuoteFeedsProperties.AllTick("fake_token_for_test", null, null, null, null), null, null, null);
    assertThat(LicenseRegistry.realData(q, withToken)).isTrue();
    assertThat(LicenseRegistry.realData(q, new app.quote.feed.QuoteFeedsProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null)))
        .isFalse();
  }

  @Test
  void 生产live时false无效_一律检查() {
    assertThat(registry("live", "false").enforced()).isTrue();
    assertThat(registry("live", "auto").enforced()).isTrue();
    assertThat(registry("live", "true").enforced()).isTrue();
  }

  @Test
  void 非生产时按变量_默认不检查() {
    assertThat(registry("simulated", "false").enforced()).isFalse();
    assertThat(registry("simulated", "auto").enforced()).isFalse();
    assertThat(registry("simulated", "true").enforced()).isTrue();
    assertThat(registry("none", null).enforced()).isFalse();
  }
}
