package app.trading.telegram.bot;

import app.trading.common.health.AlertCheck;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 机器人告警（健康检查 {@code alerts.telegram}，不影响 {@code status}：Telegram 连不上不能让部署回滚）：
 * <ul>
 *   <li>收到 409（另一个程序在用同一个令牌收消息或设了 webhook）→ <b>立即</b> DOWN，提示令牌可能泄露（TB-24），之后 10 分钟内没再出现才恢复；</li>
 *   <li>401（令牌不对或被 BotFather 撤销）、令牌和用户名对不上 → 立即 DOWN；</li>
 *   <li>轮询连续失败超过 {@code unhealthy-after}（默认 10 分钟）→ DOWN；成功一次就恢复。</li>
 * </ul>
 * 没配令牌时不检查。详情里不出现令牌。
 */
public class TelegramHealth implements AlertCheck {

  private final TelegramProperties props;
  private final Clock clock;
  private volatile Instant failingSince;
  private volatile Instant lastConflict;
  private volatile String fatal;
  private volatile String lastError;

  public TelegramHealth(TelegramProperties props, Clock clock) {
    this.props = props;
    this.clock = clock;
  }

  /** 一次请求成功（收到 Telegram 的 2xx） */
  void ok() {
    failingSince = null;
    lastError = null;
  }

  void failed(String error) {
    if (failingSince == null) failingSince = clock.instant();
    lastError = props.scrub(error);
  }

  void conflict() {
    lastConflict = clock.instant();
    failed("409 Conflict");
  }

  /** 不会自己好的问题（令牌错、用户名对不上）：直到重启（重新填令牌会重启）都是 DOWN */
  void fatal(String reason) {
    fatal = props.scrub(reason);
  }

  @Override
  public List<Item> evaluate() {
    if (!props.configured()) return List.of();
    Instant now = clock.instant();
    String detail = null;
    if (fatal != null) detail = fatal;
    else if (lastConflict != null && Duration.between(lastConflict, now).compareTo(Duration.ofMinutes(10)) < 0)
      detail = "Telegram 返回 409：另一个程序在用同一个机器人令牌收消息（或设了 webhook），令牌可能泄露，请按 TB-24 在 BotFather 换令牌";
    else if (failingSince != null && Duration.between(failingSince, now).compareTo(props.unhealthyAfter()) >= 0)
      detail = "连不上 Telegram 已经 " + Duration.between(failingSince, now).toMinutes() + " 分钟（最近一次：" + lastError + "）";
    return List.of(new Item("telegram", "Telegram 机器人异常", "Telegram bot failing", detail == null, detail == null ? "正常" : detail));
  }
}
