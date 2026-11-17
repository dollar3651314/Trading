package app.trading.support;

import app.quote.QuoteApplication;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 跨进程测试：在测试进程里另起一个<b>真实的报价服务</b>（quote-app，独立的 Spring 上下文、随机端口、自己的库），
 * trading-app 照常用 {@code trading.market.source=remote} 通过内部 WebSocket 连它。只在测试里用 quote-app 的类（正式代码不能 import，ModuleBoundaryTest 检查）。
 * 配置是 quote-app 的 quote.yml + quote-test.yml（库 FX_QUOTE_DB_URL，默认 trading_quote_test，内部 Key 是测试用的假值）。
 */
public final class QuoteAppHarness implements AutoCloseable {

  /** quote-test.yml 里的测试用内部 Key（明显不是密钥） */
  public static final String KEY = "ci_only_fake_internal_key";

  private final ConfigurableApplicationContext ctx;

  private QuoteAppHarness(ConfigurableApplicationContext ctx) {
    this.ctx = ctx;
  }

  /**
   * 启动报价服务。
   *
   * @param clock 报价服务用的时钟（和 trading-app 测试的固定时钟同一时刻，报价时间才不会被质量守卫判成过期）
   * @param props 额外配置，例如 quote.market.source=simulated、quote.feeds.alltick.token
   */
  public static QuoteAppHarness start(Clock clock, Map<String, Object> props) {
    Map<String, Object> all = new LinkedHashMap<>();
    all.put("server.port", "0");
    all.put("quote.stream.heartbeat", "100ms");
    all.putAll(props);
    // 用命令行参数传（优先级高于 quote-test.yml；builder.properties 是最低优先级的默认值，会被配置文件盖掉）
    String[] args = all.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
    ConfigurableApplicationContext ctx = QuoteApplication.builder().profiles("test")
        .initializers(c -> c.getBeanFactory().registerSingleton("clock", clock)).run(args);
    return new QuoteAppHarness(ctx);
  }

  public String url() {
    return "http://127.0.0.1:" + ctx.getEnvironment().getProperty("local.server.port");
  }

  public <T> T bean(Class<T> type) {
    return ctx.getBean(type);
  }

  @Override
  public void close() {
    ctx.close();
  }
}
