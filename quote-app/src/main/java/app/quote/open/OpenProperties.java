package app.quote.open;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 报价对外参数（quote.yml 的 quote.open）。<b>没有</b>打开对外总开关的配置项：总开关只在后台由超管打开（存在库里，默认关）。
 *
 * @param keyEncKey 加密保存 Secret 用的密钥（只从环境变量 FX_QUOTE_KEY_ENC_KEY 来）。为空时不能建 Key、所有外部请求验签失败（不会因为忘了配置而放开）
 * @param timestampSkew 签名里的时间戳和服务器时间最多差多少（±）
 * @param ticketTtl WebSocket 连接票据的有效期（只能用一次）
 * @param usageFlush 用量从内存写进库的间隔
 * @param retentionMonths 用量保留几个月
 * @param nonceTtl 签名里的 nonce 记多久（防重放，同一个 Key 的 nonce 在这段时间里只能用一次；不短于时间戳偏差 × 2）
 * @param sweep 多久检查一次已连的对外 WebSocket（Key 停用、总开关关掉后断开；要求 5 秒内）
 * @param docs 开发者文档 /docs/ 是否能看：auto（默认，报价模式不是 live 时能看，即对外开放前只在测试环境能看）/ true / false
 */
@ConfigurationProperties(prefix = "quote.open")
public record OpenProperties(String keyEncKey, Duration timestampSkew, Duration ticketTtl, Duration usageFlush, int retentionMonths, Duration sweep, String docs,
    Duration nonceTtl) {
  public OpenProperties {
    keyEncKey = keyEncKey == null ? "" : keyEncKey.strip();
    timestampSkew = timestampSkew == null ? Duration.ofSeconds(300) : timestampSkew;
    ticketTtl = ticketTtl == null ? Duration.ofMinutes(5) : ticketTtl;
    usageFlush = usageFlush == null ? Duration.ofSeconds(60) : usageFlush;
    retentionMonths = retentionMonths <= 0 ? 13 : retentionMonths;
    sweep = sweep == null ? Duration.ofSeconds(1) : sweep;
    nonceTtl = nonceTtl == null || nonceTtl.compareTo(timestampSkew.multipliedBy(2)) < 0 ? timestampSkew.multipliedBy(2) : nonceTtl;
    docs = docs == null || docs.isBlank() ? "auto" : docs.strip().toLowerCase(java.util.Locale.ROOT);
  }
}
