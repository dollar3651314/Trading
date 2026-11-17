package app.quote.open;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Secret 的加密保存（AES-256-GCM，密钥 = SHA-256(FX_QUOTE_KEY_ENC_KEY)）。服务器验 HMAC 签名要用 Secret 本身，所以不能只存哈希；
 * 库里只有密文，接口和页面都不返回明文（建 Key、轮换时只返回这一次）。没配密钥时 {@link #ready()} 为 false：不能建 Key，也解不开已有的。
 */
@Component
public class SecretBox {

  private static final SecureRandom RANDOM = new SecureRandom();
  private final SecretKeySpec key;

  SecretBox(OpenProperties props) {
    this.key = props.keyEncKey().isEmpty() ? null : new SecretKeySpec(sha256(props.keyEncKey().getBytes(StandardCharsets.UTF_8)), "AES");
  }

  public boolean ready() {
    return key != null;
  }

  public byte[] seal(String secret) {
    if (key == null) throw new IllegalStateException("没有配置 FX_QUOTE_KEY_ENC_KEY");
    try {
      byte[] iv = new byte[12];
      RANDOM.nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
      byte[] ct = c.doFinal(secret.getBytes(StandardCharsets.UTF_8));
      byte[] out = Arrays.copyOf(iv, iv.length + ct.length);
      System.arraycopy(ct, 0, out, iv.length, ct.length);
      return out;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("加密失败", e);
    }
  }

  /** 解不开（没配密钥、密钥换了、数据坏了）返回 null */
  public byte[] open(byte[] sealed) {
    if (key == null || sealed == null || sealed.length < 13) return null;
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, sealed, 0, 12));
      return c.doFinal(sealed, 12, sealed.length - 12);
    } catch (GeneralSecurityException e) {
      return null;
    }
  }

  static byte[] sha256(byte[] b) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(b);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 随机字符串（字母数字） */
  static String random(int n) {
    String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    StringBuilder sb = new StringBuilder(n);
    for (int i = 0; i < n; i++) sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
    return sb.toString();
  }
}
