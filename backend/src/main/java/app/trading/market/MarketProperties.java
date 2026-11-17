package app.trading.market;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 行情参数（application.yml 的 trading.market）。第 2 批拆进程后，报价源、主备切换、质量守卫在 quote-app，这里只剩交易侧的。
 *
 * @param source 报价从哪来：none（默认，不连报价服务；后端测试里直接喂报价）、remote（连 quote-app 的内部 WebSocket，见 {@code quote-app}）
 * @param staleAfter 报价时间（报价源给的时间）距今超过这个就不能成交（和报价服务的质量守卫同一个阈值）
 * @param maxQuoteAge 一个品种超过这个时间没收到新报价就不能成交、不能开仓（第 2 批新加的过期保护，默认 3 秒，技术方案 02 页第 2 节）
 * @param pushInterval 给客户端推送报价的间隔
 * @param fixedUsdRates 没有对应外汇品种的币种用的固定汇率（例如港币，联系汇率）
 */
@ConfigurationProperties(prefix = "trading.market")
public record MarketProperties(String source, Duration staleAfter, Duration maxQuoteAge, Duration pushInterval, Map<String, BigDecimal> fixedUsdRates) {

  public MarketProperties {
    staleAfter = staleAfter == null ? Duration.ofSeconds(5) : staleAfter;
    maxQuoteAge = maxQuoteAge == null ? Duration.ofSeconds(3) : maxQuoteAge;
    pushInterval = pushInterval == null ? Duration.ofMillis(250) : pushInterval;
    fixedUsdRates = fixedUsdRates == null ? Map.of() : fixedUsdRates;
  }

  /** 连着报价服务（source=remote） */
  public boolean remote() {
    return "remote".equalsIgnoreCase(source == null ? "" : source.strip());
  }
}
