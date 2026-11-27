package app.trading.account.api;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 找回申请（TG 机器人第 4 批 4b，需求文档 TG机器人-08 页第 1、3 节，决定清单 TB-19、TB-14 v14）：
 * <ul>
 *   <li><b>人工换绑（REBIND）</b>：新 TG 在机器人里「找回原账户」申请（客户号 + KYC 姓名 + 证件号后 4 位；不管对不对都回同一句）→ 通知原 TG 和邮箱，
 *       48 小时反对期（没人反对不冻结）→ 期满进入证明阶段、冻结原账户提现到申请结束 → <b>Amos 一人审核</b>（再输后台密码、审计、告警邮件）→
 *       <b>24 小时执行等待期</b> → 换绑（提现密码、白名单清空，48 小时不能提现，安全事件作废全部网页会话）。</li>
 *   <li>原 TG 点「不是我」：争议路径，转加严人工；可以在网页输提现密码，输对不冻结，没输（30 分钟内）、输错或选"直接冻结"就冻结提现直到申请结束。</li>
 *   <li>撤销（4b 审查 🔴R1，比需求文档更严）：反对期里只能走"不是我"；证明阶段和批准以后，原 TG 的<b>任何撤销都转 Amos 决定</b>（不管有没有争议；
 *       记撤销请求、暂停批准和执行、冻结保留，Amos 选撤销或维持）；<b>Amos 本人</b>在后台撤销直接生效。没邮箱人工重设提现密码本人可直接撤销。
 *       邮件里的签名撤销链接、服务器命令 {@code cancel-recovery} 是第 6 批（TB-37）。</li>
 *   <li><b>没邮箱忘记提现密码（PIN_RESET）</b>：本人在网页申请 → 证明 + Amos 审核 → 48 小时等待期（本人随时可撤销）→ 提现密码作废、白名单清空、48 小时不能提现。</li>
 *   <li>驳回换绑：封申请人用过的新 TG（长期）。</li>
 * </ul>
 */
public interface RecoveryApi {

  /** 机器人里提交找回的结果：ACCEPTED 不管信息对不对都是这个（防猜 KYC 信息），只有申请人自己的次数和封禁另外回 */
  enum ApplyResult {
    ACCEPTED,
    TOO_FREQUENT,
    BANNED,
    /** 第 6 批 TB-38：暂停受理人工申请中（回"暂停中，请先申请冻结提现"） */
    PAUSED
  }

  ApplyResult applyRebind(long applicantTgId, String applicantUsername, long customerNo, String name, String docLast4);

  /** 原 TG 点「不是我」：标争议、转加严人工（反对期内和证明阶段都可以）；返回给机器人回的话 */
  String object(long userId, long recoveryId);

  /** 原 TG 点「不输，直接冻结提现」 */
  String freezeNow(long userId, long recoveryId);

  /** 原 TG 在网页输提现密码：输对不冻结（已经因为争议冻结的解除）；输错冻结并照常计数（抛提现密码的错） */
  void objectWithPin(long userId, long recoveryId, WithdrawPinApi.PinInput pin);

  /** 原 TG（或本人）点「撤销」：换绑在证明阶段和批准后一律记撤销请求、暂停、转 Amos；反对期按"不是我"；人工重设提现密码直接撤销。返回给机器人回的话 */
  String revokeByOwner(long userId, long recoveryId);

  /** 没邮箱的 TG 客户申请人工重设提现密码；返回申请编号 */
  long applyPinReset(long userId);

  /** 第 4 批 4c：核对网页"申请冻结提现"填的本人信息。NO_KYC 时由调用方核对最近一笔充值（金额 + 日期） */
  enum IdentityCheck {
    NOT_FOUND,
    KYC_MATCH,
    KYC_MISMATCH,
    NO_KYC
  }

  IdentityCheck checkIdentity(long customerNo, String name, String docLast4);

  /** 这个客户有没有进行中的找回申请（网页入口"7 天只能冻结 1 次"不受限） */
  boolean hasOpen(long userId);

