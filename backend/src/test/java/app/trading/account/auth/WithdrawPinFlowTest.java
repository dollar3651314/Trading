package app.trading.account.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import app.trading.account.api.TelegramAccountApi;
import app.trading.account.api.WithdrawPinApi;
import app.trading.account.api.WithdrawPinApi.PinInput;
import app.trading.common.error.BizException;
import app.trading.common.error.ValidationException;
import app.trading.notification.api.MailApi;
import app.trading.support.FixedClockConfig;
import app.trading.support.MutableClock;
import app.trading.support.PinTestSupport;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 提现密码（TG 机器人第 4 批，05 页第 5 节必测："提现密码随机键盘（每次位置不同、按键不产生消息）、错 5 次锁定 / 期满清零 / 30 天锁 2 次冻结提现"）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class WithdrawPinFlowTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired WithdrawPins pins;
  @Autowired WithdrawPinApi api;
  @Autowired TelegramAccountApi telegram;
  @Autowired SessionService sessions;
  @Autowired StringRedisTemplate redis;
  @Autowired Clock clock;
  @Autowired ObjectMapper json;
  @MockitoBean MailApi mail;

  @AfterEach
  void reset() {
    ((MutableClock) clock).reset();
  }

  long tgUser() {
    return telegram.openAccount(4_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L), null).userId();
  }

  PinInput in(long uid, String pin) {
    return PinTestSupport.input(redis, uid, pin, "8023914576");
  }

  String code(Runnable r) {
    try {
      r.run();
      return "OK";
    } catch (BizException e) {
      return e.code();
    } catch (ValidationException e) {
      return "VALIDATION:" + e.fields().getFirst().code();
    }
  }

  List<String> events(long uid) {
    return jdbc.queryForList("SELECT type FROM account_security_event WHERE user_id = ? ORDER BY id", String.class, uid);
  }

  @Test
  void 随机键盘_每次位置不同_只能用一次_只给发它的客户用_请求里只有按键位置() throws Exception {
    long uid = tgUser();
    Set<List<Integer>> layouts = new HashSet<>();
    for (int i = 0; i < 20; i++) {
      WithdrawPins.Keypad k = pins.keypad(uid);
      assertThat(k.digits()).containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
      layouts.add(k.digits());
    }
    assertThat(layouts.size()).as("20 次里位置至少有不一样的").isGreaterThan(1);

    WithdrawPins.Keypad k = pins.keypad(uid);
    // 要输 246813：交的是每个数字在键盘上的位置
    List<Integer> pos = "246813".chars().mapToObj(c -> k.digits().indexOf(c - '0')).toList();
    assertThat(pins.decode(uid, new PinInput(k.id(), pos), "pin")).isEqualTo("246813");
    assertThat(code(() -> pins.decode(uid, new PinInput(k.id(), pos), "pin"))).as("键盘只能用一次").isEqualTo("PIN_KEYPAD_EXPIRED");
    WithdrawPins.Keypad other = pins.keypad(uid);
    assertThat(code(() -> pins.decode(uid + 1, new PinInput(other.id(), pos), "pin"))).as("别人的键盘不认").isEqualTo("PIN_KEYPAD_EXPIRED");
    WithdrawPins.Keypad k3 = pins.keypad(uid);
    assertThat(code(() -> pins.decode(uid, new PinInput(k3.id(), List.of(1, 2, 3)), "pin"))).isEqualTo("VALIDATION:pinLength");

    // 接口：直接交数字（不经过键盘）不认
    Cookie c = new Cookie("fx_session", sessions.create(uid, "203.0.113.9", "t"));
    MockHttpServletResponse r = mvc.perform(post("/api/me/withdraw-pin").cookie(c).contentType(MediaType.APPLICATION_JSON)
        .content("{\"pin\":\"246813\",\"confirm\":\"246813\"}")).andReturn().getResponse();
    assertThat(r.getStatus()).isEqualTo(400);
    JsonNode kp = json.readTree(mvc.perform(post("/api/me/withdraw-pin/keypad").cookie(c)).andReturn().getResponse().getContentAsString());
    assertThat(kp.get("digits").size()).isEqualTo(10);
    assertThat(kp.get("keypadId").asString()).isNotBlank();
  }

  /** 用接口要一副键盘，按数字算出位置 */
  String body(Cookie c, String... pins) throws Exception {
    StringBuilder b = new StringBuilder("{");
    String[] names = pins.length == 3 ? new String[] {"old", "pin", "confirm"} : new String[] {"pin", "confirm"};
    for (int i = 0; i < pins.length; i++) {
      JsonNode kp = json.readTree(mvc.perform(post("/api/me/withdraw-pin/keypad").cookie(c)).andReturn().getResponse().getContentAsString());
      List<Integer> digits = new java.util.ArrayList<>();
      kp.get("digits").forEach(d -> digits.add(d.asInt()));
      String pos = pins[i].chars().mapToObj(ch -> String.valueOf(digits.indexOf(ch - '0'))).collect(java.util.stream.Collectors.joining(","));
      if (i > 0) b.append(',');
      b.append('"').append(names[i]).append("\":{\"keypadId\":\"").append(kp.get("keypadId").asString()).append("\",\"positions\":[").append(pos).append("]}");
    }
    return b.append('}').toString();
  }

  MockHttpServletResponse postJson(String url, Cookie c, String body) throws Exception {
    return mvc.perform(post(url).cookie(c).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
  }

  @Test
  void 键盘重放_同一副键盘第二次提交无效_不算输错() throws Exception {
    long uid = tgUser();
    PinTestSupport.setPin(jdbc, uid, "246813");
    // 服务层：校验用过的键盘再交一次
    PinInput once = in(uid, "246813");
    pins.verify(uid, once);
    assertThat(code(() -> pins.verify(uid, once))).isEqualTo("PIN_KEYPAD_EXPIRED");
    PinInput wrong = in(uid, "999999");
    assertThat(code(() -> pins.verify(uid, wrong))).isEqualTo("PIN_WRONG");
    assertThat(code(() -> pins.verify(uid, wrong))).as("输错的请求原样重放：键盘已失效，不再计数").isEqualTo("PIN_KEYPAD_EXPIRED");
    assertThat(jdbc.queryForObject("SELECT failed_count FROM account_withdraw_pin WHERE user_id = ?", Integer.class, uid)).isEqualTo(1);

    // 接口：修改提现密码的请求原样再发一次
    Cookie c = new Cookie("fx_session", sessions.create(uid, "203.0.113.9", "t"));
    String req = body(c, "246813", "582047", "582047");
    MockHttpServletResponse first = postJson("/api/me/withdraw-pin/change", c, req);
    assertThat(first.getStatus()).as(first.getContentAsString()).isLessThan(300);
    Cookie c2 = new Cookie("fx_session", sessions.create(uid, "203.0.113.9", "t"));
    MockHttpServletResponse replay = postJson("/api/me/withdraw-pin/change", c2, req);
    assertThat(replay.getContentAsString()).contains("PIN_KEYPAD_EXPIRED");
    assertThat(PinTestSupport.matches(jdbc, uid, "582047")).as("密码还是第一次改的").isTrue();
  }

  @Test
  void 别人的键盘_接口里用不了() throws Exception {
    long a = tgUser();
    long b = tgUser();
    Cookie ca = new Cookie("fx_session", sessions.create(a, "203.0.113.9", "t"));
    Cookie cb = new Cookie("fx_session", sessions.create(b, "203.0.113.9", "t"));
    // A 要的键盘，B 拿来设置自己的提现密码
    String aBody = body(ca, "582047", "582047");
    MockHttpServletResponse r = postJson("/api/me/withdraw-pin", cb, aBody);
    assertThat(r.getContentAsString()).contains("PIN_KEYPAD_EXPIRED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_withdraw_pin WHERE user_id IN (?, ?)", Integer.class, a, b)).isZero();
    // 服务层：别人的键盘校验也不认，不算输错
    PinTestSupport.setPin(jdbc, b, "246813");
    assertThat(code(() -> pins.verify(b, in(a, "246813")))).isEqualTo("PIN_KEYPAD_EXPIRED");
    assertThat(jdbc.queryForObject("SELECT failed_count FROM account_withdraw_pin WHERE user_id = ?", Integer.class, b)).isZero();
  }

  @Test
  void 第一次设置_两次一致_不能太简单_是安全事件_作废全部网页会话_只能设一次() {
    long uid = tgUser();
    Cookie c = new Cookie("fx_session", sessions.create(uid, "203.0.113.9", "t"));
    assertThat(code(() -> pins.setFirst(uid, in(uid, "135790"), in(uid, "135791")))).isEqualTo("VALIDATION:pinMismatch");
    assertThat(code(() -> pins.setFirst(uid, in(uid, "111111"), in(uid, "111111")))).isEqualTo("VALIDATION:pinTooSimple");
    assertThat(code(() -> pins.setFirst(uid, in(uid, "123456"), in(uid, "123456")))).isEqualTo("VALIDATION:pinTooSimple");
    assertThat(code(() -> pins.setFirst(uid, in(uid, "135790"), in(uid, "135790")))).isEqualTo("OK");
    assertThat(events(uid)).contains("PIN_SET");
    assertThat(sessions.resolve(c.getValue())).as("安全事件作废全部网页会话").isNull();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE user_id = ? AND template_code = 'WITHDRAW_PIN_SET'", Integer.class, uid)).isPositive();
    assertThat(code(() -> pins.setFirst(uid, in(uid, "246802"), in(uid, "246802")))).isEqualTo("PIN_ALREADY_SET");
    assertThat(jdbc.queryForObject("SELECT pin_hash FROM account_withdraw_pin WHERE user_id = ?", String.class, uid)).startsWith("$2").doesNotContain("135790");
  }

  @Test
  void 错5次锁24小时_锁着输对也不行_期满清零() {
    long uid = tgUser();
    pins.setFirst(uid, in(uid, "135790"), in(uid, "135790"));
    for (int i = 1; i <= 4; i++) {
      final int n = i;
      BizException e = (BizException) org.assertj.core.api.Assertions.catchThrowable(() -> api.verify(uid, in(uid, "000123")));
      assertThat(e.code()).isEqualTo("PIN_WRONG");
      assertThat(e.getMessage()).contains("还能再试 " + (5 - n) + " 次");
    }
    assertThat(code(() -> api.verify(uid, in(uid, "000123")))).isEqualTo("PIN_LOCKED");
    assertThat(code(() -> api.verify(uid, in(uid, "135790")))).as("锁着时输对也不行").isEqualTo("PIN_LOCKED");
    assertThat(api.status(uid).lockedUntil()).isNotNull();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE user_id = ? AND template_code = 'WITHDRAW_PIN_LOCKED'", Integer.class, uid)).isPositive();
    ((MutableClock) clock).advance(Duration.ofHours(24).plusSeconds(1));
    assertThat(code(() -> api.verify(uid, in(uid, "000123")))).as("期满清零：重新从 1 次算").isEqualTo("PIN_WRONG");
    assertThat(jdbc.queryForObject("SELECT failed_count FROM account_withdraw_pin WHERE user_id = ?", Integer.class, uid)).isEqualTo(1);
    assertThat(code(() -> api.verify(uid, in(uid, "135790")))).isEqualTo("OK");
    assertThat(jdbc.queryForObject("SELECT failed_count FROM account_withdraw_pin WHERE user_id = ?", Integer.class, uid)).as("输对清零").isZero();
    assertThat(api.status(uid).withdrawFrozen()).as("只锁了 1 次，不冻结").isFalse();
  }

  @Test
  void 三十天内锁2次_冻结提现_只能人工解冻() {
    long uid = tgUser();
    pins.setFirst(uid, in(uid, "135790"), in(uid, "135790"));
    for (int round = 0; round < 2; round++) {
      for (int i = 0; i < 5; i++) code(() -> api.verify(uid, in(uid, "000123")));
      ((MutableClock) clock).advance(Duration.ofHours(25));
    }
    WithdrawPinApi.Status s = api.status(uid);
    assertThat(s.withdrawFrozen()).isTrue();
    assertThat(s.frozenSource()).isEqualTo("PIN_LOCKS");
    assertThat(code(() -> api.verify(uid, in(uid, "135790")))).as("冻结提现时输对也不行").isEqualTo("WITHDRAW_FROZEN");
    assertThat(code(() -> pins.change(uid, in(uid, "135790"), in(uid, "246802"), in(uid, "246802")))).as("冻结时不能改提现密码").isEqualTo("WITHDRAW_FROZEN");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE user_id = ? AND template_code = 'WITHDRAW_FROZEN'", Integer.class, uid)).isPositive();
    assertThat(api.unfreezeWithdraw(uid, null)).containsExactly("PIN_LOCKS");
    assertThat(code(() -> api.verify(uid, in(uid, "135790")))).isEqualTo("OK");
  }

  @Test
  void 第二次锁在30天以外_不冻结() {
    long uid = tgUser();
    pins.setFirst(uid, in(uid, "135790"), in(uid, "135790"));
    for (int i = 0; i < 5; i++) code(() -> api.verify(uid, in(uid, "000123")));
    ((MutableClock) clock).advance(Duration.ofDays(31));
    for (int i = 0; i < 5; i++) code(() -> api.verify(uid, in(uid, "000123")));
    assertThat(api.status(uid).withdrawFrozen()).isFalse();
  }

  @Test
  void 修改要先输对旧密码_输错照样计数_改了是安全事件() {
    long uid = tgUser();
    pins.setFirst(uid, in(uid, "135790"), in(uid, "135790"));
    assertThat(code(() -> pins.change(uid, in(uid, "000123"), in(uid, "246802"), in(uid, "246802")))).isEqualTo("PIN_WRONG");
    assertThat(jdbc.queryForObject("SELECT failed_count FROM account_withdraw_pin WHERE user_id = ?", Integer.class, uid)).isEqualTo(1);
    assertThat(code(() -> pins.change(uid, in(uid, "135790"), in(uid, "246802"), in(uid, "246802")))).isEqualTo("OK");
    assertThat(events(uid)).contains("PIN_CHANGE");
    assertThat(code(() -> api.verify(uid, in(uid, "246802")))).isEqualTo("OK");
  }

  @Test
  void 忘记提现密码_没邮箱走人工_有邮箱收验证码重设_是安全事件() {
    long tg = tgUser();
    assertThat(code(() -> pins.forgotCode(tg))).isEqualTo("NO_EMAIL");
    // 有邮箱的客户（以后绑了邮箱的 TG 客户也一样）
    long uid = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 + FLOOR(RAND() * 1000) FROM account_user", Long.class);
    String email = "pin" + uid + "@example.com";
    jdbc.update("INSERT INTO account_user (id, email, status) VALUES (?, ?, 'ACTIVE')", uid, email);
    pins.setFirst(uid, in(uid, "135790"), in(uid, "135790"));
    pins.forgotCode(uid);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> p = ArgumentCaptor.forClass(Map.class);
    verify(mail, atLeastOnce()).send(eq(email), eq("EMAIL_CODE"), p.capture());
    String sent = p.getValue().get("code");
    assertThat(p.getValue().get("purpose")).isEqualTo("RESET_PIN");
    assertThat(code(() -> pins.forgotReset(uid, "000000".equals(sent) ? "111111" : "000000", in(uid, "246802"), in(uid, "246802")))).isEqualTo("CODE_INVALID");
    assertThat(code(() -> pins.forgotReset(uid, sent, in(uid, "246802"), in(uid, "246802")))).isEqualTo("OK");
    assertThat(events(uid)).contains("PIN_RESET");
    assertThat(code(() -> api.verify(uid, in(uid, "246802")))).isEqualTo("OK");
  }

  @Test
  void 状态接口() throws Exception {
    long uid = tgUser();
    Cookie c = new Cookie("fx_session", sessions.create(uid, "203.0.113.9", "t"));
    assertThat(mvc.perform(get("/api/me/withdraw-pin").cookie(c)).andReturn().getResponse().getContentAsString())
        .isEqualTo("{\"set\":false,\"lockedUntil\":null,\"withdrawFrozen\":false,\"hasEmail\":false}");
  }
}
