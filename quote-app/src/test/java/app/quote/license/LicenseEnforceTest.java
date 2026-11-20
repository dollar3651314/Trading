package app.quote.license;

import static org.assertj.core.api.Assertions.assertThat;

import app.quote.QuoteProperties;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 授权检查只看 FX_QUOTE_LICENSE_ENFORCE，所有环境默认关（Amos 2026-11-17"授权检查彻底关掉"，推翻第 3 批审查"在用真实数据时一律检查"）：
 * 报价模式 live、配了真实报价源都不再强制打开；只有明确设 true 才检查。真实数据的判断（realData）只剩开发者文档 /docs/ 的 auto 在用。
 * 检查打开后的行为（开仓只用有交易用途授权的源、外部 Key 只拿转售已授权的品种）由 LicenseRegistryTest、OpenServiceTest 在 enforce=true 下覆盖。
 */
class LicenseEnforceTest {

  static LicenseRegistry registry(String enforce) {
    return new LicenseRegistry(Mockito.mock(JdbcTemplate.class), Clock.systemUTC(), new LicenseProperties(enforce));
  }

  @Test
  void 默认关_所有环境都一样() {
    assertThat(registry(null).enforced()).as("没设").isFalse();
    assertThat(registry("").enforced()).isFalse();
    assertThat(registry("false").enforced()).isFalse();
    assertThat(registry("auto").enforced()).as("旧写法 auto 也是关").isFalse();
    assertThat(registry("yes").enforced()).as("写错的值按关").isFalse();
  }

  @Test
  void 明确设true才检查() {
    assertThat(registry("true").enforced()).isTrue();
    assertThat(registry(" TRUE ").enforced()).isTrue();
  }

  @Test
  void 是否检查和报价模式_真实报价源无关() {
    // 构造函数不再收报价模式、报价源：live、配了真实报价源 + 默认 → 不检查；+ true → 检查（Spring 注入同一个 LicenseProperties）
    QuoteProperties live = new QuoteProperties("live", null, null, null, null, null, null, null);
    app.quote.feed.QuoteFeedsProperties withToken = new app.quote.feed.QuoteFeedsProperties(null, null, null, null, null, null, null, null, null, null,
        new app.quote.feed.QuoteFeedsProperties.AllTick("fake_token_for_test", null, null, null, null), null, null, null);
    assertThat(LicenseRegistry.realData(live, withToken)).as("确实是真实数据").isTrue();
    assertThat(registry(null).enforced()).as("live + 默认").isFalse();
    assertThat(registry("true").enforced()).as("live + true").isTrue();
  }

  @Test
  void 真实数据判断仍给开发者文档用() {
    QuoteProperties sim = new QuoteProperties("simulated", null, null, null, null, null, null, null);
    app.quote.feed.QuoteFeedsProperties withToken = new app.quote.feed.QuoteFeedsProperties(null, null, null, null, null, null, null, null, null, null,
        new app.quote.feed.QuoteFeedsProperties.AllTick("fake_token_for_test", null, null, null, null), null, null, null);
    assertThat(LicenseRegistry.realData(sim, withToken)).isTrue();
    assertThat(LicenseRegistry.realData(sim, new app.quote.feed.QuoteFeedsProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null)))
        .isFalse();
  }
}
