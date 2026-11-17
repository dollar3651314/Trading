package app.trading.payment.core;

import app.trading.common.health.AlertCheck;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 钱包提供方接口的告警（AC-W9）：当前提供方接口连续 1 分钟不通时健康检查的 alerts 出现 {@code wallet.topup} DOWN，告警邮件由通知模块发
 * （同一项 30 分钟一封）。只在当前提供方已经接入（配置齐了）时检查，测试环境没配置时不误报。
 * <p>每 30 秒查一次当前提供方的余额：顺便给提现自动审批 R12 用（Trading 在提供方的可用余额，判定时不访问网络，读这里查好的值）。
 */
@Component
class PaymentMonitor implements AlertCheck {

  private static final Logger log = LoggerFactory.getLogger(PaymentMonitor.class);
  static final Duration DOWN_AFTER = Duration.ofMinutes(1);

  private final PaymentService payments;
  private final ProviderRegistry registry;
  private final Clock clock;

  PaymentMonitor(PaymentService payments, ProviderRegistry registry, Clock clock, org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> metrics) {
    this.payments = payments;
    this.registry = registry;
    this.clock = clock;
    var m = metrics.getIfAvailable();
    if (m != null) {
      // 监控指标（内网管理端口 /actuator/prometheus）：当前提供方接口连续不通了多少秒（通 = 0）、可用余额
      io.micrometer.core.instrument.Gauge.builder("fx.payment.provider.failing", () -> payments.failingSince()
          .map(t -> (double) java.time.Duration.between(t, clock.instant()).toSeconds()).orElse(0.0)).baseUnit("seconds")
          .description("当前钱包提供方接口连续不通的秒数").tag("provider", "current").register(m);
      io.micrometer.core.instrument.Gauge.builder("fx.payment.provider.available", () -> {
        var b = payments.balance().available();
        return b == null ? Double.NaN : b.doubleValue();
      }).description("Trading 在当前钱包提供方的可用余额（USDT）").tag("provider", "current").register(m);
    }
  }

  @Override
  public List<Item> evaluate() {
    String code = payments.currentCode();
    var p = registry.get(code);
    if (p.isEmpty() || !p.get().connected()) return List.of();
    Instant since = payments.failingSince().orElse(null);
    boolean ok = since == null || Duration.between(since, clock.instant()).compareTo(DOWN_AFTER) < 0;
    String detail = ok ? "正常" : "从 " + since + " 起调用失败：" + registry.health(code).lastError;
    return List.of(new Item("wallet." + code.toLowerCase(), "钱包提供方 " + p.get().name() + " 接口不通", "Wallet provider " + p.get().name() + " unreachable", ok, detail));
  }

  @Component
  @ConditionalOnProperty(name = "trading.payment.monitor-enabled", havingValue = "true", matchIfMissing = true)
  static class Tick {

    private final PaymentMonitor monitor;

    Tick(PaymentMonitor monitor) {
      this.monitor = monitor;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    void run() {
      monitor.probe();
    }
  }

  /** 查一次余额（没接入时什么也不做） */
  void probe() {
    var p = registry.get(payments.currentCode());
    if (p.isEmpty() || !p.get().connected()) return;
    try {
      payments.refreshBalance();
    } catch (RuntimeException e) {
      log.warn("查钱包提供方余额失败：{}", e.getMessage());
    }
  }
}
