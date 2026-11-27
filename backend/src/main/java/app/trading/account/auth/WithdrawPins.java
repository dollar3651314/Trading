package app.trading.account.auth;

import app.trading.account.api.WithdrawBlocked;
import org.springframework.context.ApplicationEventPublisher;
import app.trading.account.api.SecurityEvent;
import app.trading.account.api.WithdrawPinApi;
import app.trading.common.error.BizException;
import app.trading.common.error.ValidationException;
import app.trading.notification.api.NotifyApi;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link WithdrawPinApi} 的实现，加上客户自己设置、修改、忘记提现密码（网页 {@code /api/me/withdraw-pin/**}，{@link WithdrawPinController}）。 */
@Service
class WithdrawPins implements WithdrawPinApi {

  static final int LENGTH = 6;
  static final int MAX_FAILURES = 5;
  static final Duration LOCK = Duration.ofHours(24);
  static final Duration LOCK_WINDOW = Duration.ofDays(30);
  static final int LOCKS_TO_FREEZE = 2;
  static final Duration KEYPAD_TTL = Duration.ofMinutes(5);
  static final String KEYPAD_PREFIX = "fx:pinpad:";
  static final String SOURCE_PIN_LOCKS = "PIN_LOCKS";
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

  /** 一个随机键盘：编号 + 10 个键上的数字（按键盘顺序） */
  record Keypad(String id, List<Integer> digits) {}

  private final AuthMapper mapper;
  /** 冻结提现时通知钱包处理在途提现（{@link WithdrawBlocked}） */
  private final ApplicationEventPublisher events;
  private final AuthService auth;
  private final EmailCodeService codes;
  private final NotifyApi notify;
  private final StringRedisTemplate redis;
  private final Clock clock;
  private final TransactionTemplate tx;
  /** 输错计数要单独提交：外面的事务回滚也不能把计数回滚掉 */
  private final TransactionTemplate own;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

  WithdrawPins(AuthMapper mapper, AuthService auth, EmailCodeService codes, NotifyApi notify, StringRedisTemplate redis, Clock clock,
      PlatformTransactionManager txm, ApplicationEventPublisher events) {
    this.events = events;
    this.mapper = mapper;
    this.auth = auth;
    this.codes = codes;
    this.notify = notify;
    this.redis = redis;
    this.clock = clock;
    this.tx = new TransactionTemplate(txm);
    this.own = new TransactionTemplate(txm);
    this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
  }

  // ---------- 随机键盘 ----------

  /** 发一个随机键盘：0～9 打乱，存在 Redis（5 分钟、只能用一次、只给这个客户用） */
  Keypad keypad(long userId) {
    List<Integer> d = new ArrayList<>(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9));
    Collections.shuffle(d, RANDOM);
    String id = Secrets.newToken();
    StringBuilder sb = new StringBuilder();
    for (int x : d) sb.append(x);
    redis.opsForValue().set(KEYPAD_PREFIX + id, userId + ":" + sb, KEYPAD_TTL);
    return new Keypad(id, List.copyOf(d));
  }

  /** 把"按了第几个键"换成数字；键盘只能用一次（取出即删）。键盘不对、过期、不是这个客户的、按键不合法都当作"键盘失效" */
  String decode(long userId, PinInput in, String field) {
    if (in == null || in.keypadId() == null || in.positions() == null) throw invalid(field, "required");
    String v = redis.opsForValue().getAndDelete(KEYPAD_PREFIX + in.keypadId());
    if (v == null) throw new BizException("PIN_KEYPAD_EXPIRED", "键盘已失效，请重新输入");
    int i = v.indexOf(':');
    if (i < 0 || !v.substring(0, i).equals(String.valueOf(userId))) throw new BizException("PIN_KEYPAD_EXPIRED", "键盘已失效，请重新输入");
    String digits = v.substring(i + 1);
    if (in.positions().size() != LENGTH) throw invalid(field, "pinLength");
    StringBuilder pin = new StringBuilder();
    for (Integer p : in.positions()) {
      if (p == null || p < 0 || p > 9) throw invalid(field, "pinLength");
      pin.append(digits.charAt(p));
    }
    return pin.toString();
  }

  private static ValidationException invalid(String field, String code) {
    return new ValidationException(List.of(new ValidationException.Field(field, code)));
  }

  /** 太简单的不行：6 位全一样、顺序递增或递减（123456、654321） */
  static boolean tooSimple(String pin) {
    boolean same = true;
    boolean up = true;
    boolean down = true;
    for (int i = 1; i < pin.length(); i++) {
      int a = pin.charAt(i - 1) - '0';
      int b = pin.charAt(i) - '0';
      same &= a == b;
      up &= b == (a + 1) % 10;
      down &= b == (a + 9) % 10;
    }
    return same || up || down;
  }

  // ---------- 客户：设置、修改、忘记 ----------

  /** 第一次设置：两次输入一致；安全事件（24 小时不能提现、作废全部网页会话） */
  void setFirst(long userId, PinInput pin, PinInput confirm) {
    String p = decode(userId, pin, "pin");
    String c = decode(userId, confirm, "confirm");
    if (!p.equals(c)) throw invalid("confirm", "pinMismatch");
    if (tooSimple(p)) throw invalid("pin", "pinTooSimple");
    tx.executeWithoutResult(st -> {
      if (mapper.pin(userId) != null) throw new BizException("PIN_ALREADY_SET", "已经设置过提现密码，请用修改");
      mapper.insertPin(userId, encoder.encode(p), now());
      auth.securityEventAndLogout(userId, SecurityEvent.PIN_SET);
      notify.toUser(userId, "WITHDRAW_PIN_SET", Map.of());
    });
  }

  /** 修改：先核对旧密码（输错照样计数、锁定），冻结提现时不能改；安全事件 */
  void change(long userId, PinInput old, PinInput pin, PinInput confirm) {
    String p = decode(userId, pin, "pin");
    String c = decode(userId, confirm, "confirm");
    if (!p.equals(c)) throw invalid("confirm", "pinMismatch");
    if (tooSimple(p)) throw invalid("pin", "pinTooSimple");
    verify(userId, old);
    tx.executeWithoutResult(st -> {
      mapper.updatePinHash(userId, encoder.encode(p), now());
      auth.securityEventAndLogout(userId, SecurityEvent.PIN_CHANGE);
      notify.toUser(userId, "WITHDRAW_PIN_CHANGED", Map.of("hours", "24"));
    });
  }

  /** 忘记提现密码（绑了邮箱的）：发验证码到绑定的邮箱。没邮箱的走人工（第 4 批 B） */
  int forgotCode(long userId) {
    String email = auth.user(userId).email();
    if (email == null) throw new BizException("NO_EMAIL", "没有绑定邮箱：忘记提现密码要走人工找回");
    if (frozen(userId)) throw new BizException("WITHDRAW_FROZEN", "提现已冻结，不能重设提现密码");
    return codes.send(email, EmailCodeService.Purpose.RESET_PIN);
  }

  /** 忘记提现密码（绑了邮箱的）：验证码 + 新密码；安全事件，48 小时不能提现；白名单保留 */
  void forgotReset(long userId, String code, PinInput pin, PinInput confirm) {
    String email = auth.user(userId).email();
    if (email == null) throw new BizException("NO_EMAIL", "没有绑定邮箱：忘记提现密码要走人工找回");
    String p = decode(userId, pin, "pin");
    String c = decode(userId, confirm, "confirm");
    if (!p.equals(c)) throw invalid("confirm", "pinMismatch");
    if (tooSimple(p)) throw invalid("pin", "pinTooSimple");
    if (frozen(userId)) throw new BizException("WITHDRAW_FROZEN", "提现已冻结，不能重设提现密码");
    if (!codes.verifyAndConsume(email, EmailCodeService.Purpose.RESET_PIN, code)) throw new BizException("CODE_INVALID", "验证码错误或已失效");
    tx.executeWithoutResult(st -> {
      if (mapper.pin(userId) == null) mapper.insertPin(userId, encoder.encode(p), now());
      else mapper.updatePinHash(userId, encoder.encode(p), now());
      auth.securityEventAndLogout(userId, SecurityEvent.PIN_RESET);
      notify.toUser(userId, "WITHDRAW_PIN_CHANGED", Map.of("hours", "48"));
    });
  }

  // ---------- WithdrawPinApi ----------

  @Override
  public Status status(long userId) {
    AuthMapper.PinRow p = mapper.pin(userId);
    LocalDateTime locked = p != null && p.lockedUntil() != null && p.lockedUntil().isAfter(now()) ? p.lockedUntil() : null;
    return new Status(p != null, locked,
        mapper.withdrawFreezes(userId).stream().map(f -> new WithdrawPinApi.Freeze(f.source(), f.reason(), f.createdAt(), f.releaseAt())).toList());
  }

  /** 有任何一个来源的提现冻结 */
  private boolean frozen(long userId) {
    return !mapper.withdrawFreezes(userId).isEmpty();
  }

  @Override
  public void verify(long userId, PinInput pin) {
    if (frozen(userId)) throw new BizException("WITHDRAW_FROZEN", "提现已冻结：要人工核对本人后才能解冻");
    String p = decode(userId, pin, "pin");
    BizException e = own.execute(st -> check(userId, p));
    if (e != null) throw e;
  }

  @Override
  public void verifyIdentity(long userId, PinInput pin) {
    String p = decode(userId, pin, "pin");
    BizException e = own.execute(st -> check(userId, p));
    if (e != null) throw e;
  }

  static final String SOURCE_BOT_SELF = "BOT_SELF";
  static final Duration SELF_UNFREEZE_WAIT = Duration.ofHours(24);

  @Override
  public LocalDateTime requestSelfUnfreeze(long userId, PinInput pin) {
    boolean self = mapper.withdrawFreezes(userId).stream().anyMatch(f -> SOURCE_BOT_SELF.equals(f.source()));
    if (!self) throw new BizException("NOT_SELF_FROZEN", "没有在机器人里自己冻结的提现，网页申请等别的冻结要人工解冻");
    verifyIdentity(userId, pin);
    return tx.execute(st -> {
      AuthMapper.WithdrawFreezeRow f = mapper.withdrawFreezes(userId).stream().filter(x -> SOURCE_BOT_SELF.equals(x.source())).findFirst()
          .orElseThrow(() -> new BizException("NOT_SELF_FROZEN", "没有在机器人里自己冻结的提现"));
      if (f.releaseAt() != null) return f.releaseAt();
      LocalDateTime at = now().plus(SELF_UNFREEZE_WAIT);
      mapper.setFreezeRelease(userId, SOURCE_BOT_SELF, at);
      notify.toUser(userId, "WITHDRAW_SELF_UNFREEZE", Map.of("at", at.format(TIME)));
      return at;
    });
  }

  @Override
  public int releaseDueSelfUnfreezes() {
    LocalDateTime now = now();
    int n = 0;
    for (Long userId : mapper.dueSelfUnfreezes(now)) {
      Boolean done = tx.execute(st -> {
        if (mapper.deleteDueSelfUnfreeze(userId, now) != 1) return false;
        notify.toUser(userId, "WITHDRAW_UNFROZEN", Map.of("source", "机器人里自己冻结", "sourceEn", "frozen yourself in the bot"));
        return true;
      });
      if (Boolean.TRUE.equals(done)) n++;
    }
    return n;
  }

  @Override
  public void clearPin(long userId) {
    mapper.deletePin(userId);
  }

  @Override
  public boolean releaseFreeze(long userId, String source) {
    return mapper.deleteWithdrawFreezeOf(userId, source) == 1;
  }

  /** 在自己的事务里核对、计数；返回要抛的错（不在事务里抛，免得计数被回滚） */
  private BizException check(long userId, String p) {
    AuthMapper.PinRow row = mapper.lockPin(userId);
    if (row == null) return new BizException("PIN_NOT_SET", "还没有设置提现密码");
    LocalDateTime now = now();
    if (row.lockedUntil() != null && row.lockedUntil().isAfter(now)) {
      return new BizException("PIN_LOCKED", "提现密码已锁定，" + row.lockedUntil().format(TIME) + " 后再试");
    }
    // 锁定期满：计数清零
    int failed = row.lockedUntil() != null ? 0 : row.failedCount();
    if (encoder.matches(p, row.pinHash())) {
      if (failed != 0 || row.lockedUntil() != null) mapper.updatePinFailures(userId, 0, null, now);
      return null;
    }
    failed++;
    if (failed < MAX_FAILURES) {
      mapper.updatePinFailures(userId, failed, null, now);
      return new BizException("PIN_WRONG", "提现密码不对，还能再试 " + (MAX_FAILURES - failed) + " 次");
    }
    LocalDateTime until = now.plus(LOCK);
    mapper.updatePinFailures(userId, 0, until, now);
    mapper.insertPinLock(userId, now);
    notify.toUser(userId, "WITHDRAW_PIN_LOCKED", Map.of("until", until.format(TIME)));
    if (mapper.countPinLocksSince(userId, now.minus(LOCK_WINDOW)) >= LOCKS_TO_FREEZE) {
      freeze(userId, SOURCE_PIN_LOCKS, "30 天内提现密码被锁 2 次", now);
    }
    return new BizException("PIN_LOCKED", "提现密码连续输错 5 次，已锁定到 " + until.format(TIME));
  }

  @Override
  public void freezeWithdraw(long userId, String source, String reason) {
    tx.executeWithoutResult(st -> freeze(userId, source, reason, now()));
  }

  private void freeze(long userId, String source, String reason, LocalDateTime now) {
    if (mapper.insertWithdrawFreeze(userId, source, reason, now) == 1) {
      notify.toUser(userId, "WITHDRAW_FROZEN", Map.of("reason", reason == null ? "" : reason));
      // 第 4 批审查 🔴4a-1：在途的提现一起挡住（钱包在这个事务提交以后处理）
      events.publishEvent(new WithdrawBlocked(userId, "提现已冻结：" + (reason == null ? source : reason)));
    }
  }

  @Override
  public java.util.List<String> unfreezeWithdraw(long userId, String source) {
    return tx.execute(st -> {
      java.util.List<String> had = mapper.withdrawFreezes(userId).stream().map(AuthMapper.WithdrawFreezeRow::source)
          .filter(x -> source == null || x.equals(source)).toList();
      if (source == null) mapper.deleteWithdrawFreeze(userId);
      else mapper.deleteWithdrawFreezeOf(userId, source);
      return had;
    });
  }
}
