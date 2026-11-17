package app.quote.open;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 对外 REST {@code /v1/**} 的鉴权和限额（WebSocket {@code /v1/ws} 用票据，不走这里）。顺序：
 * <ol>
 *   <li>验签（HMAC-SHA256，和 TopUP 一样三个请求头：X-QK-Key、X-QK-Timestamp 秒、X-QK-Signature；时间戳 ±300 秒）。Key 不存在、已停用、签名不对 → 401；</li>
 *   <li>闸 1：外部 Key 在对外开放总开关关着时 → 403（内部测试 Key 不受限制）；</li>
 *   <li>限额：超每秒或每天请求数 → 429 + Retry-After，只拒这一次，不算进请求数；</li>
 *   <li>放行，记一次用量。</li>
 * </ol>
 * 签名原文：{@code 时间戳 + "\n" + 方法 + "\n" + 路径（带 ? 和查询串，原样）+ "\n" + 请求体}，结果是小写十六进制。
 */
@Component
class OpenAuthFilter extends OncePerRequestFilter {

  static final String KEY_HEADER = "X-QK-Key";
  static final String TS_HEADER = "X-QK-Timestamp";
  static final String SIG_HEADER = "X-QK-Signature";
  static final String KEY_ATTR = "quote.open.key";

  private final ApiKeys keys;
  private final Entitlements ent;
  private final Usage usage;
  private final OpenProperties props;
  private final Clock clock;

  OpenAuthFilter(ApiKeys keys, Entitlements ent, Usage usage, OpenProperties props, Clock clock) {
    this.keys = keys;
    this.ent = ent;
    this.usage = usage;
    this.props = props;
    this.clock = clock;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    String uri = req.getRequestURI();
    return !uri.startsWith("/v1/") || uri.equals("/v1/ws");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
    String keyId = req.getHeader(KEY_HEADER);
    String ts = req.getHeader(TS_HEADER);
    String sig = req.getHeader(SIG_HEADER);
    var key = keys.find(keyId);
    var secret = keys.secret(keyId);
    if (key.isEmpty() || secret.isEmpty() || ts == null || sig == null) {
      unauthorized(req, res, "Key 不存在、已停用，或者缺少签名请求头");
      return;
    }
    long t;
    try {
      t = Long.parseLong(ts.strip());
    } catch (NumberFormatException e) {
      unauthorized(req, res, "X-QK-Timestamp 要是秒级 Unix 时间戳");
      return;
    }
    if (Math.abs(clock.instant().getEpochSecond() - t) > props.timestampSkew().toSeconds()) {
      unauthorized(req, res, "时间戳和服务器时间相差超过 " + props.timestampSkew().toSeconds() + " 秒");
      return;
    }
    String body = "GET".equals(req.getMethod()) ? "" : new String(req.getInputStream().readNBytes(8192), StandardCharsets.UTF_8);
    String path = req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
    String expected = sign(secret.get(), ts.strip() + "\n" + req.getMethod() + "\n" + path + "\n" + body);
    if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sig.strip().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
      unauthorized(req, res, "签名不对");
      return;
    }
    ApiKeys.Key k = key.get();
    if (!ent.gatePasses(k)) {
      OpenErrors.write(req, res, 403, OpenErrors.body("OPEN_SERVICE_CLOSED", "报价对外服务还没有开放，外部 Key 暂时不能用", OpenErrors.trace(req)));
      return;
    }
    String endpoint = endpoint(req.getRequestURI());
    String symbol = "/v1/klines".equals(req.getRequestURI()) ? req.getParameter("symbol") : null;
    if (symbol != null && !symbol.matches("[A-Z0-9]{1,16}")) symbol = null;
    Usage.Decision d = usage.rest(k, endpoint, symbol);
    if (d instanceof Usage.Rejected r) {
      res.setHeader("Retry-After", String.valueOf(r.retryAfter()));
      Map<String, Object> b = OpenErrors.body("RATE_LIMITED", "second".equals(r.scope()) ? "超过每秒请求数限额" : "超过每天请求数限额", OpenErrors.trace(req));
      b.put("scope", r.scope());
      b.put("limit", r.limit());
      b.put("remaining", 0);
      b.put("resetAt", r.resetAt().toEpochMilli());
      OpenErrors.write(req, res, 429, b);
      return;
    }
    res.setHeader(OpenErrors.TRACE_HEADER, OpenErrors.trace(req));
    req.setAttribute(KEY_ATTR, k);
    chain.doFilter(req, res);
  }

  static String endpoint(String uri) {
    String e = uri.substring("/v1/".length());
    return e.length() > 32 ? e.substring(0, 32) : e;
  }

  private static void unauthorized(HttpServletRequest req, HttpServletResponse res, String message) throws IOException {
    OpenErrors.write(req, res, 401, OpenErrors.body("UNAUTHORIZED", message, OpenErrors.trace(req)));
  }

  /** HMAC-SHA256，小写十六进制 */
  static String sign(byte[] secret, String text) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
