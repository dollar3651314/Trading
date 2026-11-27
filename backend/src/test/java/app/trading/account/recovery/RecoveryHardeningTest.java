package app.trading.account.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import app.trading.account.api.RecoveryApi;
import app.trading.account.api.RecoveryApi.ApplyResult;
import app.trading.account.api.TelegramAccountApi;
import app.trading.account.api.WithdrawPinApi;
import app.trading.common.error.BizException;
import app.trading.notification.api.MailApi;
import app.trading.notification.api.TelegramSender;
import app.trading.support.FixedClockConfig;
import app.trading.support.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 第 6 批后台加固（TB-37 ②、TB-38），真实 MySQL：告警邮件里的撤销链接（24 小时、只能用一次、只能撤销、库里只存哈希）；
 * 服务器命令 cancel-recovery 写的撤销请求（应用处理，执行前先处理）；暂停受理人工申请（新申请不受理、不记次数，已批准的照常执行）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
@TestPropertySource(properties = {"trading.alert.emails=ops@example.com", "trading.admin.public-url=https://admin.example.test/"})
class RecoveryHardeningTest {

  static final Pattern LINK = Pattern.compile("https://admin\\.example\\.test/admin/recovery-revoke#t=([A-Za-z0-9_-]{43})");

  @Autowired RecoveryService recovery;
  @Autowired RecoveryMapper mapper;
  @Autowired TelegramAccountApi telegram;
  @Autowired WithdrawPinApi pins;
  @Autowired JdbcTemplate jdbc;
  @Autowired Clock clock;
  @MockitoBean MailApi mail;
  @MockitoBean TelegramSender sender;

  @AfterEach
  void reset() {
    ((MutableClock) clock).reset();
    // 暂停开关是全库共用的：测试结束一定恢复受理，不影响别的测试
    if (recovery.intake().paused()) recovery.setIntakePaused(false, "admin:1", "test", "测试结束");
  }

  MutableClock time() {
    return (MutableClock) clock;
  }

  static long newTg() {
    return 6_200_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
  }

  /** 后台更换 Telegram 关联：建一份已批准的换绑申请（24 小时后执行） */
  long approvedRebind() {
    long uid = telegram.openAccount(newTg(), "o").userId();
    return recovery.adminChangeTelegram(uid, newTg(), null, 1L, "测试").recoveryId();
  }

  static String token(RecoveryApi.RevokeLines l) {
    Matcher m = LINK.matcher(l.zh());
    assertThat(m.find()).as(l.zh()).isTrue();
    assertThat(l.en()).contains(m.group(0));
    return m.group(1);
  }

