package app.trading.wallet.deposit;

import app.trading.common.e2e.E2eGuard;
import app.trading.wallet.Chain;
import app.trading.wallet.address.Base58;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 端到端测试的充值地址替身（只在 profile e2e 下存在）：按用户号算出固定的假地址，不对应任何私钥，也不上链。
 * 以太坊体系（ERC20、BSC 共用）是 0x + 40 位小写十六进制；TRON 是 0x41 + 20 字节的 Base58Check（T 开头 34 位）。
 * 20 字节 = SHA-256("trading-e2e:" + 家族 + ":" + 用户号) 的前 20 字节。
 */
@Component
@Profile(E2eGuard.PROFILE)
class E2eAddressBook implements DepositAddressBook {

  private static byte[] body(Chain.Family family, long userId) {
    try {
      byte[] h = MessageDigest.getInstance("SHA-256").digest(("trading-e2e:" + family + ":" + userId).getBytes(StandardCharsets.US_ASCII));
      byte[] out = new byte[20];
      System.arraycopy(h, 0, out, 0, 20);
      return out;
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String address(Chain.Family family, long userId) {
    byte[] b = body(family, userId);
    if (family == Chain.Family.ETH) return "0x" + HexFormat.of().formatHex(b);
    byte[] payload = new byte[21];
    payload[0] = 0x41;
    System.arraycopy(b, 0, payload, 1, 20);
    return Base58.encodeChecked(payload);
  }

  /** 发出去过的地址 → 用户号（替身只在测试进程里用，客户先打开充值页拿到地址，测试再往这个地址转账） */
  private final Map<String, Long> issued = new ConcurrentHashMap<>();

  private static String key(Chain.Family family, String address) {
    return family + ":" + (family == Chain.Family.ETH ? address.toLowerCase() : address);
  }

  @Override
  public Optional<String> addressOf(long userId, Chain chain) {
    String a = address(chain.family, userId);
    issued.put(key(chain.family, a), userId);
    return Optional.of(a);
  }

  @Override
  public Optional<Long> userOf(Chain chain, String address) {
    if (address == null) return Optional.empty();
    return Optional.ofNullable(issued.get(key(chain.family, address)));
  }
}
