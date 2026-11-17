package app.quote.license;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 报价源授权检查（quote.yml 的 quote.license）。
 *
 * @param enforce 是否按"交易用途"授权决定能不能开仓：auto（默认，报价模式 live 即生产时启用，simulated 的测试环境不受限制）/ true / false
 */
@ConfigurationProperties(prefix = "quote.license")
public record LicenseProperties(String enforce) {
  public LicenseProperties {
    enforce = enforce == null || enforce.isBlank() ? "auto" : enforce.strip().toLowerCase(java.util.Locale.ROOT);
  }
}