  @Test
  void 撤销链接_先看不用掉_撤销后不能再用_只存哈希_发告警() {
    long id = approvedRebind();
    RecoveryApi.RevokeLines l = recovery.revokeLines(id);
    assertThat(l.zh()).contains("24 小时内").contains("sudo trading cancel-recovery " + id);
    String t = token(l);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_recovery_revoke_token WHERE token_hash = ?", Integer.class, RecoveryService.sha256(t))).isOne();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_recovery_revoke_token WHERE token_hash = ?", Integer.class, t)).as("库里不存原值").isZero();
    assertThat(recovery.peekRevokeLink(t)).get().satisfies(x -> {
      assertThat(x.id()).isEqualTo(id);
      assertThat(x.status()).isEqualTo("APPROVED");
    });
    assertThat(recovery.peekRevokeLink(t)).as("看不会用掉").isPresent();
    assertThat(recovery.revokeByLink(t)).isEqualTo(RecoveryApi.LinkRevoke.CANCELLED);
    RecoveryMapper.Row r = mapper.byId(id);
    assertThat(r.status).isEqualTo("CANCELLED");
    assertThat(r.closedBy).isEqualTo("mail");
    assertThat(recovery.revokeByLink(t)).as("只能用一次").isEqualTo(RecoveryApi.LinkRevoke.INVALID);
    assertThat(recovery.peekRevokeLink(t)).isEmpty();
    verify(mail).send(eq("ops@example.com"), eq("OPS_RECOVERY_CANCELLED"), argThat(p -> p.get("id").equals(String.valueOf(id)) && p.get("viaZh").contains("撤销链接")));
    assertThat(recovery.revokeByLink("x")).isEqualTo(RecoveryApi.LinkRevoke.INVALID);
    assertThat(recovery.revokeByLink(null)).isEqualTo(RecoveryApi.LinkRevoke.INVALID);
    assertThat(recovery.revokeByLink("A".repeat(43))).as("猜的").isEqualTo(RecoveryApi.LinkRevoke.INVALID);
  }

  @Test
  void 撤销链接24小时后失效_申请已经结束时用掉令牌但不改() {
    long id = approvedRebind();
    String old = token(recovery.revokeLines(id));
    time().advance(Duration.ofHours(24).plusSeconds(1));
    assertThat(recovery.peekRevokeLink(old)).isEmpty();
    assertThat(recovery.revokeByLink(old)).isEqualTo(RecoveryApi.LinkRevoke.INVALID);
    String t = token(recovery.revokeLines(id));
    recovery.execute(id);
    assertThat(mapper.byId(id).status).isEqualTo("EXECUTED");
    assertThat(recovery.revokeByLink(t)).isEqualTo(RecoveryApi.LinkRevoke.NOT_OPEN);
    assertThat(mapper.byId(id).status).isEqualTo("EXECUTED");
  }

  @Test
  void 服务器撤销请求_执行前先处理_到时间也不执行_结果写回() {
    long id = approvedRebind();
    jdbc.update("INSERT INTO account_recovery_cancel_request (recovery_id, requested_by, reason, created_at) VALUES (?, 'server:root', '后台账号可能被盗', UTC_TIMESTAMP(3))", id);
    time().advance(Duration.ofHours(25));
    recovery.execute(id);
    assertThat(mapper.byId(id).status).as("有没处理的撤销请求，不执行").isEqualTo("APPROVED");
    jdbc.update("INSERT INTO account_recovery_cancel_request (recovery_id, requested_by, reason, created_at) VALUES (999999999999, 'server:root', 'x', UTC_TIMESTAMP(3))");
    recovery.processServerCancels();
    RecoveryMapper.Row r = mapper.byId(id);
    assertThat(r.status).isEqualTo("CANCELLED");
    assertThat(r.closedBy).isEqualTo("server:root");
    assertThat(r.closeReason).isEqualTo("后台账号可能被盗");
    assertThat(jdbc.queryForList("SELECT result FROM account_recovery_cancel_request WHERE recovery_id IN (?, 999999999999) ORDER BY id", String.class, id))
        .containsExactly("CANCELLED", "NOT_FOUND");
    verify(mail).send(eq("ops@example.com"), eq("OPS_RECOVERY_CANCELLED"), argThat(p -> p.get("id").equals(String.valueOf(id)) && p.get("viaZh").contains("cancel-recovery")));
    jdbc.update("INSERT INTO account_recovery_cancel_request (recovery_id, requested_by, reason, created_at) VALUES (?, 'server:root', '再来', UTC_TIMESTAMP(3))", id);
    recovery.processServerCancels();
    assertThat(jdbc.queryForObject("SELECT result FROM account_recovery_cancel_request WHERE recovery_id = ? ORDER BY id DESC LIMIT 1", String.class, id)).isEqualTo("NOT_OPEN");
  }

  @Test
  void 暂停受理_新的换绑和重设提现密码不受理_不记次数_已批准的照常执行_恢复后照常() {
    long approved = approvedRebind();
    recovery.setIntakePaused(true, "admin:1", "amos", "出差");
    assertThat(recovery.intake().paused()).isTrue();
    try {
      recovery.setIntakePaused(true, "admin:1", "amos", "x");
      throw new AssertionError("应该报错");
    } catch (BizException e) {
      assertThat(e.code()).isEqualTo("INTAKE_UNCHANGED");
    }
    long applicant = newTg();
    long uid = telegram.openAccount(newTg(), "p").userId();
    assertThat(recovery.applyRebind(applicant, "a", uid, "张三", "1234")).isEqualTo(ApplyResult.PAUSED);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_recovery_attempt WHERE applicant_tg_id = ?", Integer.class, applicant)).as("不记次数").isZero();
    try {
      recovery.applyPinReset(uid);
      throw new AssertionError("应该报错");
    } catch (BizException e) {
      assertThat(e.code()).isEqualTo("RECOVERY_PAUSED");
    }
    time().advance(Duration.ofHours(24).plusSeconds(1));
    recovery.execute(approved);
    assertThat(mapper.byId(approved).status).as("已批准的照常执行").isEqualTo("EXECUTED");
    recovery.setIntakePaused(false, "admin:1", "amos", "回来了");
    assertThat(recovery.applyRebind(applicant, "a", uid, "张三", "1234")).isEqualTo(ApplyResult.ACCEPTED);
    assertThat(recovery.intakeHistory(2)).extracting(RecoveryApi.IntakeChange::paused).containsExactly(false, true);
  }
}
