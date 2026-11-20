package app.trading.admin.ops;

import static org.assertj.core.api.Assertions.assertThat;

import app.trading.common.config.ConfigKey;
import app.trading.common.config.SystemConfig;
import app.trading.market.api.MarketAdminApi;
import app.trading.market.api.MarketApi;
import app.trading.support.FixedClockConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 生产第一次启动时的初始化（生产方案 v1 07 页 R-1、R-5）：只开加密货币、余额告警下限 5,000，各只做一次、写审计，之后以后台为准。
 * 测试里直接调用（测试环境没设 FX_INIT_*，启动时什么都没做）；改过的品种和配置在每个测试后恢复。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class ProductionInitTest {

  @Autowired ProductionInit init;
  @Autowired MarketAdminApi marketAdmin;
  @Autowired MarketApi market;
  @Autowired SystemConfig config;
  @Autowired JdbcTemplate jdbc;
  @Autowired app.trading.trading.api.TradingAdminApi trading;

  final List<String> reEnable = new ArrayList<>();

  void clearMarkers() {
    jdbc.update("DELETE FROM common_config WHERE config_key IN (?, ?)", ConfigKey.INIT_ENABLED_CATEGORIES.key, ConfigKey.INIT_BALANCE_MIN_USDT.key);
    jdbc.update("UPDATE common_config SET value = '0' WHERE config_key = ?", ConfigKey.PAYMENT_BALANCE_MIN_USDT.key);
    config.refresh();
  }

  @BeforeEach
  void before() {
    clearMarkers();
  }

  @AfterEach
  void after() {
    for (String code : reEnable) marketAdmin.setEnabled(code, true);
    clearMarkers();
  }

  long systemAudits(String action) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_log WHERE admin_name = 'system' AND action = ? AND high_risk = 1", Long.class, action);
  }

  @Test
  void 测试环境没设FX_INIT_启动时什么都没做() {
    assertThat(config.exists(ConfigKey.INIT_ENABLED_CATEGORIES)).isFalse();
    assertThat(init.enabledCategories("")).isFalse();
    assertThat(init.balanceMin("")).isFalse();
    assertThat(config.exists(ConfigKey.INIT_ENABLED_CATEGORIES)).isFalse();
    assertThat(config.exists(ConfigKey.INIT_BALANCE_MIN_USDT)).isFalse();
  }

  @Test
  void 只开加密货币_其余停用写审计_只做一次_之后以后台为准_R1() {
    List<String> nonCrypto = marketAdmin.symbols().stream().filter(s -> s.enabled() && !"crypto".equals(s.spec().category())).map(s -> s.spec().code()).toList();
    List<String> crypto = marketAdmin.symbols().stream().filter(s -> s.enabled() && "crypto".equals(s.spec().category())).map(s -> s.spec().code()).toList();
    assertThat(crypto).as("10 个币安品种").contains("BTCUSD", "ETHUSD", "SOLUSD", "XRPUSD", "BNBUSD", "DOGEUSD", "ADAUSD", "LTCUSD", "DOTUSD", "BCHUSD");
    assertThat(nonCrypto).as("外汇、贵金属、指数、能源、美股").contains("EURUSD", "XAUUSD", "NAS100", "XTIUSD", "AAPL");
    long audits = systemAudits("symbol.disable");

    assertThat(init.enabledCategories(" Crypto ")).isTrue();
    List<String> stillOn = marketAdmin.symbols().stream().filter(MarketAdminApi.Symbol::enabled).map(s -> s.spec().code()).toList();
    List<String> off = nonCrypto.stream().filter(c -> !stillOn.contains(c)).toList();
    reEnable.addAll(off);
    // 测试库里别的用例可能在某些品种上留了持仓或挂单：那些不停（生产第一次启动时没有）
    assertThat(stillOn).containsAll(crypto);
    assertThat(stillOn.stream().filter(nonCrypto::contains)).as("没停的非加密品种都是还有持仓或挂单的").allMatch(c -> trading.openUsage(c) > 0);
    assertThat(off).as("至少停了没人用的那些").isNotEmpty();
    assertThat(systemAudits("symbol.disable") - audits).isEqualTo(off.size());
    assertThat(systemAudits("init.enabledCategories")).isPositive();
    assertThat(market.symbol(off.get(0))).as("交易侧的品种表（客户端、官网、撮合）也没有了").isEmpty();
    assertThat(config.text(ConfigKey.INIT_ENABLED_CATEGORIES, "")).endsWith(" crypto");

    // Amos 之后在后台又开了一个：再次启动不覆盖
    marketAdmin.setEnabled(off.get(0), true);
    reEnable.remove(off.get(0));
    assertThat(init.enabledCategories("crypto")).as("只做一次").isFalse();
    assertThat(market.symbol(off.get(0))).isPresent();
  }

  @Test
  void 分类写错什么都不做_不记标记() {
    assertThat(ProductionInit.parseCategories("crypto, fx")).contains(Set.of("crypto", "fx"));
    assertThat(ProductionInit.parseCategories("crypto,forex")).isEmpty();
    assertThat(ProductionInit.parseCategories(" , ")).isEmpty();
    long before = marketAdmin.symbols().stream().filter(MarketAdminApi.Symbol::enabled).count();
    assertThat(init.enabledCategories("crypto,forex")).isFalse();
    assertThat(marketAdmin.symbols().stream().filter(MarketAdminApi.Symbol::enabled).count()).isEqualTo(before);
    assertThat(config.exists(ConfigKey.INIT_ENABLED_CATEGORIES)).isFalse();
  }

  @Test
  void 余额告警下限设成5000_写审计_只做一次_值不合法不做_R5() {
    assertThat(init.balanceMin("-1")).isFalse();
    assertThat(init.balanceMin("abc")).isFalse();
    assertThat(config.exists(ConfigKey.INIT_BALANCE_MIN_USDT)).isFalse();
    long audits = systemAudits("config.update");

    assertThat(init.balanceMin("5000")).isTrue();
    assertThat(config.text(ConfigKey.PAYMENT_BALANCE_MIN_USDT, "")).isEqualTo("5000");
    assertThat(systemAudits("config.update") - audits).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT updated_by FROM common_config WHERE config_key = ?", Long.class, ConfigKey.PAYMENT_BALANCE_MIN_USDT.key))
        .as("系统写的，没有管理员").isNull();

    // Amos 之后在后台改成 0（不告警）：再次启动不覆盖
    jdbc.update("UPDATE common_config SET value = '0' WHERE config_key = ?", ConfigKey.PAYMENT_BALANCE_MIN_USDT.key);
    config.refresh();
    assertThat(init.balanceMin("5000")).as("只做一次").isFalse();
    assertThat(config.text(ConfigKey.PAYMENT_BALANCE_MIN_USDT, "")).isEqualTo("0");
  }
}
