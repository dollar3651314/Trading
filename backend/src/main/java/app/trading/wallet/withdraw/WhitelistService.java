package app.trading.wallet.withdraw;

import app.trading.common.error.BizException;
import app.trading.common.error.ValidationException;
import app.trading.wallet.Chain;
import app.trading.wallet.address.Base58;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 提现白名单（需求 4.4-4）：每人最多 10 个，新地址 24 小时后生效，地址格式按网络校验。 */
@Service
class WhitelistService {

  private static final Pattern ETH = Pattern.compile("0x[0-9a-fA-F]{40}");

  private final WithdrawMapper mapper;
  private final WithdrawLimits limits;
  private final Clock clock;

  private final TelegramWithdrawGate tgGate;

  private final app.trading.notification.api.NotifyApi notify;

  WhitelistService(WithdrawMapper mapper, WithdrawLimits limits, Clock clock, TelegramWithdrawGate tgGate, app.trading.notification.api.NotifyApi notify) {
    this.notify = notify;
    this.tgGate = tgGate;
    this.mapper = mapper;
    this.limits = limits;
    this.clock = clock;
  }

  List<WhitelistRow> list(long userId) {
    return mapper.whitelist(userId);
  }

  @Transactional
  WhitelistRow add(long userId, String network, String address, String label) {
    return add(userId, network, address, label, null);
  }

  /**
   * TG 客户（第 4 批）：总开关、提现密码（随机键盘）；生效至少 24 小时；加时通知（生效前 1 小时、生效时由 {@link WithdrawScheduler} 通知）。
   * 别的客户照旧（pin 不看）
   */
  WhitelistRow add(long userId, String network, String address, String label, app.trading.account.api.WithdrawPinApi.PinInput pin) {
    tgGate.check(userId); // TB-1：TG 客户提现默认关（第 4 批验收后打开）
    boolean tg = tgGate.isTg(userId);
    ValidationException.Collector errs = new ValidationException.Collector();
    Chain chain = null;
    try {
      chain = network == null ? null : Chain.valueOf(network);
    } catch (IllegalArgumentException e) {
      chain = null;
    }
    if (chain == null) errs.add("network", "invalid");
    String a = address == null ? "" : address.trim();
    if (a.isEmpty()) errs.add("address", "required");
    else if (chain != null && !validAddress(chain, a)) errs.add("address", "addressInvalid");
    String l = label == null ? "" : label.trim();
    if (l.isEmpty()) errs.add("label", "required");
    else if (l.length() > 32) errs.add("label", "tooLong");
    errs.throwIfAny();

    if (mapper.countWhitelist(userId) >= props().maxWhitelist()) throw new BizException("WHITELIST_FULL", "白名单已满");
    if (tg) tgGate.verifyPin(userId, pin);
    LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    WhitelistRow r = new WhitelistRow();
    r.userId = userId;
    r.network = chain.name();
    r.address = a;
    r.addressKey = chain.family == Chain.Family.ETH ? a.toLowerCase() : a;
    r.label = l;
    r.createdAt = now;
    java.time.Duration delay = props().whitelistDelay();
    // TG 客户的白名单至少 24 小时生效（TB-14、TB-37 下限）
    if (tg && delay.compareTo(java.time.Duration.ofHours(24)) < 0) delay = java.time.Duration.ofHours(24);
    r.activeAt = now.plus(delay);
    try {
      mapper.insertWhitelist(r);
    } catch (DuplicateKeyException e) {
      throw new BizException("WHITELIST_DUPLICATE", "地址已在白名单里");
    }
    if (tg) notify.toUser(userId, "WHITELIST_ADDED", java.util.Map.of("network", r.network, "address", mask(r.address), "activeAt", r.activeAt.format(TIME)));
    return r;
  }

  /**
   * TG 客户的白名单通知（第 4 批，03 页第 0 节）：生效前 1 小时一次、生效时一次（TG + 邮箱 + 站内信）。由 {@link WithdrawScheduler} 每 30 秒调；
   * 生效超过 1 小时还没发过"已生效"的不补发（停机很久后不刷屏）。返回发了几条
   */
  int notifyWhitelist() {
    LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    int n = 0;
    for (WhitelistRow r : mapper.whitelistToNotifySoon(now, now.plusHours(1))) {
      if (mapper.markWhitelistSoon(r.id, now) == 1 && tgGate.isTg(r.userId)) {
        notify.toUser(r.userId, "WHITELIST_SOON", java.util.Map.of("network", r.network, "address", mask(r.address), "activeAt", r.activeAt.format(TIME)));
        n++;
      }
    }
    for (WhitelistRow r : mapper.whitelistToNotifyActive(now, now.minusHours(1))) {
      if (mapper.markWhitelistActive(r.id, now) == 1 && tgGate.isTg(r.userId)) {
        notify.toUser(r.userId, "WHITELIST_ACTIVE", java.util.Map.of("network", r.network, "address", mask(r.address)));
        n++;
      }
    }
    return n;
  }

  @Transactional
  void remove(long userId, long id) {
    if (mapper.deleteWhitelist(userId, id) != 1) throw new BizException("NOT_FOUND", "白名单地址不存在");
  }

  static final java.time.format.DateTimeFormatter TIME = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

  /** 通知里的地址只露前 4 后 4（03 页第 6 节） */
  static String mask(String a) {
    return a == null || a.length() <= 10 ? a : a.substring(0, 4) + "…" + a.substring(a.length() - 4);
  }

  /** 以太坊体系：0x + 40 位十六进制；TRON：Base58Check、0x41 开头、21 字节 */
  static boolean validAddress(Chain chain, String a) {
    if (chain.family == Chain.Family.ETH) return ETH.matcher(a).matches();
    try {
      byte[] p = Base58.decodeChecked(a);
      return p.length == 21 && p[0] == 0x41;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** 当前的提现参数（后台可改，S16a） */
  private WithdrawProperties props() {
    return limits.current();
  }
}
