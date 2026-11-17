package app.trading.payment.topup;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * TopUP 开放 API 的配置（application.yml 的 trading.payment.topup）。值由 Amos 填进服务器环境变量，仓库只有变量名（底线 1）：
 * {@code FX_TOPUP_BASE_URL}、{@code FX_TOPUP_API_KEY}、{@code FX_TOPUP_API_SECRET}。
 * TopUP 用同一个 API Secret 给请求签名、给回调签名（TopUP {@code server/pay/callbacks.js}）。
 *
 * @param baseUrl TopUP 地址，例如 https://topup.example（不带 /api/v1）
 * @param apiKey 商户 API Key（X-TopUP-Key）
 * @param apiSecret 商户 API Secret：请求签名和回调验签都用它
 * @param timeout 每个请求最多等多久
 */
@ConfigurationProperties("trading.payment.topup")
public record TopUpProperties(String baseUrl, String apiKey, String apiSecret, @DefaultValue("10s") Duration timeout) {

  public boolean configured() {
    return notBlank(baseUrl) && notBlank(apiKey) && notBlank(apiSecret);
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }
}
