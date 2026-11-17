package app.quote;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 报价参数（quote.yml 的 quote.market）。质量守卫的阈值沿用拆分前（功能地图 M-05、M-06）。
 *
 * @param source 报价模式：none（不启动报价源，测试里直接喂报价）、simulated（开发和测试环境：配了 token 的真实报价源接管它覆盖的品种，其余模拟）、
 *     live（生产：只用真实报价源）
 */
@ConfigurationProperties(prefix = "quote.market")
public record QuoteProperties(
    String source,
    Duration simulatedInterval,
    Duration staleAfter,
    Duration unchangedMaxAge,
    BigDecimal maxSpreadRate,
    BigDecimal maxTickJumpRate,
    BigDecimal maxVolatilityRate,
    Duration volatilityWindow) {}
