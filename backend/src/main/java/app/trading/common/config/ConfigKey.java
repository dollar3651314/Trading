package app.trading.common.config;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

/**
 * 后台可以改的系统配置（后台方案 G7）。键名和单位、分组和后台原型 {@code web/admin} 的系统配置页一致，另加 3 项提现手续费。
 * 初始值在迁移脚本 V17 里，和 application.yml 的默认值一致。
 */
public enum ConfigKey {
  WITHDRAW_MIN_AMOUNT("withdraw.minAmount", Unit.USDT, "withdraw", BigDecimal.ZERO),
  /** 单笔上限最多 5,000（TQ-13 = TD-15 A：TopUP 单笔只能付 5,000，超了会一直卡在 TopUP 待审；以后要放大走 TopUP 按商户设上限 TG-13） */
  WITHDRAW_MAX_SINGLE("withdraw.maxSingle", Unit.USDT, "withdraw", BigDecimal.ONE, true, BigDecimal.valueOf(5000)),
  WITHDRAW_DAILY_LIMIT("withdraw.dailyLimit", Unit.USDT, "withdraw", BigDecimal.ONE),
  WITHDRAW_COOLING_HOURS("withdraw.coolingHours", Unit.HOURS, "withdraw", BigDecimal.ZERO),
  WITHDRAW_LARGE_THRESHOLD("withdraw.largeThreshold", Unit.USDT, "withdraw", BigDecimal.ONE),
  WITHDRAW_LARGE_DELAY_HOURS("withdraw.largeDelayHours", Unit.HOURS, "withdraw", BigDecimal.ZERO),
  WITHDRAW_FEE_ERC20("withdraw.fee.ERC20", Unit.USDT, "withdraw", BigDecimal.ZERO),
  WITHDRAW_FEE_TRC20("withdraw.fee.TRC20", Unit.USDT, "withdraw", BigDecimal.ZERO),
  WITHDRAW_FEE_BSC("withdraw.fee.BSC", Unit.USDT, "withdraw", BigDecimal.ZERO),
  WHITELIST_EFFECTIVE_HOURS("whitelist.effectiveHours", Unit.HOURS, "withdraw", BigDecimal.ZERO),
  WHITELIST_MAX_ADDRESSES("whitelist.maxAddresses", Unit.COUNT, "withdraw", BigDecimal.ONE),
  /** 充值确认数只能调高（后台方案 G7）：最小值就是现在的值 */
  DEPOSIT_CONFIRMATIONS_ERC20("deposit.confirmations.ERC20", Unit.COUNT, "deposit", BigDecimal.valueOf(12)),
  DEPOSIT_CONFIRMATIONS_TRC20("deposit.confirmations.TRC20", Unit.COUNT, "deposit", BigDecimal.valueOf(19)),
  DEPOSIT_CONFIRMATIONS_BSC("deposit.confirmations.BSC", Unit.COUNT, "deposit", BigDecimal.valueOf(15)),
  /** 后台管理员的登录锁定（后台方案 G2）；客户的登录锁定沿用现有系统，在 application.yml */
  LOGIN_LOCK_THRESHOLD("login.lockThreshold", Unit.COUNT, "security", BigDecimal.ONE),
  LOGIN_LOCK_MINUTES("login.lockMinutes", Unit.MINUTES, "security", BigDecimal.ONE),
  /** 后台 IP 白名单：逗号分隔的 IP 或网段，为空不限制（后台方案 G3） */
  ADMIN_IP_WHITELIST("admin.ipWhitelist", Unit.TEXT, "security", null),
  ADJUST_SINGLE_LIMIT("adjust.singleLimit", Unit.USDT, "adjust", BigDecimal.ZERO),
  ADJUST_DAILY_LIMIT("adjust.dailyLimit", Unit.USDT, "adjust", BigDecimal.ZERO),
  NOTIFY_MARGIN_CALL_THROTTLE_MINUTES("notify.marginCallThrottleMinutes", Unit.MINUTES, "notify", BigDecimal.ONE),
  /** 单个客户的持仓上限（USD 名义价值，含挂单）：在风控页改（权限 risk:user_limit），不在系统配置页 */
  RISK_PER_USER_LIMIT("risk.perUserLimitUsd", Unit.USDT, "risk", BigDecimal.ONE, false),
  /** 追保线（保证金水平 %，100 即 100%）：在交易参数页改（权限 params:edit），不在系统配置页 */
  TRADING_MARGIN_CALL_LEVEL("trading.marginCallLevel", Unit.PERCENT, "trading", BigDecimal.ONE, false),
  /**
   * 提现自动审批（v2，内部文档 第 7 节）：总开关（<b>默认 false</b>，迁移 V26）、13 条规则的开关和 10 个数值、配置版本号（R13 的两个键在迁移 V27）。
   * 在"出金审核 → 自动审批规则"页一次保存（权限 withdrawal:auto_rule_edit，高危），不在系统配置页；取值范围和 R1 ≤ R2 ≤ R3 的校验在 wallet.api.AutoApprove
   */
  AUTO_APPROVE_ENABLED("withdraw.autoApprove.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_VERSION("withdraw.autoApprove.version", Unit.COUNT, "autoApprove", BigDecimal.ZERO, false),
  AUTO_APPROVE_R1_ENABLED("withdraw.autoApprove.R1.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R2_ENABLED("withdraw.autoApprove.R2.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R3_ENABLED("withdraw.autoApprove.R3.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R4_ENABLED("withdraw.autoApprove.R4.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R5_ENABLED("withdraw.autoApprove.R5.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R6_ENABLED("withdraw.autoApprove.R6.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R7_ENABLED("withdraw.autoApprove.R7.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R8_ENABLED("withdraw.autoApprove.R8.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R9_ENABLED("withdraw.autoApprove.R9.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R10_ENABLED("withdraw.autoApprove.R10.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R11_ENABLED("withdraw.autoApprove.R11.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R12_ENABLED("withdraw.autoApprove.R12.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R13_ENABLED("withdraw.autoApprove.R13.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R1_MAX_AMOUNT("withdraw.autoApprove.R1.maxAmount", Unit.USDT, "autoApprove", BigDecimal.ZERO, false),
  AUTO_APPROVE_R2_MAX_USER24H("withdraw.autoApprove.R2.maxUser24h", Unit.USDT, "autoApprove", BigDecimal.ZERO, false),
  AUTO_APPROVE_R3_MAX_PLATFORM24H("withdraw.autoApprove.R3.maxPlatform24h", Unit.USDT, "autoApprove", BigDecimal.ZERO, false),
  AUTO_APPROVE_R6_HOURS("withdraw.autoApprove.R6.hours", Unit.HOURS, "autoApprove", BigDecimal.ZERO, false),
  AUTO_APPROVE_R7_DAYS("withdraw.autoApprove.R7.days", Unit.COUNT, "autoApprove", BigDecimal.ZERO, false),
  AUTO_APPROVE_R8_MULTIPLE("withdraw.autoApprove.R8.multiple", Unit.MULTIPLE, "autoApprove", BigDecimal.ZERO, false),
  AUTO_APPROVE_R10_MAX_PROFIT7D("withdraw.autoApprove.R10.maxProfit7d", Unit.USDT, "autoApprove", BigDecimal.ZERO, false),
  AUTO_APPROVE_R10_MAX_PROFIT_PCT("withdraw.autoApprove.R10.maxProfitPct", Unit.PERCENT, "autoApprove", BigDecimal.ZERO, false),
  AUTO_APPROVE_R11_MAX_COUNT("withdraw.autoApprove.R11.maxCount", Unit.COUNT, "autoApprove", BigDecimal.ZERO, false),
  /** R13 盈利客户的门槛（累计净盈利 &gt; 它就转人工），默认 0，可以是 0 */
  AUTO_APPROVE_R13_MAX_NET_PROFIT("withdraw.autoApprove.R13.maxNetProfit", Unit.USDT, "autoApprove", BigDecimal.ZERO, false),
  /**
   * 钱包提供方可用余额告警下限（USDT，生产方案 v1 07 页 R-5）：Trading 在当前钱包提供方（TopUP）的可用余额低于它就给告警邮箱发邮件，
   * 恢复时再发一封（通知模块的 AlertMonitor）；<b>0 = 不告警</b>（迁移 V34 的初始值）。生产第一次启动时设成 5,000（FX_INIT_BALANCE_MIN_USDT，Amos 11/19），
   * 之后以后台为准。在系统配置页改（权限 config:edit，高危，写审计）
   */
  PAYMENT_BALANCE_MIN_USDT("payment.balance.minUsdt", Unit.USDT, "payment", BigDecimal.ZERO),
  /**
   * 暂停把提现交给钱包提供方（生产方案 v1 05 页第 3 节、07 页 R-4）：{@code true} 时审核通过的提现停在"已审核待放款"、不交 TopUP（不退款），
   * 健康检查 alerts 出现 {@code payout.paused} DOWN。服务器上 {@code sudo trading restore … --confirm --production} 恢复数据后自动写 true
   * （备份之后已经交给 TopUP 的提现会变回"已审核待放款"，再交一次就重复付款），人工在 TopUP 后台核对后 {@code sudo trading start-payout --confirm} 写回 false。
   * 没有这一行 = 不暂停。不在系统配置页（只由服务器命令改）
   */
  PAYMENT_PAYOUT_PAUSED("payment.payout.paused", Unit.BOOL, "payment", null, false),
  /**
   * 只做一次的初始化标记（生产方案 v1 07 页 R-1、R-5，admin.ops.ProductionInit）：值是做的时间和用的设置；有这一行就不再做，
   * 以后台的改动为准（不在每次启动时覆盖）。不在系统配置页
   */
  INIT_ENABLED_CATEGORIES("init.enabledCategories", Unit.TEXT, "init", null, false),
  INIT_BALANCE_MIN_USDT("init.balanceMinUsdt", Unit.TEXT, "init", null, false),
  /** Telegram 机器人总开关（TG 机器人第 1 批，迁移 V35，默认 true）：false 时机器人只回"暂停服务"，Telegram 提醒也不发。没配令牌时机器人本来就不启动 */
  TELEGRAM_ENABLED("telegram.enabled", Unit.BOOL, "telegram", null),
  /**
   * 允许在机器人里授权开户的 Telegram 用户编号（逗号分隔）；空 = 谁都不能开户，{@code *} = 不限（TB-13：先只给 Amos 用，迁移 V35 默认空）。
   * 已经开过户的不受影响
   */
  TELEGRAM_SIGNUP_ALLOW_IDS("telegram.signup.allowIds", Unit.TEXT, "telegram", null),
  /**
   * TG 客户能不能加白名单、提现（TB-1"TG 提现默认关"，第 2 批审查 🔴B2-1，迁移 V37 默认 false）：false 时有 TG 账户的客户（不管从哪种会话进来）
   * 加白名单、提交提现一律拒绝。第 4 批的提现密码、额度、人工审核等保护做完、验收后由 Amos 在后台打开
   */
  TELEGRAM_WITHDRAW_ENABLED("telegram.withdraw.enabled", Unit.BOOL, "telegram", null),
  /**
   * TG 客户每天提现额度（USDT，网页和机器人合并算，在锁账户的同一个事务里检查；TB-6、TB-22，迁移 V38）：没邮箱 500、有邮箱 2,000。
   * 上限 2,000（TB-37：每天额度设上限）
   */
  TELEGRAM_WITHDRAW_DAILY_LIMIT("telegram.withdraw.dailyLimit", Unit.USDT, "telegram", BigDecimal.ZERO, true, BigDecimal.valueOf(2000)),
  TELEGRAM_WITHDRAW_DAILY_LIMIT_EMAIL("telegram.withdraw.dailyLimitEmail", Unit.USDT, "telegram", BigDecimal.ZERO, true, BigDecimal.valueOf(2000)),
  /** 第 4 批 4c（TB-27）：网页"申请冻结提现"全平台 1 小时内成功冻结超过这个数就发告警（迁移 V40 默认 10） */
  WITHDRAW_FREEZE_PLATFORM_ALERT_PER_HOUR("withdraw.freeze.platformAlertPerHour", Unit.COUNT, "withdraw", BigDecimal.ONE);

  /**
   * 第 4 批（冻结、找回流程）上线前不能对所有人开放开户：后台保存 {@code telegram.signup.allowIds = *} 时拒绝（第 2 批审查 🟡2）。
   * 第 4 批 4c 起是 true：冻结入口、找回和换绑都有了，可以对所有人开放（开不开仍由 Amos 在后台决定）
   */
  public static final boolean SIGNUP_ALL_ALLOWED = true;

  /** 单位，和原型一致 */
  public enum Unit {
    USDT, HOURS, MINUTES, COUNT, TEXT, PERCENT,
    /** 开关：true / false（提现自动审批） */
    BOOL,
    /** 倍数，最多 8 位小数（提现自动审批 R8） */
    MULTIPLE;

    public String api() {
      return name().toLowerCase();
    }
  }

  public final String key;
  public final Unit unit;
  public final String group;
  /** 数值的最小值（含）；文本为空 */
  public final BigDecimal min;
  /** 数值的最大值（含）；为空表示只按单位的通用上限 */
  public final BigDecimal max;
  /**
   * 是否在系统配置页显示和修改。有专门页面和权限点的（风控、交易参数）不在系统配置页，
   * 否则有 config:edit 的人可以绕过 risk:user_limit、params:edit 去改
   */
  public final boolean configPage;

  ConfigKey(String key, Unit unit, String group, BigDecimal min) {
    this(key, unit, group, min, true);
  }

  ConfigKey(String key, Unit unit, String group, BigDecimal min, boolean configPage) {
    this(key, unit, group, min, configPage, null);
  }

  ConfigKey(String key, Unit unit, String group, BigDecimal min, boolean configPage, BigDecimal max) {
    this.key = key;
    this.unit = unit;
    this.group = group;
    this.min = min;
    this.configPage = configPage;
    this.max = max;
  }

  public static Optional<ConfigKey> of(String key) {
    return Arrays.stream(values()).filter(k -> k.key.equals(key)).findFirst();
  }

  /** 校验并规范化新值；不合法返回空。金额最多 8 位小数，小时、分钟、个数是整数 */
  public Optional<String> normalize(String value) {
    if (value == null) return Optional.empty();
    String v = value.strip();
    if (this == TELEGRAM_SIGNUP_ALLOW_IDS) {
      // 空、* 或逗号分隔的 Telegram 用户编号（纯数字），存成去掉空格的样子
      String n = v.replaceAll("\\s+", "");
      return n.length() <= 1000 && n.matches("|\\*|\\d{1,19}(,\\d{1,19})*") ? Optional.of(n) : Optional.empty();
    }
    if (unit == Unit.TEXT) return v.length() <= 1000 ? Optional.of(v) : Optional.empty();
    if (unit == Unit.BOOL) return "true".equals(v) || "false".equals(v) ? Optional.of(v) : Optional.empty();
    try {
      BigDecimal d = new BigDecimal(v);
      if (d.compareTo(min) < 0) return Optional.empty();
      if (max != null && d.compareTo(max) > 0) return Optional.empty();
      if (unit == Unit.USDT || unit == Unit.PERCENT || unit == Unit.MULTIPLE) {
        if (d.scale() > 8 || d.compareTo(new BigDecimal("1000000000")) > 0) return Optional.empty();
        return Optional.of(d.stripTrailingZeros().toPlainString());
      }
      if (d.stripTrailingZeros().scale() > 0 || d.compareTo(BigDecimal.valueOf(100_000)) > 0) return Optional.empty();
      return Optional.of(String.valueOf(d.intValueExact()));
    } catch (NumberFormatException | ArithmeticException e) {
      return Optional.empty();
    }
  }
}
