package app.trading.account.auth;

import app.trading.account.api.TelegramEmergencyApi;
import app.trading.common.error.BizException;
import app.trading.common.health.AlertCheck;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 应急开关的状态和记录（第 5 批，TB-34）。状态 = 记录表最新一行；没有记录 = 关。
 * <ul>
 *   <li>登录接口每次都直接查库（{@link #onFresh()}）；登录方式接口、告警检查用内存里的值（每 5 秒从库里刷新一次——服务器终端改开关不经过应用）。</li>
 *   <li>应急登录的会话在查会话时另外要求"开关现在是开的"（AuthMapper.xml 的 findActiveSession），所以终端关上开关后，这类会话马上失效；
 *       经后台关上时还会在同一个事务里把它们标成作废。</li>
 *   <li>告警项 {@code telegram.emergency}：开着就是 DOWN（告警邮件只在变化时发，C103），关上恢复。</li>
 * </ul>
 */
@Component
class EmergencySwitch implements TelegramEmergencyApi, AlertCheck {

  private static final Logger log = LoggerFactory.getLogger(EmergencySwitch.class);

  static final String KIND = "EMERGENCY";

  private final AuthMapper mapper;
  private final Clock clock;
  private volatile boolean cached;

  EmergencySwitch(AuthMapper mapper, Clock clock) {
    this.mapper = mapper;
    this.clock = clock;
  }

  /** 内存里的值（最多晚 5 秒） */
  boolean on() {
    return cached;
  }

  /** 直接查库 */
  boolean onFresh() {
    Rows.EmergencyChange c = mapper.latestEmergency();
    boolean on = c != null && c.turnedOn();
    cached = on;
    return on;
  }

  @Scheduled(fixedDelay = 5_000, initialDelay = 0)
  void refresh() {
    try {
      onFresh();
    } catch (RuntimeException e) {
      log.warn("读应急开关状态失败：{}", e.toString());
    }
  }

  @Override
  public State state() {
    Rows.EmergencyChange c = mapper.latestEmergency();
    cached = c != null && c.turnedOn();
    return c == null ? new State(false, null, null, null) : new State(c.turnedOn(), c.createdAt(), c.actorName(), c.reason());
  }

  @Override
  public List<Change> history(int limit) {
    return mapper.emergencyHistory(Math.max(1, Math.min(limit, 200))).stream()
        .map(c -> new Change(c.id(), c.turnedOn(), c.actor(), c.actorName(), c.reason(), c.createdAt())).toList();
  }

  @Override
  @Transactional
  public void set(boolean on, String actor, String actorName, String reason) {
    Rows.EmergencyChange c = mapper.latestEmergencyForUpdate();
    boolean now = c != null && c.turnedOn();
    if (now == on) throw new BizException("EMERGENCY_UNCHANGED", on ? "应急开关已经是打开的" : "应急开关已经是关闭的");
    mapper.insertEmergencyLog(on, actor, cut(actorName, 64), cut(reason, 500), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    if (!on) {
      int n = mapper.revokeSessionsOfKind(KIND);
      log.warn("应急开关已关闭（{}），作废了 {} 个用 Telegram 登录的网页会话", actorName, n);
    } else {
      log.warn("应急开关已打开（{}）：网页登录页出现「用 Telegram 登录」", actorName);
    }
    cached = on;
  }

  @Override
  public List<Item> evaluate() {
    boolean on = cached;
    return List.of(new Item("telegram.emergency", "Telegram 应急开关已打开", "Telegram emergency switch is on", !on,
        on ? "应急开关开着：网页登录页有「用 Telegram 登录」（算安全事件，24 小时不能提现）。机器人恢复后请在后台\"应急开关\"关上，或在服务器上运行 sudo trading set TR_TG_EMERGENCY_LOGIN off"
            : "关闭"));
  }

  private static String cut(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max);
  }
}
