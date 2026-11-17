package app.trading.market.quote;

import app.trading.market.MarketProperties;
import app.trading.market.config.SymbolCatalog;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** {@code trading.market.source=remote} 时连报价服务（{@link RemoteQuotes}）；none（后端测试）时什么都不建。 */
@Configuration
@EnableConfigurationProperties(QuoteAppProperties.class)
class RemoteQuotesConfig {

  @Bean
  @ConditionalOnProperty(prefix = "trading.market", name = "source", havingValue = "remote")
  RemoteQuotes remoteQuotes(QuoteProcessor processor, SymbolCatalog catalog, QuoteAppProperties props, MarketProperties market, ObjectMapper json, Clock clock) {
    return new RemoteQuotes(processor, catalog, props, market, json, clock);
  }
}
