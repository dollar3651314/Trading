package app.trading.account.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import app.trading.account.api.RecoveryApi.ApplyResult;
import app.trading.account.api.TelegramAccountApi;
import app.trading.account.api.WithdrawPinApi;
import app.trading.common.error.BizException;
import app.trading.notification.api.MailApi;
import app.trading.notification.api.TelegramSender;
import app.trading.support.FixedClockConfig;
import app.trading.support.MutableClock;
import app.trading.support.PinTestSupport;
import app.trading.trading.api.LedgerApi;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 找回和换绑（第 4 批 4b，需求文档 TG机器人-08 页第 1、3 节，TB-19、TB-14 v14），真实 MySQL：
 * 申请（信息不对也回同一句、限频、封禁）→ 48 小时反对期 → 证明阶段冻结提现 → Amos 批准 → 24 小时执行等待期 → 换绑（安全事件作废全部会话、
 * 提现密码和白名单清空、冻结解除、通知原 TG）；"不是我"的争议路径（输对提现密码不冻结、30 分钟没输 / 输错冻结、任何撤销转 Amos、
 * Amos 维持或撤销）；正常路径原 TG 直接撤销；驳回封 TG；没邮箱人工重设提现密码（48 小时）。
 * 机器人发送用替身（{@link TelegramSender}），定时推进直接调 {@link RecoveryService} 的方法。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
@TestPropertySource(properties = "trading.alert.emails=ops@example.com")
class RecoveryFlowTest {

  static final String PIN = "135790";
  static final String TRON = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";

  @Autowired RecoveryService recovery;
  @Autowired RecoveryMapper mapper;
  @Autowired TelegramAccountApi telegram;
  @Autowired WithdrawPinApi pins;
  @Autowired LedgerApi ledger;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired Clock clock;
  @MockitoBean MailApi mail;
  @MockitoBean TelegramSender sender;

  @AfterEach
  void reset() {
    ((MutableClock) clock).reset();
  }

  MutableClock time() {
    return (MutableClock) clock;
  }

  LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
  }

  static long newTg() {
    return 6_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
  }

  /** TG 客户：KYC 已通过（护照 E12345678），姓名 张三（姓 张、名 三），设好提现密码，有一个白名单地址；email 不为空时有邮箱 */
  long customer(String email) {
    long uid = telegram.openAccount(newTg(), "orig").userId();
    if (email != null) jdbc.update("UPDATE account_user SET email = ? WHERE id = ?", email, uid);
    jdbc.update("INSERT INTO account_profile (user_id, first_name, middle_name, last_name, birth_date, nationality) VALUES (?, '三', '', '张', '1990-01-01', 'CN')", uid);
    jdbc.update("INSERT INTO account_kyc_application (user_id, doc_type, doc_number, front_key, back_key, selfie_key, status) VALUES (?, 'PASSPORT', ?, 'f', 'b', 's', 'APPROVED')",
        uid, "E1234" + (uid % 10000 + 10000));
    PinTestSupport.setPin(jdbc, uid, PIN);
    jdbc.update("INSERT INTO wallet_whitelist (user_id, network, address, address_key, label, created_at, active_at) VALUES (?, 'TRC20', ?, ?, '旧地址', NOW(3), NOW(3))", uid, TRON, TRON);
    return uid;
  }

  String last4(long uid) {
    String doc = jdbc.queryForObject("SELECT doc_number FROM account_kyc_application WHERE user_id = ?", String.class, uid);
    return doc.substring(doc.length() - 4);
  }

  long tgOf(long uid) {
    return telegram.byUserId(uid).orElseThrow().telegramId();
  }

  /** 新 TG 申请换绑，返回申请编号 */
  long apply(long uid, long newTg) {
    assertThat(recovery.applyRebind(newTg, "newme", uid, "张三", last4(uid))).isEqualTo(ApplyResult.ACCEPTED);
    return open(uid);
  }

  Long open(long uid) {
    List<Long> ids = jdbc.queryForList("SELECT id FROM account_recovery WHERE user_id = ? ORDER BY id DESC", Long.class, uid);
    return ids.isEmpty() ? null : ids.getFirst();
  }

  RecoveryMapper.Row row(long id) {
    return mapper.byId(id);
  }

  /** 4b 复审 🟡：冻结按来源记录，判断"有没有这个来源"要用 frozenBy（frozenSource 只给最早的那个） */
  boolean frozenBy(long uid, String source) {
    return pins.status(uid).frozenBy(source);
  }

  /** 全部冻结来源 */
  List<String> sources(long uid) {
    return pins.status(uid).freezes().stream().map(f -> f.source()).toList();
  }

  long session(long uid) {
    jdbc.update("INSERT INTO account_session (user_id, token_hash, ip, user_agent, created_at, last_seen_at, expires_at) VALUES (?, ?, '203.0.113.9', 't', NOW(3), NOW(3), NOW(3) + INTERVAL 1 DAY)",
        uid, String.format("%064x", ThreadLocalRandom.current().nextLong(Long.MAX_VALUE)));
    return jdbc.queryForObject("SELECT MAX(id) FROM account_session WHERE user_id = ?", Long.class, uid);
  }

  boolean revoked(long sessionId) {
    return jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM account_session WHERE id = ?", Boolean.class, sessionId);
  }

  List<String> actions(long id) {
    return mapper.events(id).stream().map(e -> e.action()).toList();
  }

  String code(Runnable r) {
    try {
      r.run();
      return "OK";
    } catch (BizException e) {
      return e.code();
    }
  }

  /** 走到证明阶段（反对期过了） */
  long toProof(long uid, long newTg) {
    long id = apply(uid, newTg);
    time().advance(Duration.ofHours(48).plusSeconds(1));
    recovery.objectionEnded(id);
    assertThat(row(id).status).isEqualTo("PROOF");
    return id;
  }

  // ---------- 申请 ----------

  @Test
  void 信息不对也回同一句_不建申请_对了才建_通知原账户() {
    long uid = customer("rc" + System.nanoTime() + "@example.com");
    long a = newTg();
    assertThat(recovery.applyRebind(a, "x", uid, "李四", last4(uid))).isEqualTo(ApplyResult.ACCEPTED);
    assertThat(recovery.applyRebind(newTg(), "x", uid, "张三", "0000")).isEqualTo(ApplyResult.ACCEPTED);
    assertThat(recovery.applyRebind(newTg(), "x", 999_999_999L, "张三", "1234")).isEqualTo(ApplyResult.ACCEPTED);
    assertThat(open(uid)).as("信息不对的都没建申请").isNull();
    long id = apply(uid, newTg());
    RecoveryMapper.Row r = row(id);
    assertThat(r.status).isEqualTo("OBJECTION");
    assertThat(r.objectionEndsAt).isEqualTo(r.createdAt.plusHours(48));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE user_id = ? AND template_code = 'RECOVERY_REQUESTED'", Integer.class, uid)).isPositive();
    assertThat(sources(uid)).as("反对期里不冻结").isEmpty();
    assertThat(recovery.applyRebind(newTg(), "y", uid, "张 三", last4(uid))).isEqualTo(ApplyResult.ACCEPTED);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_recovery WHERE user_id = ?", Integer.class, uid)).as("同时只能 1 份").isEqualTo(1);
  }

  @Test
  void 同一个新TG每天3次_7天内同一账户1份_没做KYC找不回() {
    long uid = customer(null);
    long a = newTg();
    for (int i = 0; i < 3; i++) assertThat(recovery.applyRebind(a, "x", uid, "错的", "0000")).isEqualTo(ApplyResult.ACCEPTED);
    assertThat(recovery.applyRebind(a, "x", uid, "张三", last4(uid))).as("第 4 次").isEqualTo(ApplyResult.TOO_FREQUENT);
    time().advance(Duration.ofDays(1).plusSeconds(1));
    long id = apply(uid, a);
    recovery.revokeByAdmin(id, 1L, "测试");
    assertThat(recovery.applyRebind(newTg(), "x", uid, "张三", last4(uid))).isEqualTo(ApplyResult.ACCEPTED);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_recovery WHERE user_id = ?", Integer.class, uid)).as("7 天内只能 1 份").isEqualTo(1);

    long noKyc = customer(null);
    jdbc.update("UPDATE account_kyc_application SET status = 'PENDING' WHERE user_id = ?", noKyc);
    assertThat(recovery.applyRebind(newTg(), "x", noKyc, "张三", last4(noKyc))).isEqualTo(ApplyResult.ACCEPTED);
    assertThat(open(noKyc)).isNull();
  }

  // ---------- 正常路径 ----------

  @Test
  void 正常路径_反对期_证明阶段冻结_批准_24小时后换绑_清空提现密码白名单_作废会话_通知原TG() {
    long uid = customer("rc" + System.nanoTime() + "@example.com");
    long oldTg = tgOf(uid);
    long newTg = newTg();
    long id = apply(uid, newTg);
    assertThat(code(() -> recovery.approve(id, 1L, "视频核对"))).as("反对期里不能批准").isEqualTo("RECOVERY_NOT_READY");
    time().advance(Duration.ofHours(47));
    recovery.objectionEnded(id);
    assertThat(row(id).status).as("还没到 48 小时").isEqualTo("OBJECTION");
    time().advance(Duration.ofHours(1).plusSeconds(1));
    recovery.objectionEnded(id);
    assertThat(row(id).status).isEqualTo("PROOF");
    assertThat(frozenBy(uid, "RECOVERY")).as("证明阶段冻结提现").isTrue();
    verify(sender).sendToChat(eq(newTg), contains("48 小时反对期已过"), eq(TelegramSender.Priority.HIGH));

    assertThat(code(() -> recovery.approve(id, 1L, " "))).isEqualTo("PROOF_NOTE_REQUIRED");
    LocalDateTime at = recovery.approve(id, 1L, "来源地址小额 1.37 + 视频");
    assertThat(at).isEqualTo(now().plusHours(24));
    assertThat(row(id).status).isEqualTo("APPROVED");

    long s = session(uid);
    time().advance(Duration.ofHours(23));
    recovery.execute(id);
    assertThat(row(id).status).as("24 小时执行等待期").isEqualTo("APPROVED");
    time().advance(Duration.ofHours(1).plusSeconds(1));
    recovery.execute(id);
    assertThat(row(id).status).isEqualTo("EXECUTED");
    assertThat(tgOf(uid)).isEqualTo(newTg);
    assertThat(telegram.byTelegramId(oldTg)).isEmpty();
    assertThat(pins.status(uid).set()).as("提现密码清空").isFalse();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_whitelist WHERE user_id = ?", Integer.class, uid)).as("白名单清空").isZero();
    assertThat(jdbc.queryForList("SELECT type FROM account_security_event WHERE user_id = ?", String.class, uid)).contains("REBIND");
    assertThat(revoked(s)).as("安全事件作废全部网页会话").isTrue();
    assertThat(sources(uid)).as("申请结束解除 RECOVERY 冻结").isEmpty();
    verify(sender).sendToChat(eq(oldTg), contains("已按找回申请"), eq(TelegramSender.Priority.HIGH));
    assertThat(jdbc.queryForList("SELECT channel FROM notify_record WHERE user_id = ? AND template_code = 'RECOVERY_EXECUTED'", String.class, uid))
        .as("换绑完成也发到原账户的邮箱").contains("EMAIL");
    assertThat(actions(id)).containsExactly("APPLY", "PROOF", "APPROVE", "EXECUTE");
    assertThat(telegram.byUserId(uid).orElseThrow().authorizedAt()).as("新 TG 不用再点重新授权").isAfterOrEqualTo(
        jdbc.queryForObject("SELECT MAX(created_at) FROM account_security_event WHERE user_id = ?", LocalDateTime.class, uid));
  }

  /**
   * 4b 审查 🔴R1：批准以后原 TG 的撤销（没有争议也一样）转 Amos 决定——暂停执行、RECOVERY 冻结保留；Amos 撤销才结束，维持就照常执行。
   * 防止占着原 TG 的人在反对期里不出声、批准后一撤销就把真原主挡在外面
   */
  @Test
  void 批准后原TG撤销_没有争议也转Amos_暂停执行_冻结保留_Amos决定() {
    long uid = customer(null);
    long newTg = newTg();
    long id = toProof(uid, newTg);
    recovery.approve(id, 1L, "视频");
    assertThat(recovery.revokeByOwner(uid, id)).contains("由审核员视频核对后决定").contains("提现冻结保持不变");
    assertThat(row(id).status).as("撤销不作废申请").isEqualTo("APPROVED");
    assertThat(row(id).revokeRequestedBy).isEqualTo("ORIGINAL_TG");
    assertThat(frozenBy(uid, "RECOVERY")).isTrue();
    verify(mail).send(eq("ops@example.com"), eq("OPS_RECOVERY_REVOKE_REQ"), anyMap());
    time().advance(Duration.ofHours(25));
    recovery.execute(id);
    assertThat(row(id).status).as("待 Amos 决定时不执行").isEqualTo("APPROVED");
    assertThat(tgOf(uid)).isNotEqualTo(newTg);
    // Amos 维持：照常执行
    recovery.keep(id, 1L, "视频核对，原主就是申请人");
    recovery.execute(id);
    assertThat(row(id).status).isEqualTo("EXECUTED");
    assertThat(tgOf(uid)).isEqualTo(newTg);

    // 另一份：Amos 撤销才结束、冻结解除
    long uid2 = customer(null);
    long id2 = toProof(uid2, newTg());
    recovery.approve(id2, 1L, "视频");
    recovery.revokeByOwner(uid2, id2);
    assertThat(frozenBy(uid2, "RECOVERY")).isTrue();
    recovery.revokeByAdmin(id2, 1L, "视频核对，申请人不是本人");
    assertThat(row(id2).status).isEqualTo("CANCELLED");
    assertThat(sources(uid2)).isEmpty();
  }

  /** 4b 复审：Amos 维持过一次以后，原 TG 再撤销只记经过、发告警，不再暂停执行（防止反复撤销拖住申请） */
  @Test
  void Amos维持过一次后_原TG再撤销不再暂停_记经过_发告警_照常执行() {
    long uid = customer(null);
    long newTg = newTg();
    long id = toProof(uid, newTg);
    recovery.approve(id, 1L, "视频");
    recovery.revokeByOwner(uid, id);
    recovery.keep(id, 1L, "视频核对后维持");
    assertThat(row(id).keptAt).isNotNull();
    org.mockito.Mockito.clearInvocations(mail);
    assertThat(recovery.revokeByOwner(uid, id)).contains("不会暂停换绑");
    assertThat(row(id).revokeRequestedBy).as("不再记撤销请求").isNull();
    assertThat(actions(id)).endsWith("KEEP", "REVOKE_AFTER_KEEP");
    verify(mail).send(eq("ops@example.com"), eq("OPS_RECOVERY_REVOKE_REQ"), org.mockito.ArgumentMatchers.argThat(m -> m.get("who").contains("维持过一次")));
    assertThat(frozenBy(uid, "RECOVERY")).isTrue();
    time().advance(Duration.ofHours(25));
    assertThat(mapper.executeDue(now())).contains(id);
    recovery.execute(id);
    assertThat(row(id).status).isEqualTo("EXECUTED");
    assertThat(tgOf(uid)).isEqualTo(newTg);
  }

  @Test
  void 证明阶段原TG撤销_转Amos_冻结保留_不能批准直到Amos维持() {
    long uid = customer(null);
    long id = toProof(uid, newTg());
    assertThat(recovery.revokeByOwner(uid, id)).contains("由审核员视频核对后决定");
    assertThat(row(id).status).isEqualTo("PROOF");
    assertThat(row(id).revokeRequestedBy).isEqualTo("ORIGINAL_TG");
    assertThat(frozenBy(uid, "RECOVERY")).isTrue();
    assertThat(code(() -> recovery.approve(id, 1L, "视频"))).isEqualTo("RECOVERY_REVOKE_PENDING");
    recovery.keep(id, 1L, "视频核对后继续审核");
    recovery.approve(id, 1L, "视频");
    assertThat(row(id).status).isEqualTo("APPROVED");
  }

  @Test
  void 反对期里原TG撤销_按不是我处理_不关申请() {
    long uid = customer(null);
    long id = apply(uid, newTg());
    assertThat(recovery.revokeByOwner(uid, id)).startsWith("已记下");
    assertThat(row(id).status).isEqualTo("PROOF");
    assertThat(row(id).disputed).isTrue();
    assertThat(row(id).closedAt).isNull();
  }

  // ---------- 4b 审查 🔴R2：冻结按来源 ----------

  @Test
  void 证明阶段冻结后又被提现密码锁冻结_申请驳回或撤销后仍冻结() {
    long uid = customer(null);
    long id = toProof(uid, newTg());
    pins.freezeWithdraw(uid, "PIN_LOCKS", "30 天内提现密码被锁 2 次");
    assertThat(pins.status(uid).freezes()).extracting(f -> f.source()).containsExactlyInAnyOrder("RECOVERY", "PIN_LOCKS");
    recovery.reject(id, 1L, "视频对不上");
    assertThat(pins.status(uid).withdrawFrozen()).as("只解除 RECOVERY").isTrue();
    assertThat(pins.status(uid).freezes()).extracting(f -> f.source()).containsExactly("PIN_LOCKS");

    long uid2 = customer(null);
    long id2 = apply(uid2, newTg());
    recovery.object(uid2, id2);
    recovery.freezeNow(uid2, id2);
    pins.freezeWithdraw(uid2, "WEB_REQUEST", "网页申请冻结");
    recovery.revokeByAdmin(id2, 1L, "撤销");
    assertThat(pins.status(uid2).freezes()).extracting(f -> f.source()).containsExactly("WEB_REQUEST");
  }

  @Test
  void 驳回_封申请人的TG_之后再申请不受理() {
    long uid = customer(null);
    long newTg = newTg();
    long id = toProof(uid, newTg);
    recovery.reject(id, 1L, "视频对不上");
    assertThat(row(id).status).isEqualTo("REJECTED");
    assertThat(sources(uid)).isEmpty();
    long other = customer(null);
    assertThat(recovery.applyRebind(newTg, "x", other, "张三", last4(other))).isEqualTo(ApplyResult.BANNED);
    verify(sender).sendToChat(eq(newTg), contains("没有通过"), eq(TelegramSender.Priority.HIGH));
  }

  @Test
  void 等待期里新TG自己开了户_不执行() {
    long uid = customer(null);
    long newTg = newTg();
    long id = toProof(uid, newTg);
    recovery.approve(id, 1L, "视频");
    telegram.openAccount(newTg, "self");
    time().advance(Duration.ofHours(25));
    recovery.execute(id);
    assertThat(row(id).status).isEqualTo("REJECTED");
    assertThat(tgOf(uid)).isNotEqualTo(newTg);
    assertThat(actions(id)).contains("EXECUTE_FAILED");
  }

  // ---------- 争议路径 ----------

  @Test
  void 不是我_输对提现密码不冻结_没输30分钟冻结_输错冻结() {
    long uid = customer(null);
    long id = apply(uid, newTg());
    assertThat(recovery.object(uid, id)).startsWith("已记下");
    assertThat(row(id).status).as("争议直接转加严人工").isEqualTo("PROOF");
    assertThat(row(id).disputed).isTrue();
    assertThat(sources(uid)).isEmpty();
    recovery.objectWithPin(uid, id, PinTestSupport.input(redis, uid, PIN));
    assertThat(row(id).disputePinOk).isTrue();
    time().advance(Duration.ofMinutes(31));
    recovery.disputeGraceEnded(id);
    assertThat(sources(uid)).as("输对了不冻结").isEmpty();

    long uid2 = customer(null);
    long id2 = apply(uid2, newTg());
    recovery.object(uid2, id2);
    time().advance(Duration.ofMinutes(31));
    recovery.disputeGraceEnded(id2);
    assertThat(frozenBy(uid2, "DISPUTE")).as("30 分钟没输").isTrue();
    // 冻结之后再输对：解除这次争议冻结
    recovery.objectWithPin(uid2, id2, PinTestSupport.input(redis, uid2, PIN));
    assertThat(sources(uid2)).isEmpty();

    long uid3 = customer(null);
    long id3 = apply(uid3, newTg());
    recovery.object(uid3, id3);
    assertThat(code(() -> recovery.objectWithPin(uid3, id3, PinTestSupport.input(redis, uid3, "000111")))).isEqualTo("PIN_WRONG");
    assertThat(frozenBy(uid3, "DISPUTE")).as("输错冻结").isTrue();
    assertThat(actions(id3)).containsExactly("APPLY", "OBJECT", "PIN_WRONG", "DISPUTE_FREEZE");

    long uid4 = customer(null);
    long id4 = apply(uid4, newTg());
    recovery.object(uid4, id4);
    recovery.freezeNow(uid4, id4);
    assertThat(frozenBy(uid4, "DISPUTE")).as("选了直接冻结").isTrue();
    assertThat(code(() -> recovery.objectWithPin(uid4 + 1, id4, PinTestSupport.input(redis, uid4 + 1, PIN)))).as("别人的申请").isEqualTo("NOT_FOUND");
  }

  @Test
  void 争议路径_撤销转Amos_暂停执行_Amos维持后执行() {
    long uid = customer(null);
    long newTg = newTg();
    long id = apply(uid, newTg);
    recovery.object(uid, id);
    recovery.objectWithPin(uid, id, PinTestSupport.input(redis, uid, PIN));
    recovery.approve(id, 1L, "视频核对，原主确认是自己的新号");
    assertThat(recovery.revokeByOwner(uid, id)).contains("由审核员视频核对后决定").contains("不会执行换绑");
    assertThat(row(id).status).as("撤销不作废申请").isEqualTo("APPROVED");
    assertThat(row(id).revokeRequestedBy).isEqualTo("ORIGINAL_TG");
    verify(mail).send(eq("ops@example.com"), eq("OPS_RECOVERY_REVOKE_REQ"), anyMap());
    time().advance(Duration.ofHours(25));
    assertThat(mapper.executeDue(now())).doesNotContain(id);
    recovery.execute(id);
    assertThat(row(id).status).as("待 Amos 决定时不执行").isEqualTo("APPROVED");
    assertThat(code(() -> recovery.keep(id + 100000, 1L, "x"))).isEqualTo("NOT_FOUND");
    recovery.keep(id, 1L, "视频核对后维持");
    recovery.execute(id);
    assertThat(row(id).status).isEqualTo("EXECUTED");
    assertThat(tgOf(uid)).isEqualTo(newTg);
  }

  @Test
  void 争议路径_Amos本人撤销直接生效() {
    long uid = customer(null);
    long id = apply(uid, newTg());
    recovery.object(uid, id);
    recovery.freezeNow(uid, id);
    recovery.approve(id, 1L, "视频");
    recovery.revokeByOwner(uid, id);
    recovery.revokeByAdmin(id, 1L, "视频核对，申请人不是本人");
    assertThat(row(id).status).isEqualTo("CANCELLED");
    assertThat(row(id).closedBy).isEqualTo("admin:1");
    assertThat(sources(uid)).as("申请结束解除争议冻结").isEmpty();
  }

  // ---------- 没邮箱忘记提现密码 ----------

  @Test
  void 没邮箱人工重设提现密码_批准后48小时生效_清空白名单_本人可撤销() {
    long withMail = customer("pm" + System.nanoTime() + "@example.com");
    assertThat(code(() -> recovery.applyPinReset(withMail))).isEqualTo("HAS_EMAIL");

    long uid = customer(null);
    long id = recovery.applyPinReset(uid);
    assertThat(row(id).status).isEqualTo("PROOF");
    assertThat(code(() -> recovery.applyPinReset(uid))).isEqualTo("RECOVERY_OPEN");
    assertThat(sources(uid)).isEmpty();
    LocalDateTime at = recovery.approve(id, 1L, "视频共享 App 提币记录");
    assertThat(at).isEqualTo(now().plusHours(48));
    time().advance(Duration.ofHours(47));
    recovery.execute(id);
    assertThat(pins.status(uid).set()).isTrue();
    time().advance(Duration.ofHours(1).plusSeconds(1));
    long s = session(uid);
    recovery.execute(id);
    assertThat(row(id).status).isEqualTo("EXECUTED");
    assertThat(pins.status(uid).set()).isFalse();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_whitelist WHERE user_id = ?", Integer.class, uid)).isZero();
    assertThat(jdbc.queryForList("SELECT type FROM account_security_event WHERE user_id = ?", String.class, uid)).contains("PIN_RESET");
    assertThat(revoked(s)).isTrue();

    long uid2 = customer(null);
    long id2 = recovery.applyPinReset(uid2);
    recovery.approve(id2, 1L, "视频");
    assertThat(recovery.revokeByOwner(uid2, id2)).isEqualTo("已撤销这份申请。");
    assertThat(row(id2).status).isEqualTo("CANCELLED");
    verify(sender, never()).sendToChat(eq(tgOf(uid2)), anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void 提现密码30天锁2次冻结的_有邮箱也可以人工重设_生效后解除冻结() {
    long uid = customer("pl" + System.nanoTime() + "@example.com");
    pins.freezeWithdraw(uid, "PIN_LOCKS", "30 天内提现密码被锁 2 次");
    long id = recovery.applyPinReset(uid);
    recovery.approve(id, 1L, "视频");
    time().advance(Duration.ofHours(49));
    recovery.execute(id);
    assertThat(sources(uid)).isEmpty();
  }

  @Test
  void 姓名和证件号后4位的比对() {
    var id = new RecoveryMapper.Identity(1L, "San", "", "Zhang", "APPROVED", "E12345678");
    assertThat(RecoveryService.nameMatches(id, "zhang san")).isTrue();
    assertThat(RecoveryService.nameMatches(id, "San Zhang")).isTrue();
    assertThat(RecoveryService.nameMatches(id, "Li Si")).isFalse();
    assertThat(RecoveryService.lastFourMatches("E12345678", "5678")).isTrue();
    assertThat(RecoveryService.lastFourMatches("E12345678", "678")).isFalse();
    assertThat(RecoveryService.lastFourMatches(null, "5678")).isFalse();
    verify(sender, never()).sendToChat(anyLong(), eq("never"), org.mockito.ArgumentMatchers.any());
  }

  // ---------- 4c 审查 🟡：后台更换 Telegram 关联 = 已批准的换绑申请，24 小时后执行 ----------

  @Test
  void 后台更换Telegram关联_建已批准的申请_冻结ADMIN_原TG撤销转Amos_维持一次后不再暂停_24小时后执行() {
    long uid = customer(null);
    long oldTg = tgOf(uid);
    long newTg = newTg();
    var c = recovery.adminChangeTelegram(uid, newTg, "newme", 1L, "视频核对后换绑");
    assertThat(c.oldTelegramId()).isEqualTo(oldTg);
    assertThat(c.executeAt()).isEqualTo(now().plusHours(24));
    long id = c.recoveryId();
    assertThat(row(id).status).isEqualTo("APPROVED");
    assertThat(row(id).approvedBy).isEqualTo(1L);
    assertThat(sources(uid)).containsExactly("ADMIN");
    assertThat(tgOf(uid)).as("还没换").isEqualTo(oldTg);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE user_id = ? AND template_code = 'RECOVERY_APPROVED'", Integer.class, uid)).isPositive();
    assertThat(code(() -> recovery.adminChangeTelegram(uid, newTg(), null, 1L, "再换"))).as("已经有进行中的申请").isEqualTo("RECOVERY_OPEN");
    // 原 TG 撤销：按 4b 规则转 Amos、暂停；维持一次以后再撤销不再暂停
    assertThat(recovery.revokeByOwner(uid, id)).contains("由审核员视频核对后决定");
    time().advance(Duration.ofHours(25));
    recovery.execute(id);
    assertThat(row(id).status).isEqualTo("APPROVED");
    recovery.keep(id, 1L, "视频核对后维持");
    assertThat(recovery.revokeByOwner(uid, id)).contains("不会暂停换绑");
    recovery.execute(id);
    assertThat(row(id).status).isEqualTo("EXECUTED");
    assertThat(tgOf(uid)).isEqualTo(newTg);
    assertThat(sources(uid)).as("ADMIN 冻结要人工核对后解冻").containsExactly("ADMIN");
    assertThat(jdbc.queryForList("SELECT type FROM account_security_event WHERE user_id = ?", String.class, uid)).contains("REBIND");
  }

  @Test
  void 后台给没有Telegram的客户关联_执行时直接关联() {
    long uid = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 + FLOOR(RAND() * 1000) FROM account_user", Long.class);
    jdbc.update("INSERT INTO account_user (id, email, status) VALUES (?, ?, 'ACTIVE')", uid, "nl" + uid + "@example.com");
    long newTg = newTg();
    var c = recovery.adminChangeTelegram(uid, newTg, null, 1L, "客户要求关联");
    assertThat(c.oldTelegramId()).isNull();
    time().advance(Duration.ofHours(24).plusSeconds(1));
    recovery.execute(c.recoveryId());
    assertThat(tgOf(uid)).isEqualTo(newTg);
  }
}
