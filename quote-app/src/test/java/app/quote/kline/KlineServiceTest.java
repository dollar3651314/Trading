package app.quote.kline;

import static org.assertj.core.api.Assertions.assertThat;

import app.quote.pipeline.QuoteProcessor;
import app.quote.pipeline.RawQuote;
import app.quote.protocol.Timeframe;
import app.quote.support.FixedClockConfig;
import app.quote.support.QuoteAppTest;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * K 线聚合（第 2 批第二步从 trading-app 搬到报价服务）。第一个测试是拆分前 trading-app {@code MarketFlowTest} 里的同名测试，原样搬来；
 * 第二个测报价主链路接上了 K 线（干净的中间价，不含加点，和拆分前一样）。
 */
@QuoteAppTest
@Import(FixedClockConfig.class)
class KlineServiceTest {

  static final Instant NOW = FixedClockConfig.NOW;

  @Autowired KlineService klines;
  @Autowired QuoteProcessor processor;
  @Autowired JdbcTemplate jdbc;

  @Test
  void K线跨桶时上一根收盘写库_历史接口含当前这一根() {
    Instant a = NOW.minusSeconds(120);
    // 用固定时钟时，报价时间不能和"现在"差超过 5 秒，所以直接调用 K 线聚合
    klines.accept("SOLUSD", new BigDecimal("196.00"), a);
    klines.accept("SOLUSD", new BigDecimal("197.50"), a.plusSeconds(10));
    klines.accept("SOLUSD", new BigDecimal("195.00"), a.plusSeconds(20));
    klines.accept("SOLUSD", new BigDecimal("196.20"), a.plusSeconds(70));
    var bars = klines.history("SOLUSD", Timeframe.M1, 10);
    assertThat(bars).hasSizeGreaterThanOrEqualTo(2);
    var closed = bars.get(bars.size() - 2);
    assertThat(closed.open()).isEqualByComparingTo("196.00");
    assertThat(closed.high()).isEqualByComparingTo("197.50");
    assertThat(closed.low()).isEqualByComparingTo("195.00");
    assertThat(closed.close()).isEqualByComparingTo("195.00");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM market_kline WHERE symbol = 'SOLUSD' AND timeframe = '1m'", Integer.class)).isGreaterThanOrEqualTo(1);
  }

  @Test
  void 报价主链路的正常报价进K线_用不含加点的中间价() {
    processor.process(new RawQuote("XAUUSD", new BigDecimal("4126.35"), new BigDecimal("4126.75"), NOW));
    Bar last = klines.history("XAUUSD", Timeframe.M15, 1).getLast();
    assertThat(last.close()).isEqualByComparingTo("4126.55");
    assertThat(last.time()).isEqualTo(Timeframe.M15.bucket(NOW.getEpochSecond()));
  }
}
