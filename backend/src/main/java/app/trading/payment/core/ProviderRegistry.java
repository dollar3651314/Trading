package app.trading.payment.core;

import app.trading.payment.provider.PaymentProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 所有提供方（按代码），以及每家接口最近一次成功、失败的时间（健康检查、后台钱包提供方页用）。
 * 测试和端到端替身可以注册自己的提供方（同一个代码会替换掉原来的）。
 */
public class ProviderRegistry {

  /** 一家提供方的接口状态 */
  public static final class Health {
    volatile java.time.Instant lastOk;
    volatile java.time.Instant failingSince;
    volatile String lastError;
  }

  private final Map<String, PaymentProvider> providers = new ConcurrentHashMap<>();
  private final List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
  private final Map<String, Health> health = new ConcurrentHashMap<>();

  public void register(PaymentProvider p) {
    if (providers.put(p.code(), p) == null) order.add(p.code());
    health.putIfAbsent(p.code(), new Health());
  }

  public void remove(String code) {
    providers.remove(code);
    order.remove(code);
  }

  public Optional<PaymentProvider> get(String code) {
    return Optional.ofNullable(code == null ? null : providers.get(code));
  }

  public List<PaymentProvider> all() {
    List<PaymentProvider> out = new ArrayList<>();
    for (String c : order) if (providers.containsKey(c)) out.add(providers.get(c));
    return out;
  }

  Health health(String code) {
    return health.computeIfAbsent(code, c -> new Health());
  }

  void ok(String code, java.time.Instant at) {
    Health h = health(code);
    h.lastOk = at;
    h.failingSince = null;
    h.lastError = null;
  }

  void failed(String code, java.time.Instant at, String error) {
    Health h = health(code);
    if (h.failingSince == null) h.failingSince = at;
    h.lastError = error;
  }
}
