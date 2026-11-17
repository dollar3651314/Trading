package app.trading.payment.core;

import app.trading.payment.provider.CoboProvider;
import app.trading.payment.provider.FireblocksProvider;
import app.trading.payment.topup.TopUpProperties;
import app.trading.payment.topup.TopUpProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注册各家提供方：TopUP（配置齐了才算接入）；Cobo、Fireblocks 只占位（"未接入"）。 */
@Configuration
@EnableConfigurationProperties(TopUpProperties.class)
class PaymentConfig {

  @Bean
  ProviderRegistry providerRegistry(TopUpProperties topup) {
    ProviderRegistry r = new ProviderRegistry();
    r.register(new TopUpProvider(topup));
    r.register(new CoboProvider());
    r.register(new FireblocksProvider());
    return r;
  }
}
