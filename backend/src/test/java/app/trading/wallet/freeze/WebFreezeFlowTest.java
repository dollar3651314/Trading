package app.trading.wallet.freeze;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import app.trading.account.api.TelegramAccountApi;
import app.trading.account.api.WithdrawPinApi;
import app.trading.common.captcha.Captchas;
import app.trading.common.config.ConfigKey;
import app.trading.common.config.SystemConfig;
import app.trading.common.error.BizException;
import app.trading.notification.api.MailApi;
import app.trading.support.FixedClockConfig;
import app.trading.support.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 网页"申请冻结提现"（第 4 批 4c，需求文档 TG机器人-08 页第 2 节，TB-27），真实 MySQL 和 Redis：不登录、要验证码；信息不对也回同一句；
 * 失败的提交不影响正确的提交（同一 IP 失败很多次以后，正确的照样冻结，只是换更难的验证码）；目标账户失败 5 次告警；7 天内同一账户只能被网页冻结 1 次
 * （有找回申请时不受限）；全平台超过阈值告警；没做 KYC 的核对最近一笔充值；只能人工解冻（WEB_REQUEST）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
@TestPropertySource(properties = "trading.alert.emails=ops@example.com")
class WebFreezeFlowTest {

  @Autowired WebFreezeService service;
  @Autowired TelegramAccountApi telegram;
  @Autowired WithdrawPinApi pins;
  @Autowired SystemConfig config;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired Clock clock;
  @Autowired MockMvc mvc;
  @MockitoBean MailApi mail;

  @AfterEach
  void reset() {
    ((MutableClock) clock).reset();
    config.setBySystem(ConfigKey.WITHDRAW_FREEZE_PLATFORM_ALERT_PER_HOUR, "10", LocalDateTime.now());
  }

  static String ip() {
    return "198.51.100." + ThreadLocalRandom.current().nextInt(1, 250) + "-" + UUID.randomUUID().toString().substring(0, 6);
  }

  LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
  }

  /** 放一个已知答案的验证码 */
  String captcha() {
    String id = UUID.randomUUID().toString().replace("-", "");
    redis.opsForValue().set(Captchas.PREFIX + id, Captchas.hash("AC34"), Duration.ofMinutes(5));
    return id;
  }

  /** TG 客户，KYC 已通过（姓名 李雷，证件号后 4 位 4321）；kyc=false 时没做 KYC、有一笔 2026-10-01 到账 123.45 的充值 */
  long customer(boolean kyc) {
    long uid = telegram.openAccount(5_500_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L), "fz").userId();
    if (kyc) {
      jdbc.update("INSERT INTO account_profile (user_id, first_name, middle_name, last_name, birth_date, nationality) VALUES (?, '雷', '', '李', '1990-01-01', 'CN')", uid);
      jdbc.update("INSERT INTO account_kyc_application (user_id, doc_type, doc_number, front_key, back_key, selfie_key, status) VALUES (?, 'PASSPORT', 'G00004321', 'f', 'b', 's', 'APPROVED')",
          uid);
    } else {
      jdbc.update("INSERT INTO wallet_deposit (public_no, chain, tx_hash, log_index, block_number, block_hash, from_address, to_address, user_id, amount, status, "
          + "confirmations, credited_at) VALUES (?, 'TRC20', ?, 0, 1, '0x1', 'Ta', 'Tb', ?, 123.45, 'CREDITED', 20, '2026-10-01 08:00:00')",
          app.trading.common.id.Snowflake.shared(1).next(), "0x" + UUID.randomUUID().toString().replace("-", "") + uid, uid);
    }
    return uid;
  }

  WebFreezeService.Form kyc(long uid, String name, String last4) {
    return new WebFreezeService.Form(String.valueOf(uid), name, last4, null, null, captcha(), "ac34");
  }

  List<String> sources(long uid) {
    return pins.status(uid).freezes().stream().map(WithdrawPinApi.Freeze::source).toList();
  }

  String code(Runnable r) {
    try {
      r.run();
      return "OK";
    } catch (BizException e) {
      return e.code();
    }
  }

  @Test
  void 信息对不对都回同一句_对了才冻结_来源WEB_REQUEST_验证码不对单独提示() {
    long uid = customer(true);
    String ip = ip();
    String wrong = service.submit(ip, "fp-aaaaaaaa", kyc(uid, "韩梅梅", "4321"));
    String wrong2 = service.submit(ip, null, kyc(uid, "李雷", "0000"));
    String unknown = service.submit(ip, null, kyc(999_999_999_999L, "李雷", "4321"));
    assertThat(sources(uid)).isEmpty();
    String ok = service.submit(ip, null, kyc(uid, "李 雷", "4321"));
    assertThat(List.of(wrong, wrong2, unknown, ok)).containsOnly(WebFreezeService.ACCEPTED);
    assertThat(sources(uid)).containsExactly("WEB_REQUEST");
    assertThat(service.submit(ip, null, kyc(uid, "李雷", "4321"))).isEqualTo(WebFreezeService.ACCEPTED);
    assertThat(jdbc.queryForList("SELECT result FROM wallet_freeze_request WHERE target_user_id = ? ORDER BY id", String.class, uid))
        .containsExactly("WRONG", "WRONG", "FROZEN", "ALREADY");
    var bad = new WebFreezeService.Form(String.valueOf(uid), "李雷", "4321", null, null, captcha(), "XXXX");
    assertThat(code(() -> service.submit(ip, null, bad))).isEqualTo("CAPTCHA_INVALID");
    var reused = new WebFreezeService.Form(String.valueOf(uid), "李雷", "4321", null, null, "0".repeat(32), "AC34");
    assertThat(code(() -> service.submit(ip, null, reused))).as("不存在 / 用过的验证码").isEqualTo("CAPTCHA_INVALID");
  }

  @Test
  void 失败的提交不影响正确的提交_同一IP失败很多次后正确的照样冻结_只是换更难的验证码_不锁目标账户() {
    long uid = customer(true);
    String ip = ip();
    String fp = "fp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String other = "fp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    assertThat(service.hard(ip, fp)).isFalse();
    for (int i = 0; i < 12; i++) service.submit(ip, fp, kyc(uid, "猜的", "000" + (i % 10)));
    assertThat(service.hard(ip, fp)).as("同一来源当天失败 10 次以上换更难的").isTrue();
    assertThat(service.hard(ip(), fp)).as("换了 IP，同一浏览器标识也一样").isTrue();
    assertThat(service.hard(ip(), other)).as("别的来源不受影响").isFalse();
    assertThat(service.captcha(ip, fp).hard()).isTrue();
    service.submit(ip, fp, kyc(uid, "李雷", "4321"));
    assertThat(sources(uid)).as("正确的照样冻结").containsExactly("WEB_REQUEST");
    // 目标账户 24 小时失败 5 次以上：发告警（只告警，不锁账户）
    verify(mail).send(eq("ops@example.com"), eq("OPS_ALERT"), argThat(p -> p.get("itemZh").contains("客户 " + uid) && p.get("itemZh").contains("信息不对")));
  }

  @Test
  void 同一IP当天提交满3次换更难的验证码() {
    long uid = customer(true);
    String ip = ip();
    for (int i = 0; i < 3; i++) service.submit(ip, null, kyc(uid, "李雷", "4321"));
    assertThat(service.hard(ip, null)).isTrue();
  }

  @Test
  void 没做KYC的核对最近一笔充值的金额和日期() {
    long uid = customer(false);
    String ip = ip();
    service.submit(ip, null, new WebFreezeService.Form(String.valueOf(uid), null, null, "123.4", "2026-10-01", captcha(), "AC34"));
    service.submit(ip, null, new WebFreezeService.Form(String.valueOf(uid), null, null, "123.45", "2026-10-02", captcha(), "AC34"));
    assertThat(sources(uid)).isEmpty();
    service.submit(ip, null, new WebFreezeService.Form(String.valueOf(uid), "随便", "9999", "123.450", "2026-10-01", captcha(), "AC34"));
    assertThat(sources(uid)).containsExactly("WEB_REQUEST");
  }

  @Test
  void 同一账户7天内只能被网页冻结1次_有找回申请时不受限() {
    long uid = customer(true);
    String ip = ip();
    service.submit(ip, null, kyc(uid, "李雷", "4321"));
    pins.unfreezeWithdraw(uid, "WEB_REQUEST");
    ((MutableClock) clock).advance(Duration.ofDays(3));
    service.submit(ip(), null, kyc(uid, "李雷", "4321"));
    assertThat(sources(uid)).as("7 天内第二次不冻结").isEmpty();
    verify(mail).send(eq("ops@example.com"), eq("OPS_ALERT"), argThat(p -> p.get("itemZh").contains("7 天内已经被网页入口冻结过")));
    jdbc.update("INSERT INTO account_recovery (kind, user_id, applicant_tg_id, status, objection_ends_at, created_at) VALUES ('REBIND', ?, 1, 'OBJECTION', ?, ?)", uid,
        now().plusHours(40), now());
    service.submit(ip(), null, kyc(uid, "李雷", "4321"));
    assertThat(sources(uid)).as("有进行中的找回申请：不受 7 天限制").containsExactly("WEB_REQUEST");
    ((MutableClock) clock).advance(Duration.ofDays(5));
  }

  @Test
  void 全平台1小时成功冻结超过阈值_告警_正确的照常冻结() {
    config.setBySystem(ConfigKey.WITHDRAW_FREEZE_PLATFORM_ALERT_PER_HOUR, "1", LocalDateTime.now());
    // 前面的测试可能已经冻结过：挪到 1 小时以外，这里从 0 开始数
    jdbc.update("UPDATE wallet_freeze_request SET created_at = created_at - INTERVAL 2 HOUR");
    redis.delete("fx:freeze-alert:platform");
    long a = customer(true);
    long b = customer(true);
    service.submit(ip(), null, kyc(a, "李雷", "4321"));
    service.submit(ip(), null, kyc(b, "李雷", "4321"));
    assertThat(sources(b)).containsExactly("WEB_REQUEST");
    verify(mail).send(eq("ops@example.com"), eq("OPS_ALERT"), argThat(p -> p.get("itemZh").contains("1 小时内成功冻结")));
  }

  @Test
  void 接口不用登录_验证码是PNG_提交回同一句() throws Exception {
    long uid = customer(true);
    MockHttpServletResponse c = mvc.perform(post("/api/public/withdraw-freeze/captcha").param("fp", "fp-" + UUID.randomUUID().toString().substring(0, 8))).andReturn().getResponse();
    assertThat(c.getStatus()).isEqualTo(200);
    assertThat(c.getContentAsString()).contains("\"id\"").contains("\"pngBase64\":\"iVBORw0KGgo");
    String body = "{\"customerNo\":\"" + uid + "\",\"name\":\"李雷\",\"docLast4\":\"4321\",\"captchaId\":\"" + captcha() + "\",\"captcha\":\"AC34\",\"fp\":\"fp-dddddddd\"}";
    MockHttpServletResponse r = mvc.perform(post("/api/public/withdraw-freeze").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(r.getContentAsString()).contains(WebFreezeService.ACCEPTED);
    assertThat(sources(uid)).containsExactly("WEB_REQUEST");
    verify(mail, org.mockito.Mockito.never()).send(eq("nobody"), eq("x"), anyMap());
  }
}
