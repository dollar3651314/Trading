package app.trading.admin.ops;

import app.trading.admin.security.Audit;
import app.trading.common.config.ConfigKey;
import app.trading.common.config.SystemConfig;
import app.trading.market.api.MarketAdminApi;
import app.trading.trading.api.TradingAdminApi;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 生产第一次启动时的初始化（生产方案 v1 07 页 R-1、R-5）。两项各<b>只做一次</b>：做完在系统配置里记一个标记（{@link ConfigKey#INIT_ENABLED_CATEGORIES}、
 * {@link ConfigKey#INIT_BALANCE_MIN_USDT}），以后启动不再做，以后台的改动为准（不覆盖 Amos 在后台改过的）。每一处改动写审计（操作人 {@value Audit#SYSTEM}）。
 * 两个设置都只在生产的 docker-compose 里写；测试环境、开发、CI 不设，什么都不做。
 * <ul>
 *   <li>{@code FX_INIT_ENABLED_CATEGORIES}（生产写 {@code crypto}，Amos 11/19「只开加密」）：只保留这些分类的品种，其余已启用的停用
 *       （外汇、贵金属、指数、能源、美股 40 个），和后台"停用品种"一样；还有持仓或挂单的品种不停（生产第一次启动时没有），记日志和审计。
 *       写错的分类（不在 fx / metal / index / energy / crypto / stock 里）什么都不做、不记标记，记错误日志。</li>
 *   <li>{@code FX_INIT_BALANCE_MIN_USDT}（生产写 {@code 5000}，Amos 11/19「余额告警按建议」）：把系统配置
 *       {@code payment.balance.minUsdt}（钱包提供方可用余额告警下限）设成这个值。值不合法什么都不做、不记标记。</li>
 * </ul>
 * 出错（例如数据库不通）只记日志、不记标记、不影响启动：下次启动再做。
 */
@Component
class ProductionInit implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(ProductionInit.class);
  /** 品种分类（V4__market.sql 的 category） */
  static final Set<String> CATEGORIES = Set.of("fx", "metal", "index", "energy", "crypto", "stock");

  private final MarketAdminApi market;
  private final TradingAdminApi trading;
  private final SystemConfig config;
  private final Audit audit;
  private final Clock clock;
  private final String categories;
  private final String balanceMin;

  ProductionInit(MarketAdminApi market, TradingAdminApi trading, SystemConfig config, Audit audit, Clock clock,
      @Value("${trading.init.enabled-categories:}") String categories, @Value("${trading.init.balance-min-usdt:}") String balanceMin) {
    this.market = market;
    this.trading = trading;
    this.config = config;
    this.audit = audit;
    this.clock = clock;
    this.categories = categories == null ? "" : categories.strip();
    this.balanceMin = balanceMin == null ? "" : balanceMin.strip();
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      enabledCategories(categories);
    } catch (RuntimeException e) {
      log.error("生产初始化：按分类停用品种没做完，下次启动再做", e);
    }
    try {
      balanceMin(balanceMin);
    } catch (RuntimeException e) {
      log.error("生产初始化：余额告警下限没设上，下次启动再做", e);
    }
  }

  /** 解析分类设置（逗号分隔，不分大小写）；有不认识的返回空 */
  static Optional<Set<String>> parseCategories(String v) {
    Set<String> out = new LinkedHashSet<>();
    for (String c : v.split(",")) {
      String x = c.strip().toLowerCase(Locale.ROOT);
      if (x.isEmpty()) continue;
      if (!CATEGORIES.contains(x)) return Optional.empty();
      out.add(x);
    }
    return out.isEmpty() ? Optional.empty() : Optional.of(out);
  }

  /** 只保留这些分类的品种。做了返回 true（没设、已经做过、设置不合法返回 false） */
  boolean enabledCategories(String v) {
    if (v.isEmpty() || config.exists(ConfigKey.INIT_ENABLED_CATEGORIES)) return false;
    Optional<Set<String>> keep = parseCategories(v);
    if (keep.isEmpty()) {
      log.error("FX_INIT_ENABLED_CATEGORIES={} 里有不认识的分类（只认 {}），没有停用任何品种", v, CATEGORIES);
      return false;
    }
    String why = "生产初始化：只开 " + String.join("、", keep.get()) + " 分类的品种（FX_INIT_ENABLED_CATEGORIES，只做一次）";
    List<String> disabled = new ArrayList<>();
    List<String> inUse = new ArrayList<>();
    for (MarketAdminApi.Symbol s : market.symbols()) {
      if (!s.enabled() || keep.get().contains(s.spec().category())) continue;
      String code = s.spec().code();
      if (trading.openUsage(code) > 0) {
        inUse.add(code);
        continue;
      }
      market.setEnabled(code, false);
      audit.recordSystem("symbol.disable", code, why, Map.of("enabled", true), Map.of("enabled", false));
      disabled.add(code);
    }
    if (!inUse.isEmpty()) log.warn("生产初始化：这些品种还有持仓或挂单，没有停用（要停在后台停）：{}", inUse);
    LocalDateTime now = LocalDateTime.now(clock);
    config.setBySystem(ConfigKey.INIT_ENABLED_CATEGORIES, now + " " + String.join(",", keep.get()), now);
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("keep", List.copyOf(keep.get()));
    after.put("disabled", disabled.size());
    after.put("inUse", inUse);
    audit.recordSystem("init.enabledCategories", String.join(",", keep.get()), why, null, after);
    log.warn("生产初始化：只开 {} 分类的品种，停用了 {} 个：{}", keep.get(), disabled.size(), disabled);
    return true;
  }

  /** 设余额告警下限。做了返回 true */
  boolean balanceMin(String v) {
    if (v.isEmpty() || config.exists(ConfigKey.INIT_BALANCE_MIN_USDT)) return false;
    Optional<String> value = ConfigKey.PAYMENT_BALANCE_MIN_USDT.normalize(v);
    if (value.isEmpty()) {
      log.error("FX_INIT_BALANCE_MIN_USDT 不是合法的金额（≥ 0，最多 8 位小数），没有设余额告警下限");
      return false;
    }
    LocalDateTime now = LocalDateTime.now(clock);
    String before = config.setBySystem(ConfigKey.PAYMENT_BALANCE_MIN_USDT, value.get(), now);
    config.setBySystem(ConfigKey.INIT_BALANCE_MIN_USDT, now + " " + value.get(), now);
    String key = ConfigKey.PAYMENT_BALANCE_MIN_USDT.key;
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("value", before);
    audit.recordSystem("config.update", key, "生产初始化：钱包提供方可用余额告警下限（FX_INIT_BALANCE_MIN_USDT，只做一次）", b, Map.of("value", value.get()));
    log.warn("生产初始化：{} 设成 {} USDT", key, value.get());
    return true;
  }
}