  /**
   * 第 4 批 4c 后台"解除关联 / 更换 Telegram 关联"（08 页第 5 节；只有超管、再输后台密码、审计、告警邮件在后台模块）：
   * <ul>
   *   <li>解除（newTelegramId 为空）：马上生效——安全事件（作废全部网页会话含 BOT 会话）+ 删掉 TG 关联 + 冻结提现（来源 ADMIN）。不给任何人新的访问权。</li>
   *   <li>更换（4c 审查 🟡）：不一步到位，建一份<b>已批准</b>的换绑申请（审批人就是操作的管理员），24 小时后由定时任务执行（和 4b 的换绑一样：
   *       安全事件、改绑、清提现密码和白名单）；建的时候就冻结提现（ADMIN）、通知原 TG 和邮箱（带「撤销」），原 TG 的撤销按 4b 规则转 Amos
   *       （维持过一次后不再暂停）。这个客户已经有进行中的找回申请时拒绝（{@code RECOVERY_OPEN}）。</li>
   * </ul>
   */
  record TelegramChange(Long oldTelegramId, Long recoveryId, LocalDateTime executeAt) {}

  TelegramChange adminChangeTelegram(long userId, Long newTelegramId, String newUsername, long adminId, String reason);

  // ---------- 后台（权限、再输密码、审计、告警邮件在后台模块） ----------

  record Event(String action, String actor, String note, LocalDateTime at) {}

  record View(long id, String kind, long userId, Long applicantTgId, String applicantTgUsername, String status, boolean disputed, boolean disputePinOk,
      boolean disputeFrozen, LocalDateTime objectionEndsAt, String proofNote, Long approvedBy, LocalDateTime approvedAt, LocalDateTime executeAt,
      String revokeRequestedBy, LocalDateTime revokeRequestedAt, LocalDateTime keptAt, String closedBy, String closeReason, LocalDateTime closedAt, LocalDateTime createdAt) {}

  record Detail(View recovery, List<Event> events) {}

  /** status 为空时是全部；最新的在前，最多 200 条 */
  List<View> list(String status);

  Optional<Detail> detail(long id);

  /** 当前客户自己的申请（作为被找回的账户），最新的在前 */
  List<View> mine(long userId);

  /** 批准：只能在证明阶段（反对期过了，或争议转人工）；proofNote 必填；返回执行时间 */
  LocalDateTime approve(long id, long adminId, String proofNote);

  void reject(long id, long adminId, String reason);

  /** Amos 本人撤销：没执行的都可以，直接生效 */
  void revokeByAdmin(long id, long adminId, String reason);

  /** 收到原 TG 的撤销请求后，Amos 视频核对后维持（证明阶段或已批准）：清掉撤销请求，继续审核 / 到执行时间就执行 */
  void keep(long id, long adminId, String reason);

  // ---------- 第 6 批：后台加固（TB-37 ②、TB-38） ----------

  /**
   * 告警邮件里的撤销入口（中文、英文各一段）：现做一个一次性撤销链接（24 小时、只能撤销这一份申请）+ 服务器命令 {@code sudo trading cancel-recovery <编号>}。
   * 没配后台地址（{@code FX_ADMIN_URL}）时只有服务器命令
   */
  record RevokeLines(String zh, String en) {}

  RevokeLines revokeLines(long recoveryId);

  /** 撤销链接对应的申请（不用掉令牌；令牌用过、过期、不存在时为空） */
  record RevokeTarget(long id, String kind, long userId, String status, LocalDateTime executeAt) {}

  Optional<RevokeTarget> peekRevokeLink(String token);

  /** 撤销链接的结果：CANCELLED 撤销了 / NOT_OPEN 申请已经结束（令牌也用掉了）/ INVALID 令牌用过、过期或不存在 */
  enum LinkRevoke {
    CANCELLED,
    NOT_OPEN,
    INVALID
  }

  /** 用告警邮件里的链接撤销（只能撤销，没有批准的入口）。撤销后发告警邮件 OPS_RECOVERY_CANCELLED */
  LinkRevoke revokeByLink(String token);

  /** 暂停受理人工申请（TB-38）：暂停时新的换绑（机器人）、没邮箱重设提现密码（网页）申请不受理；已提交的留在队列、已批准的照常执行 */
  record Intake(boolean paused, LocalDateTime changedAt, String changedBy, String reason) {}

  record IntakeChange(long id, boolean paused, String actor, String actorName, String reason, LocalDateTime at) {}

  Intake intake();

  List<IntakeChange> intakeHistory(int limit);

  /** 和现在一样时报 {@code INTAKE_UNCHANGED}。谁能操作、后台密码、审计、告警邮件在后台模块 */
  void setIntakePaused(boolean paused, String actor, String actorName, String reason);
}
