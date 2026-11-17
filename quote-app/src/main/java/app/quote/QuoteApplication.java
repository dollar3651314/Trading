package app.quote;

import java.time.Clock;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 报价进程入口（第 2 批：报价拆进程）。和 trading-app 分开启停：交易停了报价照常；报价停了交易按"暂无报价"拒新开仓（需求 03 页第一节）。
 * 配置文件是 {@code quote.yml}（{@link #CONFIG_NAME}），不读 application.yml。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class QuoteApplication {

  /** 配置文件名：quote.yml、quote-{profile}.yml */
  public static final String CONFIG_NAME = "spring.config.name=quote";

  public static void main(String[] args) {
    builder().run(args);
  }

  /** 正式启动和测试里启动都用它（固定配置文件名） */
  public static SpringApplicationBuilder builder() {
    return new SpringApplicationBuilder(QuoteApplication.class).properties(CONFIG_NAME);
  }

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock clock() {
    return Clock.systemUTC();
  }
}
