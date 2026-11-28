package app.trading.account.auth;

import app.trading.account.api.SecurityEvent;
import app.trading.account.api.TelegramAccountApi.TgAccount;
import app.trading.account.api.UserStatus;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 从机器人进入网页（TG 机器人第 2 批，技术方案 TG机器人-12 页）：
 * <ul>
 *   <li><b>一键登录（login_url，默认）</b>：Telegram 把签了名的身份加在网址参数里。验 hash（除 hash 和我们自己的参数外按字母排序拼成
 *       {@code key=value}、换行连接，用机器人令牌的 SHA-256 作密钥做 HMAC-SHA256，<b>恒定时间比较</b>）；{@code auth_date} 只认 60 秒以内的；
 *       hash 只能用一次（"已用 hash"表的主键，INSERT 成功才算，原子）；只认 {@code id}；冻结、授权后有安全事件的拒绝。</li>
 *   <li><b>一次性链接（退路，{@code FX_TG_LOGIN_MODE=link}）</b>：机器人现做随机令牌（库里只存哈希），60 秒、只能用一次
 *       （{@code UPDATE … WHERE used_at IS NULL}，影响 1 行才算）。</li>
 *   <li>通过后建<b>机器人会话</b>（闲置 30 分钟、最长 12 小时）。验收反馈 B：不再给机器人发"刚在浏览器里登录"（Amos 嫌每次都发太烦），
 *       「退出所有网页会话」是机器人主菜单里的常驻按钮。</li>
 * </ul>
 * <b>日志里不出现身份参数</b>（id、hash、名字、令牌）：失败只记原因。
 */
@Service
class TelegramWebLogin {

  private static final Logger log = LoggerFactory.getLogger(TelegramWebLogin.class);

  /** 登录后打开的页面参数名（我们自己加的，不参与 hash） */
  static final String NEXT = "tr_next";
  static final Duration MAX_AGE = Duration.ofSeconds(60);
  /** 手机时间可能比服务器快一点：auth_date 最多比现在晚这么多 */
  static final Duration FUTURE_SKEW = Duration.ofSeconds(30);
  static final Duration LINK_TTL = Duration.ofSeconds(60);
  private static final Pattern SAFE_PATH = Pattern.compile("/[A-Za-z0-9/_\\-.]{0,100}(\\?[A-Za-z0-9=&_\\-.%]{0,100})?");

  /** 结果：OK 或失败原因（提示页按它显示文案） */
  enum Outcome {
    OK, REFUSED, INVALID, EXPIRED, USED, NO_ACCOUNT, FROZEN, REAUTH, DISABLED
  }

  record Result(Outcome outcome, String token, String next) {
    static Result fail(Outcome o) {
      return new Result(o, null, null);
    }
  }

  private final AuthMapper mapper;
  private final TelegramAccounts accounts;
  private final AuthService auth;
  private final SessionService sessions;
  private final Clock clock;
  private final String botToken;
  private final String mode;

  TelegramWebLogin(AuthMapper mapper, TelegramAccounts accounts, AuthService auth, SessionService sessions, Clock clock,
      @Value("${trading.telegram.token:}") String botToken, @Value("${trading.telegram.login-mode:link}") String mode) {
    this.mapper = mapper;
    this.accounts = accounts;
    this.auth = auth;
    this.sessions = sessions;
    this.clock = clock;
    this.botToken = botToken == null ? "" : botToken.strip();
    this.mode = mode == null ? "link" : mode.strip();
  }

  boolean linkMode() {
    return "link".equalsIgnoreCase(mode);
  }

  /** 登录后打开的页面：只认站内路径，其他一律回首页 */
  static String safeNext(String next) {
    if (next == null || next.startsWith("//") || !SAFE_PATH.matcher(next).matches()) return "/";
    return next;
  }

  // ---------- 一键登录 ----------

