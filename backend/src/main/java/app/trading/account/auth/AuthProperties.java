package app.trading.account.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 登录相关的参数（application.yml 的 trading.auth）。默认值沿用现有系统（功能地图 A-02）：
 * 连续失败 5 次锁定 15 分钟；现有系统只按 IP 计数，这里按账号和 IP 同时计数（需求 4.1-2、AC-02）。
 *
 * @param codeDailyLimit 同一邮箱同一用途 24 小时内最多发几次验证码（安全审查 SEC-03：防止反复换新验证码猜重置密码的验证码）
 * @param codeIpHourlyLimit 同一 IP 每小时最多请求几次发验证码（安全审查 SEC-03：防止用一个 IP 给大量邮箱发信、探测哪些邮箱注册过）
 * @param phase1TelegramOnly 第一阶段只走 Telegram（TB-33；默认关，Amos 统一验收时打开）：关闭注册、邮箱登录、忘记密码（发验证码也关），客户只能从机器人一键登录
 * @param botSessionIdleMinutes 机器人会话（从机器人一键登录）闲置多久过期，默认 30 分钟
 * @param botSessionMaxHours 机器人会话最长多久，默认 12 小时
 */
@ConfigurationProperties(prefix = "trading.auth")
record AuthProperties(
    int loginFailureLimit,
    int lockMinutes,
    int sessionTtlHours,
    int codeTtlMinutes,
    int codeResendSeconds,
    int codeMaxAttempts,
    String cookieName,
    boolean cookieSecure,
    int codeDailyLimit,
    int codeIpHourlyLimit,
    @DefaultValue("false") boolean phase1TelegramOnly,
    @DefaultValue("30") int botSessionIdleMinutes,
    @DefaultValue("12") int botSessionMaxHours) {}
