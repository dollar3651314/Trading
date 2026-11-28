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
 * @param botSessionDays 机器人会话（从机器人进网页）的滑动有效期，默认 7 天：每次用网页都续到"现在 + 这么多天"（Amos 11/28 定；以前是闲置 30 分钟、最长 12 小时）
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
    @DefaultValue("7") int botSessionDays) {

  /** 机器人会话有效期（至少 1 天，最多 30 天） */
  java.time.Duration botSessionTtl() {
    return java.time.Duration.ofDays(Math.max(1, Math.min(30, botSessionDays)));
  }
}
