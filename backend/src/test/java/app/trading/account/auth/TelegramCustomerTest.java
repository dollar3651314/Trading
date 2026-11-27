package app.trading.account.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import app.trading.account.api.CustomerAdminApi;
import app.trading.account.api.TelegramAccountApi;
import app.trading.account.api.TelegramAccountApi.TgAccount;
import jakarta.servlet.http.Cookie;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 纯 Telegram 客户（机器人开户，没有邮箱、没有资料）在网页和后台的样子（TG 机器人第 1 批，技术方案 07 页第 2b 节）：
 * 会话信息邮箱为空、带 TG 用户名；资料页不报错、第一次保存时插入；没有密码不能用邮箱登录；后台能按 TG 用户名、编号搜到。
 * 网页会话这里直接建（机器人一键登录是批 2）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TelegramCustomerTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired TelegramAccountApi telegram;
  @Autowired SessionService sessions;
  @Autowired CustomerAdminApi customers;
  @Autowired ObjectMapper json;

  record Tg(TgAccount account, Cookie cookie) {}

  Tg open(String username) {
    long tgId = 9_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
    TgAccount a = telegram.openAccount(tgId, username);
    return new Tg(a, new Cookie("fx_session", sessions.create(a.userId(), "10.1.2.3", "test")));
  }

  JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, Cookie c, String body, int status) throws Exception {
    if (body != null) b = b.contentType(MediaType.APPLICATION_JSON).content(body);
    MockHttpServletResponse r = mvc.perform(b.cookie(c)).andReturn().getResponse();
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
    return r.getContentAsString().isEmpty() ? null : json.readTree(r.getContentAsString());
  }

  @Test
  void 会话信息_邮箱为空_带TG用户名() throws Exception {
    Tg t = open("tg_user_" + ThreadLocalRandom.current().nextInt(1_000_000));
    JsonNode s = call(get("/api/me/session"), t.cookie(), null, 200);
    assertThat(s.get("email").isNull()).isTrue();
    assertThat(s.get("telegramUsername").asString()).isEqualTo(t.account().username());
    assertThat(s.get("userId").asString()).isEqualTo(String.valueOf(t.account().userId()));
  }

  @Test
  void 资料页不报错_第一次保存时插入() throws Exception {
    Tg t = open(null);
    JsonNode p = call(get("/api/me/profile"), t.cookie(), null, 200);
    assertThat(p.get("email").isNull()).isTrue();
    assertThat(p.get("firstName").isNull()).isTrue();
    assertThat(p.get("locked").asBoolean()).isFalse();
    String body = "{\"firstName\":\"San\",\"lastName\":\"Zhang\",\"birthDate\":\"1990-05-01\",\"nationality\":\"CN\"}";
    JsonNode saved = call(put("/api/me/profile"), t.cookie(), body, 200);
    assertThat(saved.get("firstName").asString()).isEqualTo("San");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_profile WHERE user_id = ?", Integer.class, t.account().userId())).isEqualTo(1);
    JsonNode again = call(put("/api/me/profile"), t.cookie(), body.replace("San", "Si"), 200);
    assertThat(again.get("firstName").asString()).as("第二次是更新").isEqualTo("Si");
  }

  @Test
  void 没有密码_不能改密码() throws Exception {
    Tg t = open(null);
    MockHttpServletResponse r = mvc.perform(post("/api/auth/password/change").cookie(t.cookie()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"currentPassword\":\"whatever1\",\"newPassword\":\"password123\"}")).andReturn().getResponse();
    assertThat(r.getContentAsString()).contains("CURRENT_PASSWORD_WRONG");
  }

  @Test
  void 后台按TG用户名和编号能搜到_显示TG用户名() {
    String name = "find_me_" + ThreadLocalRandom.current().nextInt(1_000_000);
    Tg t = open(name);
    var byName = customers.customers("@" + name, null, null, 0, 10).items();
    assertThat(byName).singleElement().satisfies(c -> {
      assertThat(c.id()).isEqualTo(t.account().userId());
      assertThat(c.email()).isNull();
      assertThat(c.telegramUsername()).isEqualTo(name);
      assertThat(c.telegramId()).isEqualTo(t.account().telegramId());
    });
    assertThat(customers.search(String.valueOf(t.account().telegramId()), 10)).containsExactly(t.account().userId());
  }
}
