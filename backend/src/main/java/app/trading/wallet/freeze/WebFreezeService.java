package app.trading.wallet.freeze;

import app.trading.account.api.RecoveryApi;
import app.trading.account.api.WithdrawPinApi;
import app.trading.common.captcha.Captchas;
import app.trading.common.config.ConfigKey;
import app.trading.common.config.SystemConfig;
import app.trading.common.error.BizException;
import app.trading.common.health.AlertProperties;
import app.trading.notification.api.MailApi;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 网页"申请冻结提现"（第 4 批 4c，需求文档 TG机器人-08 页第 2 节，TB-27）。不用登录（TG 丢了的人也能用），要图形验证码。
 * <ul>
 *   <li>要填：客户号 + 只有本人知道的一项——做过 KYC 的填姓名 + 证件号后 4 位；没做 KYC 的填最近一笔充值的金额和日期（UTC）。</li>
 *   <li><b>不管对不对都回同一句</b>（防止拿来猜 KYC 信息）；只有验证码不对单独提示（和账户信息无关）。</li>
 *   <li><b>失败的提交绝不影响正确的提交</b>：信息正确就冻结（来源 WEB_REQUEST，只能人工解冻），不受任何失败次数影响；失败只让<b>提交来源</b>
 *       （同一 IP 或同一浏览器标识）当天失败 10 次以后换更难的验证码，不锁目标账户。同一 IP 当天提交满 3 次以后也换更难的验证码。</li>
 *   <li>某个目标账户 24 小时内失败 5 次以上：只给告警邮箱发告警（每个账户每天一封），由人工判断要不要主动冻结。</li>
 *   <li>同一目标账户 7 天内只能被网页入口冻结 1 次；有进行中的找回申请、或在自助解冻等待期里不受限。超过的不冻结、发告警，回应一样。</li>
 *   <li>全平台 1 小时内成功冻结超过 {@code withdraw.freeze.platformAlertPerHour}（默认 10）个：发告警（每小时一封）。
 *       需求文档写的是"全平台暂停"，但同一节又写"信息正确的提交不受任何暂停影响"，两条矛盾；这里按"正确的照常冻结"做，超过只告警，交 Amos 确认。</li>
 * </ul>
 * IP 用 {@code request.getRemoteAddr()}（Nginx 传来的）。生产现在没开真实 IP（TB-37 现状），看到的都是网关地址，按 IP 的计数会把所有人算在一起
 * ——只影响验证码难度，不会挡住正确的提交。
 */
@Service
public class WebFreezeService {

  private static final Logger log = LoggerFactory.getLogger(WebFreezeService.class);

  static final String SOURCE = "WEB_REQUEST";
  static final int SOURCE_FAILS_FOR_HARD = 10;
  static final int IP_SUBMITS_FOR_HARD = 3;
  static final int TARGET_FAILS_FOR_ALERT = 5;
  static final Duration ONCE_PER = Duration.ofDays(7);
  static final String ACCEPTED = "已受理；信息正确的话会冻结提现并通知账户持有人。";

  /** 网页填的东西 */
  public record Form(String customerNo, String name, String docLast4, String depositAmount, String depositDate, String captchaId, String captcha) {}

  private final FreezeRequestMapper mapper;
  private final RecoveryApi recovery;
  private final WithdrawPinApi pins;
  private final Captchas captchas;
  private final SystemConfig config;
  private final MailApi mail;
  private final AlertProperties alerts;
  private final StringRedisTemplate redis;
  private final Clock clock;

  WebFreezeService(FreezeRequestMapper mapper, RecoveryApi recovery, WithdrawPinApi pins, Captchas captchas, SystemConfig config, MailApi mail,
      AlertProperties alerts, StringRedisTemplate redis, Clock clock) {
    this.mapper = mapper;
    this.recovery = recovery;
    this.pins = pins;
    this.captchas = captchas;
    this.config = config;
    this.mail = mail;
    this.alerts = alerts;
    this.redis = redis;
    this.clock = clock;
  }

  LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
  }

  /** 要一个验证码：这个来源当天失败 10 次以上、或这个 IP 当天提交满 3 次的给更难的 */
  public Captchas.Captcha captcha(String ip, String fingerprint) {
    return captchas.create(hard(ip, fingerprint));
  }

  boolean hard(String ip, String fingerprint) {
    LocalDateTime day = now().minusDays(1);
    if (mapper.countByIp(ip, "WRONG", day) >= SOURCE_FAILS_FOR_HARD || mapper.countByIp(ip, null, day) >= IP_SUBMITS_FOR_HARD) return true;
    return fingerprint != null && mapper.countByFingerprint(fingerprint, "WRONG", day) >= SOURCE_FAILS_FOR_HARD;
  }

  /** 提交：验证码不对抛 CAPTCHA_INVALID；其余不管对不对都返回同一句 */
  public String submit(String ip, String fingerprint, Form f) {
    String fp = fingerprint == null || !fingerprint.matches("[A-Za-z0-9_-]{8,64}") ? null : fingerprint;
    LocalDateTime now = now();
    Long target = parseNo(f.customerNo());
    if (!captchas.verify(f.captchaId(), f.captcha())) {
      mapper.insert(target, ip, fp, "CAPTCHA", now);
      throw new BizException("CAPTCHA_INVALID", "验证码不对或已过期，请换一张再试");
    }
    String result = target == null ? "WRONG" : decide(target, f, now);
    mapper.insert(target, ip, fp, result, now);
    if ("WRONG".equals(result) && target != null && mapper.countByTarget(target, "WRONG", now.minusDays(1)) >= TARGET_FAILS_FOR_ALERT) {
      alertOnce("target:" + target, Duration.ofDays(1), "网页申请冻结提现：客户 " + target + " 24 小时内信息不对的提交达到 " + TARGET_FAILS_FOR_ALERT + " 次",
          "Web withdrawal-freeze requests: " + TARGET_FAILS_FOR_ALERT + "+ failed submissions for customer " + target + " in 24 hours",
          "有人在反复猜这个客户的本人信息（每次提交都记下了 IP 和浏览器标识）。请判断要不要主动冻结这个客户的提现、联系客户。");
    }
    if ("FROZEN".equals(result)) {
      int limit = config.integer(ConfigKey.WITHDRAW_FREEZE_PLATFORM_ALERT_PER_HOUR, 10);
      int n = mapper.countFrozenSince(now.minusHours(1));
      if (n > limit) {
        alertOnce("platform", Duration.ofHours(1), "网页申请冻结提现：1 小时内成功冻结 " + n + " 个，超过 " + limit,
            "Web withdrawal-freeze requests: " + n + " freezes within an hour (limit " + limit + ")",
            "可能有人批量拿到了客户信息在冻结账户。信息正确的提交照常冻结（不暂停）；请检查提交记录、联系被冻结的客户。");
      }
    }
    return ACCEPTED;
  }

  /** 信息对不对、要不要冻结；返回记录用的结果 */
  private String decide(long target, Form f, LocalDateTime now) {
    RecoveryApi.IdentityCheck c = recovery.checkIdentity(target, f.name(), f.docLast4());
    boolean ok = switch (c) {
      case KYC_MATCH -> true;
      case NO_KYC -> depositMatches(target, f.depositAmount(), f.depositDate());
      default -> false;
    };
    if (!ok) return "WRONG";
    WithdrawPinApi.Status st = pins.status(target);
    if (st.frozenBy(SOURCE)) return "ALREADY";
    boolean selfUnfreezing = st.freezes().stream().anyMatch(x -> "BOT_SELF".equals(x.source()) && x.releaseAt() != null);
    if (!selfUnfreezing && !recovery.hasOpen(target) && mapper.countByTarget(target, "FROZEN", now.minus(ONCE_PER)) > 0) {
      alertOnce("limited:" + target, Duration.ofDays(1), "网页申请冻结提现：客户 " + target + " 7 天内已经被网页入口冻结过，这次没有冻结",
          "Web withdrawal-freeze request: customer " + target + " was already frozen via the web within 7 days; not frozen again",
          "信息是对的，但同一账户 7 天内只能被网页入口冻结 1 次（没有进行中的找回申请、也不在自助解冻等待期）。请判断要不要人工冻结、联系客户。");
      return "LIMITED";
    }
    pins.freezeWithdraw(target, SOURCE, "网页申请冻结提现（本人信息核对通过）");
    log.warn("客户 {} 的提现已按网页申请冻结", target);
    return "FROZEN";
  }

  private boolean depositMatches(long target, String amount, String date) {
    FreezeRequestMapper.LastDeposit d = mapper.lastDeposit(target);
    if (d == null || amount == null || date == null) return false;
    try {
      return new BigDecimal(amount.strip()).compareTo(d.amount()) == 0 && LocalDate.parse(date.strip()).equals(d.creditedAt().toLocalDate());
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static Long parseNo(String s) {
    if (s == null) return null;
    String v = s.strip().replaceFirst("^#", "");
    return v.matches("\\d{1,18}") ? Long.valueOf(v) : null;
  }

  /** 告警邮件（同一个键在 ttl 内只发一次；收件人为空只记日志） */
  private void alertOnce(String key, Duration ttl, String itemZh, String itemEn, String detail) {
    Boolean first = redis.opsForValue().setIfAbsent("fx:freeze-alert:" + key, "1", ttl);
    if (!Boolean.TRUE.equals(first)) return;
    log.warn("{}：{}", itemZh, detail);
    List<String> to = alerts.recipients();
    Map<String, String> p = Map.of("itemZh", itemZh, "itemEn", itemEn, "detail", detail, "since", now().toInstant(ZoneOffset.UTC).toString());
    for (String email : to) {
      try {
        mail.send(email, "OPS_ALERT", p);
      } catch (RuntimeException e) {
        log.error("冻结入口告警邮件写入发送任务失败", e);
      }
    }
  }
}
