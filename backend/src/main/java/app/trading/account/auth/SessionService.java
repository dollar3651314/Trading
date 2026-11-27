package app.trading.account.auth;

import app.trading.account.auth.Rows.SessionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录会话：浏览器里是 HttpOnly Cookie 中的随机令牌，库里只存哈希。
 * 每次请求都查库，作废（登出、改密、重置、冻结）立即生效（AC-03、AC-04 要求 5 秒内）。
 * 网页会话 72 小时没有访问就过期；机器人会话（TG 机器人第 2 批，从机器人一键登录）闲置 30 分钟过期、最长 12 小时。
 * 有访问时每分钟最多续期一次。两种会话在同一张表，冻结、改密等作废时一起作废。
 */
@Service
class SessionService {

  private final AuthMapper mapper;
  private final AuthProperties props;

  SessionService(AuthMapper mapper, AuthProperties props) {
    this.mapper = mapper;
    this.props = props;
  }

  /** 新建会话，返回令牌原值（只在这里出现一次，写进 Cookie） */
  @Transactional
  String create(long userId, String ip, String userAgent) {
    String token = Secrets.newToken();
    mapper.insertSession(userId, Secrets.sha256(token), ip, truncate(userAgent, 255), props.sessionTtlHours());
    return token;
  }

  /** 新建机器人会话（从机器人一键登录），返回令牌原值 */
  @Transactional
  String createBot(long userId, String ip, String userAgent) {
    String token = Secrets.newToken();
    mapper.insertBotSession(userId, Secrets.sha256(token), ip, truncate(userAgent, 255), props.botSessionIdleMinutes(), props.botSessionMaxHours());
    return token;
  }

  /** 有效时返回用户 ID，否则返回 null */
  @Transactional
  Long resolve(String token) {
    if (token == null || token.isBlank()) return null;
    SessionRow s = mapper.findActiveSession(Secrets.sha256(token));
    if (s == null) return null;
    if (s.idleSeconds() >= 60) mapper.extendSession(s.id(), props.sessionTtlHours(), props.botSessionIdleMinutes());
    return s.userId();
  }

  @Transactional
  void revoke(String token) {
    if (token != null && !token.isBlank()) mapper.revokeSession(Secrets.sha256(token));
  }

  /** 作废该用户的所有会话；keepToken 不为空时保留这一个（修改密码时保留当前设备） */
  @Transactional
  void revokeAll(long userId, String keepToken) {
    mapper.revokeUserSessions(userId, keepToken == null ? null : Secrets.sha256(keepToken));
  }

  private static String truncate(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }
}
