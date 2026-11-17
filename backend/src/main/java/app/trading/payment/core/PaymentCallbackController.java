package app.trading.payment.core;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供方回调：{@code POST /api/pay/callback/topup}（不要登录，靠验签；SessionFilter 的公开接口）。
 * 2xx 表示收下了（含重复投递、不认识的事件）；签名不对 401；处理出错 500，提供方会按它的规则重试（TopUP：1 分钟 … 24 小时，共 8 次）。
 * 回调地址要 https、不能是内网（TopUP 的规矩），由 Amos 在 TopUP 后台填。
 */
@RestController
class PaymentCallbackController {

  /** 回调请求体最大 64 KB（TopUP 的回调只有一个事件） */
  static final int MAX_BODY = 64 * 1024;

  private final PaymentService payments;

  PaymentCallbackController(PaymentService payments) {
    this.payments = payments;
  }

  @PostMapping("/api/pay/callback/topup")
  ResponseEntity<Map<String, String>> topup(HttpServletRequest req) throws IOException {
    byte[] raw = req.getInputStream().readNBytes(MAX_BODY + 1);
    if (raw.length > MAX_BODY) return ResponseEntity.status(413).body(Map.of("result", "too_large"));
    Map<String, String> headers = new LinkedHashMap<>();
    for (String n : Collections.list(req.getHeaderNames())) headers.put(n.toLowerCase(), req.getHeader(n));
    PaymentService.CallbackResult r = payments.callback("TOPUP", headers, new String(raw, StandardCharsets.UTF_8));
    return switch (r) {
      case BAD_SIGNATURE -> ResponseEntity.status(401).body(Map.of("result", "bad_signature"));
      case UNKNOWN_PROVIDER -> ResponseEntity.status(404).body(Map.of("result", "unknown_provider"));
      default -> ResponseEntity.ok(Map.of("result", r.name().toLowerCase()));
    };
  }
}
