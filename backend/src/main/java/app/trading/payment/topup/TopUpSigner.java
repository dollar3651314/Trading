package app.trading.payment.topup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TopUP 开放 API 的签名（和 TopUP {@code server/pay/api-v1.js}、{@code callbacks.js} 一致）：
 * <ul>
 *   <li>请求：X-TopUP-Signature = hex(HMAC-SHA256(Secret, 时间戳 \n 方法 \n 路径和查询 \n hex(SHA-256(请求体))))，路径形如 /api/v1/orders/</li>
 *   <li>回调：X-TopUP-Signature = hex(HMAC-SHA256(Secret, 时间戳 \n 原始请求体))</li>
 * </ul>
 */
public final class TopUpSigner {

  private TopUpSigner() {}

  public static String signRequest(String secret, String ts, String method, String pathAndQuery, String body) {
    return hmac(secret, String.join("\n", ts, method, pathAndQuery, sha256(body == null ? "" : body)));
  }

  public static String signCallback(String secret, String ts, String body) {
    return hmac(secret, ts + "\n" + body);
  }

  static String sha256(String s) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String hmac(String secret, String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 定长比较，不泄露前几位是否相同 */
  public static boolean same(String a, String b) {
    if (a == null || b == null) return false;
    return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}
