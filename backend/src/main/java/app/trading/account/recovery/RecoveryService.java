package app.trading.account.recovery;

import app.trading.account.api.AccountRecovered;
import app.trading.account.api.RecoveryApi;
import app.trading.account.api.SecurityEvent;
import app.trading.account.api.TelegramAccountApi;
import app.trading.account.api.WithdrawPinApi;
import app.trading.account.auth.AuthAccess;
import app.trading.common.error.BizException;
import app.trading.common.health.AlertProperties;
import app.trading.notification.api.MailApi;
import app.trading.notification.api.NotifyApi;
import app.trading.notification.api.TelegramSender;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link RecoveryApi} 的实现（第 4 批 4b）。规则见接口说明和需求文档 TG机器人-08 页第 1、3 节。
 * 冻结用提现冻结的两个来源：{@value #SOURCE_RECOVERY}（反对期过了的证明阶段，冻结到申请结束）、{@value #SOURCE_DISPUTE}（"不是我"之后没输 / 输错提现密码或选了直接冻结）；
 * 申请结束（执行、驳回、撤销）时只解除这两个来源的冻结，别的来源（网页申请冻结、提现密码锁 2 次等）不动。
 */
@Service
class RecoveryService implements RecoveryApi {

  private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

  static final String REBIND = "REBIND";
  static final String PIN_RESET = "PIN_RESET";
  static final String SOURCE_RECOVERY = "RECOVERY";
  static final String SOURCE_DISPUTE = "DISPUTE";
  static final String SOURCE_PIN_LOCKS = "PIN_LOCKS";
  static final Set<String> OPEN = Set.of("OBJECTION", "PROOF", "APPROVED");

  /** 反对期 48 小时；换绑批准后 24 小时执行；人工重设提现密码批准后 48 小时生效（08 页第 1、3 节） */
  static final Duration OBJECTION = Duration.ofHours(48);
  static final Duration REBIND_WAIT = Duration.ofHours(24);
  static final Duration PIN_RESET_WAIT = Duration.ofHours(48);
  /** 点了"不是我"多久没输对提现密码就冻结 */
  static final Duration DISPUTE_PIN_GRACE = Duration.ofMinutes(30);
  /** 同一个新 TG 每天最多提交 3 次（信息不对的也算）；同一目标账户 7 天内 1 份 */
  static final int ATTEMPTS_PER_DAY = 3;
  static final Duration TARGET_WINDOW = Duration.ofDays(7);
  /** 驳回后封申请人的 TG：长期 */
  static final LocalDateTime BAN_FOREVER = LocalDateTime.of(2099, 1, 1, 0, 0);
  static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");


  private final RecoveryMapper mapper;
  private final TelegramAccountApi telegram;
  private final WithdrawPinApi pins;
  private final AuthAccess auth;
  private final NotifyApi notify;
  private final MailApi mail;
  private final AlertProperties alerts;
  private final ObjectProvider<TelegramSender> sender;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final TransactionTemplate tx;

  RecoveryService(RecoveryMapper mapper, TelegramAccountApi telegram, WithdrawPinApi pins, AuthAccess auth, NotifyApi notify, MailApi mail,
      AlertProperties alerts, ObjectProvider<TelegramSender> sender, ApplicationEventPublisher events, Clock clock, PlatformTransactionManager txm) {
    this.mapper = mapper;
    this.telegram = telegram;
    this.pins = pins;
    this.auth = auth;
    this.notify = notify;
    this.mail = mail;
    this.alerts = alerts;
    this.sender = sender;
    this.events = events;
    this.clock = clock;
    this.tx = new TransactionTemplate(txm);
  }

  LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
  }

  // ---------- 申请 ----------

  @Override
  public ApplyResult applyRebind(long applicantTgId, String applicantUsername, long customerNo, String name, String docLast4) {
    LocalDateTime now = now();
    if (mapper.banned("TG", String.valueOf(applicantTgId), now) > 0) return ApplyResult.BANNED;
    if (mapper.countAttemptsSince(applicantTgId, now.minusDays(1)) >= ATTEMPTS_PER_DAY) return ApplyResult.TOO_FREQUENT;
    mapper.insertAttempt(applicantTgId, now);
    // 下面不管哪一步不对都回同一句（防止拿来猜客户号、姓名、证件号）
    if (telegram.byTelegramId(applicantTgId).isPresent()) return ApplyResult.ACCEPTED;
    RecoveryMapper.Identity id = mapper.identity(customerNo);
    if (id == null || telegram.byUserId(customerNo).isEmpty() || !"APPROVED".equals(id.kycStatus())) return ApplyResult.ACCEPTED;
    if (!lastFourMatches(id.docNumber(), docLast4) || !nameMatches(id, name)) return ApplyResult.ACCEPTED;
    tx.executeWithoutResult(st -> {
      // 同一目标账户：同时只能 1 份进行中、7 天内 1 份（争议转人工的是同一份继续，不另开）。
      // 先查后写、没有加锁：依赖机器人的更新是单线程逐条处理的（TelegramBot 长轮询一个线程，UpdateHandler.consume 逐条处理），
      // 同一时间不会有两份申请同时走到这里。以后改成多线程或多实例处理更新时，要在这里锁住目标客户（例如 SELECT … FOR UPDATE account_user）
      // 或者加唯一约束兜底
      if (mapper.openCount(customerNo) > 0 || mapper.countSince(customerNo, REBIND, now.minus(TARGET_WINDOW)) > 0) return;
      RecoveryMapper.Row r = new RecoveryMapper.Row();
      r.kind = REBIND;
      r.userId = customerNo;
      r.applicantTgId = applicantTgId;
      r.applicantTgUsername = cut(applicantUsername, 64);
      r.status = "OBJECTION";
      r.objectionEndsAt = now.plus(OBJECTION);
      r.createdAt = now;
      mapper.insert(r);
      mapper.insertEvent(r.id, "APPLY", "tg:" + applicantTgId, null, now);
      notify.toUser(customerNo, "RECOVERY_REQUESTED", Map.of("id", String.valueOf(r.id)));
    });
    return ApplyResult.ACCEPTED;
  }

  @Override
  public long applyPinReset(long userId) {
    return tx.execute(st -> {
      if (telegram.byUserId(userId).isEmpty()) throw new BizException("NOT_TG_ACCOUNT", "只有 Telegram 账户可以申请人工重设提现密码");
      WithdrawPinApi.Status st0 = pins.status(userId);
      if (!st0.set()) throw new BizException("PIN_NOT_SET", "还没有设置提现密码");
      // 有邮箱的用邮箱验证码重设；30 天内被锁 2 次冻结的也走人工（08 页第 3 节）
      boolean hasEmail = auth.email(userId).isPresent();
      if (hasEmail && !st0.frozenBy(SOURCE_PIN_LOCKS)) throw new BizException("HAS_EMAIL", "绑了邮箱的请用邮箱验证码重设");
      if (mapper.openCount(userId) > 0) throw new BizException("RECOVERY_OPEN", "已经有一份进行中的申请");
      LocalDateTime now = now();
      RecoveryMapper.Row r = new RecoveryMapper.Row();
      r.kind = PIN_RESET;
      r.userId = userId;
      r.status = "PROOF";
      r.createdAt = now;
      mapper.insert(r);
      mapper.insertEvent(r.id, "APPLY", "user:" + userId, null, now);
      notify.toUser(userId, "PIN_RESET_REQUESTED", Map.of("id", String.valueOf(r.id)));
      return r.id;
    });
  }

  // ---------- 原 TG（被找回的账户）的操作 ----------

  @Override
  public String object(long userId, long recoveryId) {
    String[] out = new String[1];
    tx.executeWithoutResult(st -> {
      RecoveryMapper.Row r = own(userId, recoveryId);
      if (r == null || !OPEN.contains(r.status)) {
        out[0] = "这份申请已经结束了。";
        return;
      }
      if ("APPROVED".equals(r.status) || PIN_RESET.equals(r.kind)) {
        out[0] = null; // 已批准的走撤销规则
        return;
      }
      LocalDateTime now = now();
      if (mapper.markDisputed(r.id, now) == 1) {
        if ("OBJECTION".equals(r.status)) mapper.transit(r.id, "OBJECTION", "PROOF");
        mapper.insertEvent(r.id, "OBJECT", "user:" + userId, null, now);
      }
      out[0] = "已记下：这份找回申请转人工严格核对，没有你的确认不会换绑。\n"
          + "为了先挡住可能的盗号：请在 30 分钟内用下面的「输提现密码」在网页输一次提现密码——输对就不冻结提现；不输或输错会冻结提现，直到人工处理完。"
          + "也可以直接点「不输，直接冻结提现」。";
    });
    return out[0] == null ? revokeByOwner(userId, recoveryId) : out[0];
  }

  @Override
  public String freezeNow(long userId, long recoveryId) {
    String[] out = new String[1];
    tx.executeWithoutResult(st -> {
      RecoveryMapper.Row r = own(userId, recoveryId);
      if (r == null || !OPEN.contains(r.status) || !REBIND.equals(r.kind)) {
        out[0] = "这份申请已经结束了。";
        return;
      }
      LocalDateTime now = now();
      if (mapper.markDisputed(r.id, now) == 1 && "OBJECTION".equals(r.status)) mapper.transit(r.id, "OBJECTION", "PROOF");
      disputeFreeze(r, "user:" + userId, "客户选了直接冻结");
      out[0] = "已冻结提现（交易照常），直到人工处理完这份找回申请。";
    });
    return out[0];
  }

  @Override
  public void objectWithPin(long userId, long recoveryId, WithdrawPinApi.PinInput pin) {
    RecoveryMapper.Row r = mapper.byId(recoveryId);
    if (r == null || r.userId != userId || !REBIND.equals(r.kind) || !OPEN.contains(r.status)) throw new BizException("NOT_FOUND", "申请不存在或已经结束");
    if (!r.disputed) throw new BizException("RECOVERY_NOT_DISPUTED", "请先在机器人里点「不是我」");
    if (r.disputePinOk) return;
    try {
      pins.verifyIdentity(userId, pin);
    } catch (BizException e) {
      if (!"PIN_KEYPAD_EXPIRED".equals(e.code())) {
        tx.executeWithoutResult(st -> {
          RecoveryMapper.Row l = mapper.lock(recoveryId);
          mapper.insertEvent(l.id, "PIN_WRONG", "user:" + userId, e.code(), now());
          disputeFreeze(l, "user:" + userId, "点了不是我以后提现密码没输对");
        });
      }
      throw e;
    }
    tx.executeWithoutResult(st -> {
      RecoveryMapper.Row l = mapper.lock(recoveryId);
      mapper.markDisputePinOk(l.id);
      mapper.insertEvent(l.id, "PIN_OK", "user:" + userId, null, now());
      // 输对：不冻结；之前因为争议冻结的解除（证明阶段的 RECOVERY 冻结照旧）
      if (l.disputeFrozen) pins.releaseFreeze(userId, SOURCE_DISPUTE);
    });
  }

  /**
   * 原 TG（或本人）点「撤销」（4b 审查 🔴R1）：
   * <ul>
   *   <li>没邮箱人工重设提现密码（PIN_RESET）：申请人就是本人，直接撤销。</li>
   *   <li>换绑（REBIND）在反对期：按"不是我"处理（转争议、加严人工），不关申请——反对期只能走"不是我"。</li>
   *   <li>换绑在证明阶段或已批准：<b>不管有没有争议，撤销都转 Amos 决定</b>，记撤销请求、暂停批准和执行，证明阶段的 RECOVERY 冻结保留，
   *       发告警邮件。防止占着原 TG 的人在反对期里不出声、等批准后一撤销就把真原主永远挡在外面、顺带解冻提现。
   *       需求文档 08 页"正常路径原 TG 或邮箱可直接撤销"这里<b>故意更严</b>，等 Amos 确认。
   *       Amos 选过一次"维持"以后，再撤销只记经过（REVOKE_AFTER_KEEP）、发告警，不再暂停。</li>
   * </ul>
   */
  @Override
  public String revokeByOwner(long userId, long recoveryId) {
    String[] out = new String[1];
    Runnable[] after = new Runnable[1];
    tx.executeWithoutResult(st -> {
      RecoveryMapper.Row r = own(userId, recoveryId);
      if (r == null || !OPEN.contains(r.status)) {
        out[0] = "这份申请已经结束了。";
        return;
      }
      LocalDateTime now = now();
      if (PIN_RESET.equals(r.kind)) {
        close(r, "CANCELLED", "ORIGINAL_TG", "本人撤销");
        out[0] = "已撤销这份申请。";
        return;
      }
      if ("OBJECTION".equals(r.status)) {
        out[0] = null; // 反对期：按"不是我"处理
        return;
      }
      if (r.keptAt != null) {
        // 4b 复审：Amos 已经视频核对、选过一次"维持"，之后的撤销只记经过、发告警，不再暂停批准和执行（防止占着原 TG 的人反复撤销把申请拖住）
        mapper.insertEvent(r.id, "REVOKE_AFTER_KEEP", "user:" + userId, null, now);
        after[0] = () -> alert("OPS_RECOVERY_REVOKE_REQ", Map.of("id", String.valueOf(r.id), "customerId", String.valueOf(r.userId), "who",
            "原 Telegram（你已经维持过一次，这次不再暂停）", "whoEn", "the original Telegram account (you already kept it once; this does not pause it)"));
        out[0] = "已记下你的撤销并通知审核员。审核员已经核对并维持过这份申请，这次撤销不会暂停换绑；如果你认为账户被盗，请联系客服。";
        return;
      }
      if (r.revokeRequestedBy == null) {
        mapper.requestRevoke(r.id, "ORIGINAL_TG", now);
        mapper.insertEvent(r.id, "REVOKE_REQUEST", "user:" + userId, null, now);
        after[0] = () -> alert("OPS_RECOVERY_REVOKE_REQ", Map.of("id", String.valueOf(r.id), "customerId", String.valueOf(r.userId), "who", "原 Telegram ",
            "whoEn", "the original Telegram account"));
      }
      out[0] = "已收到撤销请求。按规则换绑申请的撤销要由审核员视频核对后决定；决定之前不会批准、也不会执行换绑，提现冻结保持不变。";
    });
    if (after[0] != null) after[0].run();
    return out[0] == null ? object(userId, recoveryId) : out[0];
  }

  // ---------- 第 4 批 4c ----------

  @Override
  public IdentityCheck checkIdentity(long customerNo, String name, String docLast4) {
    RecoveryMapper.Identity id = mapper.identity(customerNo);
    if (id == null) return IdentityCheck.NOT_FOUND;
    if (!"APPROVED".equals(id.kycStatus())) return IdentityCheck.NO_KYC;
    return lastFourMatches(id.docNumber(), docLast4) && nameMatches(id, name) ? IdentityCheck.KYC_MATCH : IdentityCheck.KYC_MISMATCH;
  }

  @Override
  public boolean hasOpen(long userId) {
    return mapper.openCount(userId) > 0;
  }

  @Override
  public TelegramChange adminChangeTelegram(long userId, Long newTelegramId, String newUsername, long adminId, String reason) {
    return tx.execute(st -> {
      Long old = telegram.byUserId(userId).map(TelegramAccountApi.TgAccount::telegramId).orElse(null);
      LocalDateTime now = now();
      if (newTelegramId == null) {
        if (old == null) throw new BizException("NOT_TG_ACCOUNT", "这个客户没有 Telegram 关联");
        // 解除：马上生效（不给任何人新的访问权）；安全事件作废全部网页会话（含 BOT 会话）后再删关联
        auth.securityEvent(userId, SecurityEvent.REBIND);
        telegram.unlink(userId);
        pins.freezeWithdraw(userId, "ADMIN", "后台解除了 Telegram 关联");
        notify.toUser(userId, "TELEGRAM_LINK_CHANGED", Map.of("userId", String.valueOf(userId), "actionZh", "解除", "actionEn", "removed"));
        long o = old;
        toApplicant(o, "你的 Telegram 已经不再关联客户号 #" + userId + "（客服操作）。如果不是你本人申请的，请马上联系客服。");
        return new TelegramChange(old, null, null);
      }
      var other = telegram.byTelegramId(newTelegramId);
      if (other.isPresent()) throw new BizException("TELEGRAM_TAKEN", other.get().userId() == userId ? "已经关联的就是这个 Telegram" : "这个 Telegram 已经关联了别的客户");
      if (mapper.openCount(userId) > 0) throw new BizException("RECOVERY_OPEN", "这个客户已经有一份进行中的找回申请，请先在\"找回申请\"里处理完");
      // 更换：建一份已批准的换绑申请，24 小时执行等待期后执行；原 TG 的撤销按 4b 规则转 Amos
      RecoveryMapper.Row r = new RecoveryMapper.Row();
      r.kind = REBIND;
      r.userId = userId;
      r.applicantTgId = newTelegramId;
      r.applicantTgUsername = cut(newUsername, 64);
      r.status = "PROOF";
      r.createdAt = now;
      mapper.insert(r);
      LocalDateTime at = now.plus(REBIND_WAIT);
      String note = "后台更换 Telegram 关联：" + (reason == null ? "" : reason);
      mapper.insertEvent(r.id, "APPLY", "admin:" + adminId, note, now);
      mapper.approve(r.id, adminId, cut(note, 500), now, at);
      mapper.insertEvent(r.id, "APPROVE", "admin:" + adminId, cut(note, 500), now);
      pins.freezeWithdraw(userId, "ADMIN", "后台更换 Telegram 关联（找回申请 #" + r.id + "）");
      notify.toUser(userId, "RECOVERY_APPROVED", Map.of("id", String.valueOf(r.id), "executeAt", at.format(TIME)));
      toApplicant(newTelegramId, "客服已安排把客户号 #" + userId + " 关联到这个 Telegram，将在 " + at.format(TIME) + " 生效。生效前原账户持有人还可以提出撤销。");
      return new TelegramChange(old, r.id, at);
    });
  }

  // ---------- 后台 ----------

  @Override
  public List<View> list(String status) {
    return mapper.list(status == null || status.isBlank() ? null : status.toUpperCase(Locale.ROOT)).stream().map(RecoveryService::view).toList();
  }

  @Override
  public Optional<Detail> detail(long id) {
    RecoveryMapper.Row r = mapper.byId(id);
    return r == null ? Optional.empty() : Optional.of(new Detail(view(r), mapper.events(id)));
  }

  @Override
  public List<View> mine(long userId) {
    return mapper.ofUser(userId).stream().map(RecoveryService::view).toList();
  }

  @Override
  public LocalDateTime approve(long id, long adminId, String proofNote) {
    String note = proofNote == null ? "" : proofNote.strip();
    if (note.isEmpty()) throw new BizException("PROOF_NOTE_REQUIRED", "要写证明核对记录（来源地址 / 视频共享 App / P2P 等）");
    return tx.execute(st -> {
      RecoveryMapper.Row r = lockOrThrow(id);
      if ("OBJECTION".equals(r.status)) throw new BizException("RECOVERY_NOT_READY", "还在 48 小时反对期里，不能批准");
      if (!"PROOF".equals(r.status)) throw new BizException("RECOVERY_NOT_PENDING", "只能批准待审核的申请");
      if (r.revokeRequestedBy != null) throw new BizException("RECOVERY_REVOKE_PENDING", "原 TG 请求了撤销：请先视频核对，选维持或撤销");
      LocalDateTime now = now();
      LocalDateTime at = now.plus(REBIND.equals(r.kind) ? REBIND_WAIT : PIN_RESET_WAIT);
      mapper.approve(r.id, adminId, cut(note, 500), now, at);
      mapper.insertEvent(r.id, "APPROVE", "admin:" + adminId, cut(note, 500), now);
      notify.toUser(r.userId, REBIND.equals(r.kind) ? "RECOVERY_APPROVED" : "PIN_RESET_APPROVED",
          Map.of("id", String.valueOf(r.id), "executeAt", at.format(TIME)));
      if (r.applicantTgId != null) toApplicant(r.applicantTgId, "你的找回申请 #" + r.id + " 已经人工批准，将在 " + at.format(TIME) + " 执行换绑。执行前原账户持有人还可以提出撤销。");
      return at;
    });
  }

  @Override
  public void reject(long id, long adminId, String reason) {
    tx.executeWithoutResult(st -> {
      RecoveryMapper.Row r = lockOrThrow(id);
      if (!"OBJECTION".equals(r.status) && !"PROOF".equals(r.status)) throw new BizException("RECOVERY_NOT_PENDING", "只能驳回还没批准的申请；已批准的用撤销");
      LocalDateTime now = now();
      close(r, "REJECTED", "admin:" + adminId, reason);
      if (r.applicantTgId != null) {
        // 驳回：封申请人用过的新 TG（长期）；IP 7 天要等网页入口（第 4 批 4c）
        mapper.insertBan("TG", String.valueOf(r.applicantTgId), BAN_FOREVER, r.id, now);
        toApplicant(r.applicantTgId, "你的找回申请 #" + r.id + " 没有通过。");
      }
    });
  }

  @Override
  public void revokeByAdmin(long id, long adminId, String reason) {
    tx.executeWithoutResult(st -> {
      RecoveryMapper.Row r = lockOrThrow(id);
      if (!OPEN.contains(r.status)) throw new BizException("RECOVERY_NOT_OPEN", "这份申请已经结束了");
      close(r, "CANCELLED", "admin:" + adminId, reason);
      if (r.applicantTgId != null) toApplicant(r.applicantTgId, "你的找回申请 #" + r.id + " 已被审核员撤销。");
    });
  }

  @Override
  public void keep(long id, long adminId, String reason) {
    tx.executeWithoutResult(st -> {
      RecoveryMapper.Row r = lockOrThrow(id);
      if (!("APPROVED".equals(r.status) || "PROOF".equals(r.status)) || r.revokeRequestedBy == null) {
        throw new BizException("RECOVERY_NO_REVOKE_REQUEST", "这份申请没有待决定的撤销请求");
      }
      mapper.clearRevoke(r.id, now());
      mapper.insertEvent(r.id, "KEEP", "admin:" + adminId, reason, now());
    });
  }

  // ---------- 定时（RecoveryScheduler） ----------

  /** 反对期到了、没人反对的换绑：进入证明阶段，冻结原账户提现到申请结束，告诉申请人等联系 */
  void objectionEnded(long id) {
    tx.executeWithoutResult(st -> {
      RecoveryMapper.Row r = mapper.lock(id);
      if (r == null || !"OBJECTION".equals(r.status) || now().isBefore(r.objectionEndsAt)) return;
      mapper.transit(r.id, "OBJECTION", "PROOF");
      mapper.insertEvent(r.id, "PROOF", "system", "48 小时反对期结束，没人反对", now());
      pins.freezeWithdraw(r.userId, SOURCE_RECOVERY, "找回申请 #" + r.id + " 证明阶段");
      if (r.applicantTgId != null) {
        toApplicant(r.applicantTgId, "找回申请 #" + r.id + "：48 小时反对期已过。审核员会通过这个 Telegram 联系你，做视频核对本人和资金来源（只认手机 App 的实时共享）。请不要相信其他渠道自称客服的人。");
      }
    });
  }

  /** 点了"不是我"超过 30 分钟还没输对提现密码：冻结提现 */
  void disputeGraceEnded(long id) {
    tx.executeWithoutResult(st -> {
      RecoveryMapper.Row r = mapper.lock(id);
      if (r == null || !OPEN.contains(r.status) || !r.disputed || r.disputePinOk || r.disputeFrozen) return;
      disputeFreeze(r, "system", "点了不是我以后 30 分钟没输提现密码");
    });
  }

  /** 到执行时间：换绑 / 作废提现密码 */
  void execute(long id) {
    Long[] oldTg = new Long[1];
    tx.executeWithoutResult(st -> {
      RecoveryMapper.Row r = mapper.lock(id);
      LocalDateTime now = now();
      if (r == null || !"APPROVED".equals(r.status) || r.revokeRequestedBy != null || now.isBefore(r.executeAt)) return;
      if (REBIND.equals(r.kind)) {
        if (telegram.byTelegramId(r.applicantTgId).isPresent()) {
          // 等待期里申请人的新 TG 自己开了户：不能换绑（一个 TG 只能对应一个客户号）
          mapper.insertEvent(r.id, "EXECUTE_FAILED", "system", "新 Telegram 已经绑了别的客户号", now);
          close(r, "REJECTED", "system", "新 Telegram 已经绑了别的客户号，没有执行");
          toApplicant(r.applicantTgId, "找回申请 #" + r.id + " 没有执行：这个 Telegram 已经绑了别的客户号。");
          return;
        }
        oldTg[0] = telegram.byUserId(r.userId).map(TelegramAccountApi.TgAccount::telegramId).orElse(null);
        // 安全事件：作废全部网页会话（含 BOT 会话），48 小时不能提现；之后才改授权时间，新 TG 不用再点"重新授权"
        auth.securityEvent(r.userId, SecurityEvent.REBIND);
        // 后台给没有 TG 的客户关联（4c）：没有原来的 TG，直接关联
        if (oldTg[0] == null) telegram.link(r.userId, r.applicantTgId, r.applicantTgUsername, now());
        else telegram.rebind(r.userId, r.applicantTgId, r.applicantTgUsername, now());
      } else {
        auth.securityEvent(r.userId, SecurityEvent.PIN_RESET);
        pins.releaseFreeze(r.userId, SOURCE_PIN_LOCKS);
      }
      pins.clearPin(r.userId);
      events.publishEvent(new AccountRecovered(r.userId, r.kind, r.id)); // 钱包在同一个事务里清空白名单
      mapper.insertEvent(r.id, "EXECUTE", "system", null, now);
      releaseFreezes(r.userId);
      mapper.close(r.id, "APPROVED", "EXECUTED", "system", null, now);
      notify.toUser(r.userId, REBIND.equals(r.kind) ? "RECOVERY_EXECUTED" : "PIN_RESET_EXECUTED", Map.of("id", String.valueOf(r.id)));
      if (REBIND.equals(r.kind) && oldTg[0] != null) {
        long old = oldTg[0];
        toApplicant(old, "你的账户（客户号 #" + r.userId + "）已按找回申请 #" + r.id + " 换绑到另一个 Telegram，这个 Telegram 不再能操作它。如果不是你本人安排的，请马上联系客服。");
      }
    });
  }

  // ---------- 公共 ----------

  private RecoveryMapper.Row own(long userId, long recoveryId) {
    RecoveryMapper.Row r = mapper.lock(recoveryId);
    return r == null || r.userId != userId ? null : r;
  }

  private RecoveryMapper.Row lockOrThrow(long id) {
    RecoveryMapper.Row r = mapper.lock(id);
    if (r == null) throw new BizException("NOT_FOUND", "申请不存在");
    return r;
  }

  private void disputeFreeze(RecoveryMapper.Row r, String actor, String why) {
    if (r.disputeFrozen) return;
    pins.freezeWithdraw(r.userId, SOURCE_DISPUTE, "找回申请 #" + r.id + " 有争议：" + why);
    mapper.markDisputeFrozen(r.id);
    mapper.insertEvent(r.id, "DISPUTE_FREEZE", actor, why, now());
  }

  /** 结束一份进行中的申请（撤销 / 驳回）：解除这份申请带来的冻结，通知客户 */
  private void close(RecoveryMapper.Row r, String to, String by, String reason) {
    LocalDateTime now = now();
    if (mapper.close(r.id, r.status, to, by, cut(reason, 500), now) != 1) throw new BizException("RECOVERY_NOT_OPEN", "这份申请状态已经变了");
    mapper.insertEvent(r.id, "CANCELLED".equals(to) ? "CANCEL" : "REJECT", by, cut(reason, 500), now);
    releaseFreezes(r.userId);
    String zh = "CANCELLED".equals(to) ? "已撤销" : "没有通过";
    String en = "CANCELLED".equals(to) ? "cancelled" : "rejected";
    notify.toUser(r.userId, "RECOVERY_CLOSED", Map.of("id", String.valueOf(r.id), "result", zh, "resultEn", en));
  }

  /**
   * 解除这份申请带来的冻结（证明阶段 RECOVERY、争议 DISPUTE）。冻结时暂停的已审核待放款提现不会自动继续（4b 审查 🟡1），在后台逐笔处理
   */
  private void releaseFreezes(long userId) {
    pins.releaseFreeze(userId, SOURCE_RECOVERY);
    pins.releaseFreeze(userId, SOURCE_DISPUTE);
  }

  /** 发给申请人（还没绑账户的新 TG）或换绑后的原 TG：事务提交以后才排进机器人发送队列 */
  private void toApplicant(long tgId, String text) {
    Runnable send = () -> {
      TelegramSender s = sender.getIfAvailable();
      if (s != null) s.sendToChat(tgId, text, TelegramSender.Priority.HIGH);
    };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          send.run();
        }
      });
    } else {
      send.run();
    }
  }

  private void alert(String template, Map<String, String> p) {
    List<String> to = alerts.recipients();
    if (to.isEmpty()) log.warn("没有配置告警邮箱（FX_ALERT_EMAILS），{} 只记了日志：{}", template, p);
    for (String email : to) {
      try {
        mail.send(email, template, p);
      } catch (RuntimeException e) {
        log.error("告警邮件 {} 写入发送任务失败", template, e);
      }
    }
  }

  static boolean lastFourMatches(String docNumber, String last4) {
    if (docNumber == null || last4 == null) return false;
    String d = docNumber.strip().toUpperCase(Locale.ROOT);
    String l = last4.strip().toUpperCase(Locale.ROOT);
    return l.length() == 4 && d.length() >= 4 && d.endsWith(l);
  }

  /** 姓名：去掉空格、不分大小写；名在前或姓在前都认（中文名一般姓在前） */
  static boolean nameMatches(RecoveryMapper.Identity id, String name) {
    if (name == null || id.firstName() == null || id.lastName() == null) return false;
    String n = norm(name);
    if (n.isEmpty()) return false;
    String f = norm(id.firstName());
    String m = norm(id.middleName() == null ? "" : id.middleName());
    String l = norm(id.lastName());
    return n.equals(f + m + l) || n.equals(l + f + m) || n.equals(l + m + f) || n.equals(f + l) || n.equals(l + f);
  }

  private static String norm(String s) {
    return s.replaceAll("[\\s·.\\-]", "").toLowerCase(Locale.ROOT);
  }

  private static String cut(String s, int n) {
    return s == null ? null : s.length() <= n ? s : s.substring(0, n);
  }

  static View view(RecoveryMapper.Row r) {
    return new View(r.id, r.kind, r.userId, r.applicantTgId, r.applicantTgUsername, r.status, r.disputed, r.disputePinOk, r.disputeFrozen,
        r.objectionEndsAt, r.proofNote, r.approvedBy, r.approvedAt, r.executeAt, r.revokeRequestedBy, r.revokeRequestedAt, r.keptAt, r.closedBy, r.closeReason,
        r.closedAt, r.createdAt);
  }
}
