package app.trading.support;

import app.trading.account.api.WithdrawPinApi.PinInput;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 提现密码的测试工具（TG 机器人第 4 批）：直接写一个已知顺序的"随机键盘"到 Redis（和 WithdrawPins 同一种格式），
 * 按要输的数字算出按键位置；直接写提现密码的哈希（别的模块的测试用，不走设置流程）。
 */
public final class PinTestSupport {

  private PinTestSupport() {}

  /** 给客户发一个键盘（数字顺序随便给），返回输入 pin 时要交的东西 */
  public static PinInput input(StringRedisTemplate redis, long userId, String pin, String layout) {
    String id = "t-" + UUID.randomUUID();
    redis.opsForValue().set("fx:pinpad:" + id, userId + ":" + layout, Duration.ofMinutes(5));
    List<Integer> pos = new ArrayList<>();
    for (char c : pin.toCharArray()) pos.add(layout.indexOf(c));
    return new PinInput(id, pos);
  }

  public static PinInput input(StringRedisTemplate redis, long userId, String pin) {
    return input(redis, userId, pin, "7395120486");
  }

  /** 直接设好提现密码（BCrypt） */
  public static void setPin(JdbcTemplate jdbc, long userId, String pin) {
    jdbc.update("INSERT INTO account_withdraw_pin (user_id, pin_hash, failed_count, created_at, updated_at) VALUES (?, ?, 0, NOW(3), NOW(3)) "
        + "ON DUPLICATE KEY UPDATE pin_hash = VALUES(pin_hash), failed_count = 0, locked_until = NULL", userId, new BCryptPasswordEncoder(4).encode(pin));
  }

  /** 库里的提现密码是不是这个 */
  public static boolean matches(JdbcTemplate jdbc, long userId, String pin) {
    String hash = jdbc.queryForObject("SELECT pin_hash FROM account_withdraw_pin WHERE user_id = ?", String.class, userId);
    return new BCryptPasswordEncoder().matches(pin, hash);
  }

  /** 把一个客户变成 TG 客户（有 TG 账户） */
  public static void makeTg(JdbcTemplate jdbc, long userId) {
    jdbc.update("INSERT INTO account_telegram (user_id, telegram_id, username, source, authorized_at, created_at) VALUES (?, ?, NULL, 'SIGNUP', '2026-01-01', '2026-01-01')",
        userId, 5_000_000_000L + userId);
  }
}
