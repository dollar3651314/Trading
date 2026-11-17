package app.quote.license;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 报价源授权检查（quote.yml 的 quote.license）。
 *
 * @param enforce 是否检查授权（开仓看"交易用途"、外部 Key 看"转售"）：只有 {@code true} 才检查；false（默认）、auto（旧写法）和其他值一律不检查。
 *     <b>所有环境默认关，包括报价模式 live 和配了真实报价源</b>（Amos 2026-11-17 决定"授权检查彻底关掉"）
 */
@ConfigurationProperties(prefix = "quote.license")
public record LicenseProperties(String enforce) {
  public LicenseProperties {
    enforce = enforce == null || enforce.isBlank() ? "false" : enforce.strip().toLowerCase(java.util.Locale.ROOT);
  }

  /** 认得的值；其他值（写错）按关处理，启动时记 warn */
  static final java.util.Set<String> KNOWN = java.util.Set.of("true", "false", "auto");

  /** 只有明确设 true 才检查 */
  public boolean enforced() {
    return "true".equals(enforce);
  }
}
