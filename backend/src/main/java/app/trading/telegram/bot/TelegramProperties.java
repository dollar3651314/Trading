package app.trading.telegram.bot;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 机器人参数（application.yml 的 trading.telegram）。
 *
 * @param token 机器人令牌（FX_TG_BOT_TOKEN，只由 Amos 填进服务器）；为空 = 机器人不启动
 * @param username 机器人用户名（FX_TG_BOT_USERNAME，不带 @）；启动时用 getMe 核对令牌属于它
 * @param apiUrl Telegram 接口地址（测试里换成本机的假 Telegram）
 * @param webUrl 网页客户端地址（login_url 按钮用；批 2 之前不填 = 不显示按钮）
 */
@ConfigurationProperties("trading.telegram")
public record TelegramProperties(String token, String username, @DefaultValue("https://api.telegram.org") String apiUrl, String webUrl,
    @DefaultValue("50s") Duration pollTimeout, @DefaultValue("1s") Duration perChatInterval, @DefaultValue("25") int globalPerSecond,
    @DefaultValue("5000") int queueCapacity, @DefaultValue("10m") Duration unhealthyAfter, @DefaultValue("20") int userPerMinute) {

  public boolean configured() {
    return token != null && !token.isBlank();
  }

  boolean hasWebUrl() {
    return webUrl != null && !webUrl.isBlank();
  }

  /** 用户名去掉开头的 @ */
  String bareUsername() {
    if (username == null) return "";
    String u = username.strip();
    return u.startsWith("@") ? u.substring(1) : u;
  }

  /** 把字符串里的令牌换成 ***（异常信息、日志都先过一遍） */
  String scrub(String s) {
    if (s == null || !configured()) return s;
    return s.replace(token, "***");
  }

  /** 不打印令牌 */
  @Override
  public String toString() {
    return "TelegramProperties[username=" + username + ", apiUrl=" + apiUrl + ", webUrl=" + webUrl + ", token=" + (configured() ? "(已设置)" : "(空)") + "]";
  }
}
