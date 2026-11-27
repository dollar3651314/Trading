package app.trading.account.auth;

import app.trading.account.api.SecurityEvent;
import app.trading.account.api.TelegramAccountApi.TgAccount;
import app.trading.account.api.UserStatus;
import app.trading.notification.api.NotifyApi;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 应急开关下"用 Telegram 登录"网页（第 5 批，技术方案 TG机器人-11 页第 3 节，TB-34；需求文档 TG机器人-03 页"网页 8082「用 Telegram 登录」"）：
 * <ul>
 *   <li>只在开关<b>打开</b>、而且配好了<b>另一个只管登录的机器人</b>（{@code FX_TG_LOGIN_BOT_USERNAME}、令牌 {@code FX_TG_LOGIN_BOT_TOKEN}，
 *       令牌只由 Amos 用 set-key 填）时可用；交易机器人被封时它大概率不受连累。</li>
 *   <li>用 Telegram 官方登录小部件（Login Widget）：网页拿到 Telegram 签过名的身份（id、名字、auth_date、hash），交给这里。
 *       验 hash 的办法和一键登录一样，只是密钥换成<b>登录机器人</b>令牌的 SHA-256；{@code auth_date} 只认 5 分钟以内的；hash 只能用一次（和一键登录共用"已用 hash"表）；
 *       <b>只认 {@code id}</b>（对应机器人里的 {@code from.id}）。技术方案写的是 OIDC，这里先用 Login Widget（文档里写明的验签办法），协调方同意过。</li>
 *   <li>冻结的账户拒绝。通过后<b>记安全事件 {@code TG_WEB_LOGIN}</b>（作废这个账户其他全部网页会话；24 小时不能提现，TelegramWithdrawGate 统一拦），
 *       再建一个应急登录会话（{@code kind=EMERGENCY}，开关关上就失效），并给客户发安全提醒（站内信、推送、邮件、机器人）。</li>
 * </ul>
 * 日志里不出现身份参数。
 */
@Service
class EmergencyLogin {

  private static final Logger log = LoggerFactory.getLogger(EmergencyLogin.class);

  static final Duration MAX_AGE = Duration.ofMinutes(5);
  static final Duration HOLD = Duration.ofHours(24);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  enum Outcome {
    OK, DISABLED, INVALID, EXPIRED, USED, NO_ACCOUNT, FROZEN
  }

  record Result(Outcome outcome, String token, LocalDateTime withdrawAt) {
    static Result fail(Outcome o) {
      log.info("应急登录没有成功：{}", o); // 只记原因
      return new Result(o, null, null);
    }
  }

  private final EmergencySwitch emergency;
  private final AuthMapper mapper;
  private final TelegramAccounts accounts;
  private final AuthService auth;
  private final SessionService sessions;
  private final NotifyApi notify;
  private final Clock clock;
  private final String botToken;
  private final String botUsername;

  EmergencyLogin(EmergencySwitch emergency, AuthMapper mapper, TelegramAccounts accounts, AuthService auth, SessionService sessions, NotifyApi notify,
      Clock clock, @Value("${trading.telegram.login-bot.token:}") String botToken, @Value("${trading.telegram.login-bot.username:}") String botUsername) {
    this.emergency = emergency;
    this.mapper = mapper;
    this.accounts = accounts;
    this.auth = auth;
    this.sessions = sessions;
    this.notify = notify;
    this.clock = clock;
    this.botToken = botToken == null ? "" : botToken.strip();
    String u = botUsername == null ? "" : botUsername.strip();
    this.botUsername = u.startsWith("@") ? u.substring(1) : u;
  }

  /** 登录机器人配好了吗（用户名和令牌都有） */
  boolean configured() {
    return !botToken.isEmpty() && !botUsername.isEmpty();
  }

  /** 网页要不要显示「用 Telegram 登录」：开关开着（内存里的值）、登录机器人配好了 */
  boolean available() {
    return configured() && emergency.on();
  }

  String botUsername() {
    return botUsername.isEmpty() ? null : botUsername;
  }

  /** @param params 登录小部件给的全部字段（id、first_name、last_name、username、photo_url、auth_date、hash），值都转成文字 */
  @Transactional
  Result login(Map<String, String> params, String ip, String userAgent) {
    if (!configured() || !emergency.onFresh()) return Result.fail(Outcome.DISABLED);
    String hash = params.get("hash");
    String id = params.get("id");
    if (hash == null || id == null || params.size() > 10) return Result.fail(Outcome.INVALID);
    if (!TelegramWebLogin.validHash(params, hash, botToken)) return Result.fail(Outcome.INVALID);
    long authDate;
    long tgId;
    try {
      authDate = Long.parseLong(params.getOrDefault("auth_date", ""));
      tgId = Long.parseLong(id);
    } catch (NumberFormatException e) {
      return Result.fail(Outcome.INVALID);
    }
    Instant now = clock.instant();
    Instant at = Instant.ofEpochSecond(authDate);
    if (at.isBefore(now.minus(MAX_AGE)) || at.isAfter(now.plus(TelegramWebLogin.FUTURE_SKEW))) return Result.fail(Outcome.EXPIRED);
    try {
      mapper.insertLoginHash(hash.toLowerCase(), LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    } catch (DuplicateKeyException e) {
      return Result.fail(Outcome.USED);
    }
    Optional<TgAccount> acc = accounts.byTelegramId(tgId);
    if (acc.isEmpty()) return Result.fail(Outcome.NO_ACCOUNT);
    long userId = acc.get().userId();
    if (auth.status(userId) == UserStatus.FROZEN) return Result.fail(Outcome.FROZEN);
    // 安全事件：作废这个账户其他全部网页会话（含机器人会话、别的应急会话），24 小时不能提现；之后再建这次的会话
    auth.securityEventAndLogout(userId, SecurityEvent.TG_WEB_LOGIN);
    String token = sessions.createEmergency(userId, ip, userAgent);
    LocalDateTime withdrawAt = LocalDateTime.ofInstant(now, ZoneOffset.UTC).plus(HOLD);
    notify.toUser(userId, "TG_EMERGENCY_LOGIN",
        Map.of("device", TelegramWebLogin.device(userAgent), "ip", TelegramWebLogin.maskIp(ip), "withdrawAt", withdrawAt.format(TIME)));
    log.warn("客户 {} 在应急开关下用 Telegram 登录了网页（安全事件，24 小时不能提现）", userId);
    return new Result(Outcome.OK, token, withdrawAt);
  }
}
