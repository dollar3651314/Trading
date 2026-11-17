package app.trading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Trading 后端入口：单体应用，单实例运行（内部文档 3.3 ⑦）。 */
@SpringBootApplication
public class TradingApplication {

  public static void main(String[] args) {
    SpringApplication.run(TradingApplication.class, args);
  }
}
