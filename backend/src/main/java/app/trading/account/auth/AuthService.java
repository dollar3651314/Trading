package app.trading.account.auth;

import app.trading.account.api.WithdrawBlocked;
import org.springframework.context.ApplicationEventPublisher;
import app.trading.account.api.SecurityEvent;
import app.trading.account.api.UserStatus;
import app.trading.account.auth.AuthMapper.UserInsert;
import app.trading.account.auth.AuthRequests.Register;
import app.trading.account.auth.Rows.Profile;
import app.trading.account.auth.Rows.UserRow;
import app.trading.common.error.BizException;
import app.trading.notification.api.NotifyApi;
import app.trading.trading.api.LedgerApi;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 注册、登录、密码和冻结。会话见 {@link SessionService}，失败计数见 {@link LoginLimiter}。 */
@Service
class AuthService {

  private final AuthMapper mapper;
  private final EmailCodeService codes;
  private final SessionService sessions;
  private final LoginLimiter limiter;
  private final SendCodeLimiter codeLimiter;
  private final LedgerApi ledger;
  private final NotifyApi notify;
  private final Clock clock;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

  /** 冻结时通知钱包处理在途提现（{@link WithdrawBlocked}） */
  private final ApplicationEventPublisher events;

  AuthService(AuthMapper mapper, EmailCodeService codes, SessionService sessions, LoginLimiter limiter, SendCodeLimiter codeLimiter, LedgerApi ledger,
      NotifyApi notify, Clock clock, ApplicationEventPublisher events) {
    this.events = events;
    this.mapper = mapper;
    this.codes = codes;
    this.sessions = sessions;
    this.limiter = limiter;
    this.codeLimiter = codeLimiter;
    this.ledger = ledger;
    this.notify = notify;
    this.clock = clock;
  }

  /**
   * 记一条账号安全事件（v2 提现自动审批 R6）：和改密码、重置密码、冻结、解冻在同一个事务里写。
   * 时间用注入的时钟（UTC），和提现判定的"现在"同一个来源。
   * <p><b>第 4 批备忘（TG 机器人第 2 批审查 🟡4）</b>：新增的安全事件（改提现密码、换绑、改邮箱等）要同时作废这个账户的<b>全部</b>网页会话，
   * 包括从机器人一键登录的 BOT 会话（{@code sessions.revokeAll(userId, null)}），和现在的冻结一样。
   */
  private void securityEvent(long userId, String type) {
    mapper.insertSecurityEvent(userId, type, LocalDateTime.now(clock.withZone(ZoneOffset.UTC)));
  }

  /**
   * 安全事件（第 4 批）：记一条，并作废这个账户的全部网页会话（含 BOT 会话）。机器人那边授权时间早于这次事件，要先「重新授权」才能继续，
   * 一键登录也会拒绝（TelegramWebLogin），所以客户回机器人重新授权、再点「打开交易」。
   */
  @Transactional
  void securityEventAndLogout(long userId, String type) {
    securityEvent(userId, type);
    sessions.revokeAll(userId, null);
  }

  java.util.List<SecurityEvent> securityEventsSince(long userId, LocalDateTime since) {
    return mapper.securityEventsSince(userId, since);
  }

  LocalDateTime registeredAt(long userId) {
    LocalDateTime t = mapper.registeredAt(userId);
    if (t == null) throw new BizException("USER_NOT_FOUND", "用户不存在");
    return t;
  }

  Optional<SecurityEvent> lastSecurityEventSince(long userId, LocalDateTime since) {
    return Optional.ofNullable(mapper.lastSecurityEventSince(userId, since));
  }

