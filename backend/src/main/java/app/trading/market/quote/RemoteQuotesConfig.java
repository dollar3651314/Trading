package app.trading.market.quote;

import app.trading.market.MarketProperties;
import app.trading.market.config.SymbolCatalog;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** {@code trading.market.source=remote} 时连报价服务（{@link RemoteQuotes}，也提供 K 线）；none（后端测试）时不连，K 线为空。 */
@Configuration
@EnableConfigurationProperties(QuoteAppProperties.class)
class RemoteQuotesConfig {

  @Bean
  @ConditionalOnProperty(prefix = "trading.market", name = "source", havingValue = "remote")
  RemoteQuotes remoteQuotes(QuoteProcessor processor, SymbolCatalog catalog, QuoteAppProperties props, MarketProperties market, ObjectMapper json, Clock clock) {
    return new RemoteQuotes(processor, catalog, props, market, json, clock);
  }

  /** K 线：连着报价服务时由它提供，否则没有 */
  @Bean
  app.trading.market.kline.Klines klines(org.springframework.beans.factory.ObjectProvider<RemoteQuotes> remote) {
    RemoteQuotes r = remote.getIfAvailable();
    if (r == null) return app.trading.market.kline.Klines.NONE;
    return new app.trading.market.kline.Klines() {
      @Override
      public java.util.List<app.quote.protocol.Message.Bar> history(String symbol, app.quote.protocol.Timeframe tf, int limit) {
        return r.history(symbol, tf, limit);
      }

      @Override
      public java.util.Map<String, java.util.List<java.math.BigDecimal>> closes(java.util.List<String> symbols, app.quote.protocol.Timeframe tf, int limit) {
        return r.closes(symbols, tf, limit);
      }

      @Override
      public void onUpdate(java.util.function.Consumer<app.quote.protocol.Message.Bar> listener) {
        r.onUpdate(listener);
      }

      @Override
      public void watch(java.util.Set<String> keys) {
        r.watch(keys);
      }
    };
  }
}
