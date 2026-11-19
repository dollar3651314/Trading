package app.quote.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 联调 O-1（11/19）：模拟价围绕起始价回归均值，每天波动约 0.5%、长期离起始价约 1%。
 * 以前是没有中心的随机游走（每天约 8%，EURUSD 两天从 1.157 走到 1.037）。固定随机种子，结果确定。
 */
class SimulatedWalkTest {

  static final int STEPS_PER_DAY = 2 * 86_400; // 0.5 秒一跳

  @Test
  void 每天波动和长期偏离都接近真实外汇_不会越走越远() {
    double sigma = SimulatedQuoteSource.stepSigma(Duration.ofMillis(500));
    double theta = SimulatedQuoteSource.stepTheta(sigma);
    assertThat(sigma * Math.sqrt(STEPS_PER_DAY)).as("每天约 0.5%").isCloseTo(0.005, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(sigma / Math.sqrt(2 * theta)).as("长期标准差 1%").isCloseTo(0.01, org.assertj.core.data.Offset.offset(1e-9));
    Random rnd = new Random(20261119);
    double ref = 1.157;
    double maxDev = 0;
    for (int run = 0; run < 3; run++) {
      double p = ref;
      for (int day = 0; day < 10; day++) {
        double lo = p, hi = p;
        for (int i = 0; i < STEPS_PER_DAY; i++) {
          p = SimulatedQuoteSource.step(p, ref, sigma, theta, rnd.nextGaussian());
          lo = Math.min(lo, p);
          hi = Math.max(hi, p);
        }
        assertThat((hi - lo) / ref).as("每天的高低差").isBetween(0.002, 0.025);
        maxDev = Math.max(maxDev, Math.abs(p / ref - 1));
      }
    }
    assertThat(maxDev).as("10 天里每天收盘离起始价不超过 4%（4 个标准差）").isLessThan(0.04);
  }

  @Test
  void 离中心越远拉回越多_换报价间隔每天波动不变() {
    double sigma = SimulatedQuoteSource.stepSigma(Duration.ofMillis(500));
    double theta = SimulatedQuoteSource.stepTheta(sigma);
    double up = SimulatedQuoteSource.step(1.10, 1.00, sigma, theta, 0);
    assertThat(up).isLessThan(1.10).isGreaterThan(1.00);
    assertThat(SimulatedQuoteSource.step(1.00, 1.00, sigma, theta, 0)).isEqualTo(1.00);
    double s1 = SimulatedQuoteSource.stepSigma(Duration.ofSeconds(1));
    assertThat(s1 * Math.sqrt(86_400)).isCloseTo(sigma * Math.sqrt(STEPS_PER_DAY), org.assertj.core.data.Offset.offset(1e-12));
  }
}