  /**
   * @param params 网址上的全部参数（Telegram 加的 + 我们的 {@link #NEXT}）
   */
  @Transactional
  Result loginUrl(Map<String, String> params, String ip, String userAgent) {
    if (linkMode() || botToken.isEmpty()) return Result.fail(Outcome.DISABLED);
    String hash = params.get("hash");
    String id = params.get("id");
    if (hash == null || id == null) return Result.fail(Outcome.REFUSED); // 客户在确认框点了取消：打开的是不带身份的网址
    if (!validHash(params, hash)) return fail(Outcome.INVALID);
    long authDate;
    long tgId;
    try {
      authDate = Long.parseLong(params.getOrDefault("auth_date", ""));
      tgId = Long.parseLong(id);
    } catch (NumberFormatException e) {
      return fail(Outcome.INVALID);
    }
    Instant now = clock.instant();
    Instant at = Instant.ofEpochSecond(authDate);
    if (at.isBefore(now.minus(MAX_AGE)) || at.isAfter(now.plus(FUTURE_SKEW))) return fail(Outcome.EXPIRED);
    try {
      mapper.insertLoginHash(hash.toLowerCase(), LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    } catch (DuplicateKeyException e) {
      return fail(Outcome.USED);
    }
    Optional<TgAccount> acc = accounts.byTelegramId(tgId);
    if (acc.isEmpty()) return fail(Outcome.NO_ACCOUNT);
    return session(acc.get(), safeNext(params.get(NEXT)), ip, userAgent);
  }

  /** Telegram 登录数据的 hash：机器人令牌的 SHA-256 作密钥，HMAC-SHA256(data-check-string)，十六进制；恒定时间比较 */
  boolean validHash(Map<String, String> params, String hash) {
    return validHash(params, hash, botToken);
  }

  /** 同上，指定机器人令牌（第 5 批应急登录用另一个只管登录的机器人，见 {@link EmergencyLogin}）；令牌为空一律不通过 */
  static boolean validHash(Map<String, String> params, String hash, String botToken) {
    if (botToken == null || botToken.isEmpty() || hash == null) return false;
    TreeMap<String, String> data = new TreeMap<>(params);
    data.remove("hash");
    data.remove(NEXT);
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> e : data.entrySet()) {
      if (!sb.isEmpty()) sb.append('\n');
      sb.append(e.getKey()).append('=').append(e.getValue());
    }
    try {
      byte[] key = MessageDigest.getInstance("SHA-256").digest(botToken.getBytes(StandardCharsets.UTF_8));
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      byte[] expected = HexFormat.of().formatHex(mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.US_ASCII);
      return MessageDigest.isEqual(expected, hash.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---------- 一次性链接（退路） ----------

  /** 机器人现做一条一次性链接的令牌（原值只在这里出现一次） */
  @Transactional
  String createLink(long userId, String next) {
    String token = Secrets.newToken();
    LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    mapper.insertLinkToken(Secrets.sha256(token), userId, safeNext(next), now.plus(LINK_TTL), now);
    return token;
  }

  /**
   * 一套菜单的一组令牌（验收反馈：每个进网页的按钮都带自己的令牌，点哪个都能登录）：同一个组，用掉一个，同组别的一起作废。
   * 返回 路径 → 令牌原值（原值只在这里出现一次）
   */
  @Transactional
  java.util.Map<String, String> createLinkGroup(long userId, java.util.List<String> paths) {
    String group = Secrets.newToken().replaceAll("[^A-Za-z0-9]", "");
    group = group.length() > 32 ? group.substring(0, 32) : group;
    LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
    for (String p : paths) {
      String token = Secrets.newToken();
      mapper.insertGroupLinkToken(Secrets.sha256(token), userId, group, safeNext(p), now.plus(LINK_TTL), now);
      out.put(p, token);
    }
    return out;
  }

  @Transactional
  Result link(String token, String ip, String userAgent) {
    return link(token, ip, userAgent, null);
  }

  /**
   * 一次性令牌换会话。existingSession：浏览器里已有的会话令牌（Cookie）——还有效、是同一个客户的机器人会话时<b>沿用它</b>，
   * 不再建新会话（#51 审查：不让「当前网页会话」越堆越多）；令牌和同组的照样作废
   */
  @Transactional
  Result link(String token, String ip, String userAgent, String existingSession) {
    if (!linkMode()) return Result.fail(Outcome.DISABLED);
    if (token == null || token.isBlank() || token.length() > 100) return fail(Outcome.INVALID);
    String h = Secrets.sha256(token.strip());
    LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    // #51 审查：同组两个令牌同时用——先按固定顺序锁住整组再标记，第二个排队后看到已用，回"过期"（不死锁）
    String group = mapper.linkTokenGroup(h);
    if (group != null) mapper.lockLinkTokenGroup(group);
    if (mapper.useLinkToken(h, now) != 1) return fail(Outcome.EXPIRED); // 不存在、过期、用过都一样回答
    // 同一套菜单里的别的令牌一起作废（没用的不能留着以后再用）
    if (group != null) mapper.burnLinkTokenGroup(group, now);
    AuthMapper.LinkToken t = mapper.linkToken(h);
    Optional<TgAccount> acc = accounts.byUserId(t.userId());
    if (acc.isEmpty()) return fail(Outcome.NO_ACCOUNT);
    if (existingSession != null && !existingSession.isBlank()) {
      Rows.SessionRow s = sessions.resolveSession(existingSession, userAgent, true);
      if (s != null && "BOT".equals(s.kind()) && s.userId() == t.userId()) {
        if (auth.status(t.userId()) == UserStatus.FROZEN) return fail(Outcome.FROZEN);
        if (auth.lastSecurityEventSince(t.userId(), acc.get().authorizedAt()).isPresent()) return fail(Outcome.REAUTH);
        return new Result(Outcome.OK, existingSession, t.nextPath());
      }
    }
    return session(acc.get(), t.nextPath(), ip, userAgent);
  }

  // ---------- 共用 ----------

  private Result session(TgAccount a, String next, String ip, String userAgent) {
    if (auth.status(a.userId()) == UserStatus.FROZEN) return fail(Outcome.FROZEN);
    Optional<SecurityEvent> ev = auth.lastSecurityEventSince(a.userId(), a.authorizedAt());
    if (ev.isPresent()) return fail(Outcome.REAUTH);
    String token = sessions.createBot(a.userId(), ip, userAgent);
    // 验收反馈 B（Amos：「每次使用后都发，太烦，不要发这个」）：从机器人进网页不再发"刚在浏览器里登录"；
    // 「退出所有网页会话」改成机器人主菜单里的常驻按钮。应急开关下的网页登录（安全事件）照旧发 TG_EMERGENCY_LOGIN（EmergencyLogin）
    return new Result(Outcome.OK, token, next);
  }

  private static Result fail(Outcome o) {
    log.info("从机器人进入网页没有成功：{}", o); // 只记原因，不记身份参数
    return Result.fail(o);
  }

  /** 设备大致信息：系统 + 浏览器（从 User-Agent 粗略判断） */
  static String device(String ua) {
    if (ua == null || ua.isBlank()) return "未知设备";
    String os = ua.contains("iPhone") ? "iPhone" : ua.contains("iPad") ? "iPad" : ua.contains("Android") ? "安卓" : ua.contains("Windows") ? "Windows"
        : ua.contains("Mac OS X") || ua.contains("Macintosh") ? "Mac" : ua.contains("Linux") ? "Linux" : "未知系统";
    String browser = ua.contains("Telegram") ? "Telegram 内置浏览器" : ua.contains("Edg/") ? "Edge" : ua.contains("Firefox/") ? "Firefox"
        : ua.contains("Chrome/") ? "Chrome" : ua.contains("Safari/") ? "Safari" : "浏览器";
    return os + " · " + browser;
  }

  /** IP 只露前面一部分：IPv4 前三段，IPv6 前三组 */
  static String maskIp(String ip) {
    if (ip == null || ip.isBlank()) return "未知";
    if (ip.contains(":")) {
      String[] p = ip.split(":");
      return p.length >= 3 ? p[0] + ":" + p[1] + ":" + p[2] + ":…" : "…";
    }
    int i = ip.lastIndexOf('.');
    return i > 0 ? ip.substring(0, i) + ".*" : "…";
  }

  /** 已用 hash 保留 24 小时、一次性链接过期 1 天后清理（每小时一次） */
  @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
  void cleanup() {
    try {
      LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
      mapper.deleteLoginHashesBefore(now.minusHours(24));
      mapper.deleteLinkTokensBefore(now.minusDays(1));
    } catch (RuntimeException e) {
      log.warn("清理一键登录记录失败：{}", e.toString());
    }
  }
}
