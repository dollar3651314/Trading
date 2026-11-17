package app.quote.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.quote.QuoteProperties;
import app.quote.catalog.QuoteSymbol;
import app.quote.catalog.SymbolCatalog;
import app.quote.pipeline.QuoteProcessor;
import app.quote.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 主备切换 + 报价源授权（技术方案 08 页：当前源没有"交易用途"授权就切到有授权的备源；都没有的品种只显示不开仓）。
 * 直接调用 {@link QuoteRouter#onQuote}，时钟手动拨。授权用一个可改的假登记。
 */
class QuoteRouterLicenseTest {

  /** 假的授权登记：licensed 里的"报价商|品种"有交易用途授权 */
  static final class FakeLicenses implements TradingLicenses {
    boolean enforced = true;
    Set<String> licensed = Set.of();

    @Override
    public boolean enforced() {
      return enforced;
    }

    @Override
    public boolean tradingUse(String feed, String symbol) {
      return !enforced || licensed.contains(feed + "|" + symbol);
    }
  }

  MutableClock clock;
  QuoteProcessor processor;
  SymbolCatalog catalog;
  FakeLicenses licenses;
  QuoteRouter router;

  @BeforeEach
  void setUp() {
    clock = new MutableClock();
    processor = mock(QuoteProcessor.class);
    catalog = mock(SymbolCatalog.class);
    when(catalog.get(anyString())).thenReturn(Optional.of(new QuoteSymbol("EURUSD", "fx", 5, null)));
    licenses = new FakeLicenses();
    QuoteFeedsProperties p = new QuoteFeedsProperties("alltick", Duration.ofSeconds(10), Duration.ofSeconds(30), new BigDecimal("0.005"), Duration.ofSeconds(5),
        BigDecimal.ONE, Duration.ofSeconds(60), null, null, null, new QuoteFeedsProperties.AllTick("ci_only_fake_token", null, null, null, null),
        new QuoteFeedsProperties.ITick("ci_only_fake_token", null, null), null, Map.of("EURUSD", List.of("alltick:fx:EURUSD", "itick:forex:EURUSD$GB")));
    QuoteProperties m = new QuoteProperties("live", Duration.ofMillis(500), Duration.ofSeconds(5), Duration.ofMinutes(1), new BigDecimal("0.05"),
        new BigDecimal("0.10"), new BigDecimal("0.05"), Duration.ofSeconds(60));
    router = new QuoteRouter(m, p, processor, catalog, clock, new SimpleMeterRegistry(), licenses);
  }

  void both() {
    router.onQuote("alltick", "EURUSD", new BigDecimal("1.08000"), new BigDecimal("1.08010"), null);
    router.onQuote("itick", "EURUSD$GB", new BigDecimal("1.08001"), new BigDecimal("1.08011"), null);
  }

  @Test
  void 主源没授权_备源有授权且有新价_改用备源_不来回切() {
    licenses.licensed = Set.of("itick|EURUSD");
    both();
    assertThat(router.current("EURUSD")).contains(new QuoteSource("itick", "backup"));
    clock.advance(Duration.ofSeconds(31));
    both(); // 主源连续报价满 30 秒：没有授权时也不切回
    assertThat(router.current("EURUSD")).contains(new QuoteSource("itick", "backup"));
  }

  @Test
  void 主源有授权_照常用主源() {
    licenses.licensed = Set.of("alltick|EURUSD", "itick|EURUSD");
    both();
    assertThat(router.current("EURUSD")).contains(new QuoteSource("alltick", "primary"));
  }

  @Test
  void 两家都没授权_不动_照常用主源推报价() {
    both();
    assertThat(router.current("EURUSD")).contains(new QuoteSource("alltick", "primary"));
  }

  @Test
  void 有授权的备源没有新价时不切过去_它有价了再切() {
    licenses.licensed = Set.of("itick|EURUSD");
    router.onQuote("alltick", "EURUSD", new BigDecimal("1.08000"), new BigDecimal("1.08010"), null);
    assertThat(router.current("EURUSD")).contains(new QuoteSource("alltick", "primary"));
    router.onQuote("itick", "EURUSD$GB", new BigDecimal("1.08001"), new BigDecimal("1.08011"), null);
    assertThat(router.current("EURUSD")).contains(new QuoteSource("itick", "backup"));
  }

  @Test
  void 没启用授权检查时和以前一样() {
    licenses.enforced = false;
    both();
    assertThat(router.current("EURUSD")).contains(new QuoteSource("alltick", "primary"));
  }

  @Test
  void 最多用前两家_N家的结构留在常量里() {
    assertThat(QuoteRouter.MAX_ACTIVE_FEEDS).isEqualTo(2);
  }
}
