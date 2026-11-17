package app.quote.stream;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;

/** 内部 Key 校验（常数时间比较）。没配置 Key 时一律拒绝（不会因为忘了配置而放开）。 */
@Component

public class InternalKey {

  public static final String HEADER = app.quote.protocol.QuoteStreamClient.KEY_HEADER;

  private final byte[] key;

  InternalKey(StreamProperties props) {
    this.key = props.internalKey().getBytes(StandardCharsets.UTF_8);
  }

  public boolean matches(String presented) {
    if (key.length == 0 || presented == null) return false;
    return MessageDigest.isEqual(key, presented.strip().getBytes(StandardCharsets.UTF_8));
  }
}