  static String normalizeEmail(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * 发验证码。注册：邮箱已注册时直接提示；重置密码：邮箱不存在时假装已发送，不暴露哪些邮箱注册过。
   * 先按 IP 限次（安全审查 SEC-03），邮箱存不存在都算一次。
   *
   * @return 下次可以重发前要等的秒数
   */
  int sendCode(String rawEmail, EmailCodeService.Purpose purpose, String ip) {
    if (!codeLimiter.allow(ip)) throw new BizException("CODE_TOO_FREQUENT", "发送太频繁，请稍后再试");
    String email = normalizeEmail(rawEmail);
    boolean exists = mapper.findUserByEmail(email) != null;
    if (purpose == EmailCodeService.Purpose.REGISTER && exists) throw new BizException("EMAIL_TAKEN", "邮箱已注册");
    // 找回密码时邮箱不存在：不发，但返回和发了一样的结果，不暴露邮箱是否注册过
    if (purpose == EmailCodeService.Purpose.RESET_PASSWORD && !exists) return codes.cooldownSeconds();
    return codes.send(email, purpose);
  }

  /** 注册：建用户、资料、交易账户，返回用户 ID。同一个事务。 */
  @Transactional
  long register(Register r) {
    String email = normalizeEmail(r.email());
    if (mapper.findUserByEmail(email) != null) throw new BizException("EMAIL_TAKEN", "邮箱已注册");
    if (!codes.verifyAndConsume(email, EmailCodeService.Purpose.REGISTER, r.code())) throw new BizException("CODE_INVALID", "验证码错误或已失效");
    UserInsert u = new UserInsert(email, encoder.encode(r.password()));
    try {
      mapper.insertUser(u);
    } catch (DuplicateKeyException e) {
      throw new BizException("EMAIL_TAKEN", "邮箱已注册"); // 同一邮箱同时注册，唯一键兜底
    }
    mapper.insertProfile(new Profile(u.id, r.firstName().trim(), r.middleName() == null ? "" : r.middleName().trim(), r.lastName().trim(), r.birthDate(), r.nationality()));
    ledger.openAccount(u.id);
    // 充值地址不在注册时分配：第 1 批改成第一次打开充值页时向支付接口层（TopUP）要
    // 欢迎站内信（体验优化 1021 UX-9）：内容和客户端新手引导一致，下一步是认证 → 充值；模板 WELCOME 只发站内信，不推送、不发邮件
    notify.toUser(u.id, "WELCOME", Map.of());
    return u.id;
  }

  /** 登录：成功返回用户；锁定、密码错误、冻结分别抛不同的错误码。用户不存在和密码错误用同一个错误码。 */
  UserRow login(String rawEmail, String password, String ip) {
    String email = normalizeEmail(rawEmail);
    if (limiter.isLocked(email, ip)) throw new BizException("LOGIN_LOCKED", "失败次数过多，请稍后再试");
    UserRow u = mapper.findUserByEmail(email);
    if (u == null || u.passwordHash() == null || !encoder.matches(password, u.passwordHash())) {
      limiter.recordFailure(email, ip);
      throw new BizException("INVALID_CREDENTIALS", "邮箱或密码错误");
    }
    if (!UserStatus.ACTIVE.name().equals(u.status())) throw new BizException("USER_FROZEN", "账号已冻结");
    limiter.clear(email, ip);
    mapper.touchLogin(u.id());
    return u;
  }

  /** 忘记密码：验证码重置，成功后所有登录失效（AC-03） */
  @Transactional
  void resetPassword(String rawEmail, String code, String password) {
    String email = normalizeEmail(rawEmail);
    UserRow u = mapper.findUserByEmail(email);
    if (u == null || !codes.verifyAndConsume(email, EmailCodeService.Purpose.RESET_PASSWORD, code)) throw new BizException("CODE_INVALID", "验证码错误或已失效");
    mapper.updatePassword(u.id(), encoder.encode(password));
    securityEvent(u.id(), SecurityEvent.PASSWORD_RESET);
    sessions.revokeAll(u.id(), null);
  }

  /** 修改密码：需要原密码；成功后其他设备的登录失效，当前设备保留（AC-03） */
  @Transactional
  void changePassword(long userId, String currentPassword, String newPassword, String currentToken) {
    UserRow u = mapper.findUserById(userId);
    if (u == null || !encoder.matches(currentPassword, u.passwordHash())) throw new BizException("CURRENT_PASSWORD_WRONG", "原密码错误");
    mapper.updatePassword(userId, encoder.encode(newPassword));
    securityEvent(userId, SecurityEvent.PASSWORD_CHANGE);
    sessions.revokeAll(userId, currentToken);
  }

  UserRow user(long userId) {
    UserRow u = mapper.findUserById(userId);
    if (u == null) throw new BizException("USER_NOT_FOUND", "用户不存在");
    return u;
  }

  @Transactional
  void freeze(long userId) {
    user(userId);
    mapper.updateStatus(userId, UserStatus.FROZEN.name());
    securityEvent(userId, SecurityEvent.FREEZE);
    sessions.revokeAll(userId, null);
    // 安全提醒（TG 机器人第 1 批）：模板 ACCOUNT_FROZEN 只发 Telegram（只发给有 TG 账户的客户；网页客户冻结后本来就登录不了），不能关
    notify.toUser(userId, "ACCOUNT_FROZEN", Map.of());
    // 第 4 批审查 🔴4a-1：在途的提现（冷静期、待审核、大额延迟中、已审核待放款）不能继续走
    events.publishEvent(new WithdrawBlocked(userId, "账户已冻结"));
  }

  @Transactional
  void unfreeze(long userId) {
    user(userId);
    mapper.updateStatus(userId, UserStatus.ACTIVE.name());
    securityEvent(userId, SecurityEvent.UNFREEZE);
  }

  UserStatus status(long userId) {
    return UserStatus.valueOf(user(userId).status());
  }
}
