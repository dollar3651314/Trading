package app.quote.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * quote-app 的 Spring 集成测试：配置文件是 quote.yml + quote-test.yml（spring.config.name=quote），真实 MySQL（独立的库，见 quote-test.yml），随机端口。
 * 额外的配置用 {@code @TestPropertySource(properties = ...)}。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.config.name=quote")
@ActiveProfiles("test")
public @interface QuoteAppTest {}
