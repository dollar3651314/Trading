package app.trading.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import app.trading.account.api.TelegramEmergencyApi;
import app.trading.notification.api.MailApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 后台"应急开关"（TG 机器人第 5 批，TB-34）：只有配置里写死编号的那个后台账号（Amos）能开关，别的超管也不行（不靠权限点，记失败审计）；
 * 开、关都要再输后台密码（输错记 admin.reauth）+ 原因；写审计、发告警邮件；页面有开关记录（后台和服务器终端的）；看页面要 emergency:view。
 */
@TestPropertySource(properties = {"trading.alert.emails=ops@example.com", "trading.admin.owner-id=" + AdminEmergencyTest.OWNER_ID})
class AdminEmergencyTest extends AdminTestSupport {

  static final long OWNER_ID = 9_000_000_001L;

  @Autowired ObjectMapper json;
  @Autowired TelegramEmergencyApi emergency;
  @MockitoBean MailApi mail;

  @AfterEach
  void off() {
    if (emergency.state().on()) emergency.set(false, "admin:" + OWNER_ID, "test", "测试结束");
  }

  Admin owner() throws Exception {
    return fixedSuperAdmin(OWNER_ID, "amos_owner_t");
  }

  String set(Admin a, String body) throws Exception {
    MockHttpServletResponse r = call(post("/api/admin/emergency"), a.cookie(), body);
    return r.getStatus() + " " + r.getContentAsString();
  }

  JsonNode view(Admin a) throws Exception {
    MockHttpServletResponse r = call(get("/api/admin/emergency"), a.cookie(), null);
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    return json.readTree(r.getContentAsString());
  }

  @Test
  void 只有Amos的账号能开关_别的超管不行_要后台密码和原因_审计_告警邮件_开关记录() throws Exception {
    Admin amos = owner();
    Admin otherSuper = superAdmin();
    Admin viewer = withRoles(role(Permission.EMERGENCY_VIEW));
    Admin nobody = withRoles(role(Permission.CUSTOMER_VIEW));
    String on = "{\"on\":true,\"reason\":\"机器人被封\",\"password\":\"" + PASSWORD + "\"}";

    assertThat(call(get("/api/admin/emergency"), nobody.cookie(), null).getStatus()).as("看页面要 emergency:view").isEqualTo(403);
    JsonNode v = view(viewer);
    assertThat(v.get("on").asBoolean()).isFalse();
    assertThat(v.get("canOperate").asBoolean()).isFalse();
    assertThat(v.get("ownerConfigured").asBoolean()).isTrue();
    assertThat(view(otherSuper).get("canOperate").asBoolean()).as("别的超管也不行").isFalse();
    assertThat(view(amos).get("canOperate").asBoolean()).isTrue();

    assertThat(set(otherSuper, on)).startsWith("403").contains("FORBIDDEN");
    assertThat(set(viewer, on)).startsWith("403");
    assertThat(audits("telegram.emergency_on")).as("别人点了记失败审计").contains("FAILURE " + otherSuper.username() + " NOT_OWNER");
    assertThat(set(amos, "{\"on\":true,\"password\":\"" + PASSWORD + "\"}")).as("原因必填").startsWith("400");
    assertThat(set(amos, "{\"on\":true,\"reason\":\"x\",\"password\":\"wrong\"}")).contains("CURRENT_PASSWORD_WRONG");
    assertThat(audits("admin.reauth")).anyMatch(l -> l.startsWith("FAILURE amos_owner_t"));
    assertThat(set(amos, "{\"reason\":\"x\",\"password\":\"" + PASSWORD + "\"}")).as("要说开还是关").startsWith("400");
    assertThat(emergency.state().on()).isFalse();
    verify(mail, never()).send(eq("ops@example.com"), eq("OPS_TG_EMERGENCY"), argThat(p -> true));

    // Amos 打开
    String r = set(amos, on);
    assertThat(r).startsWith("200");
    JsonNode after = json.readTree(r.substring(4));
    assertThat(after.get("on").asBoolean()).isTrue();
    assertThat(after.get("changedBy").asString()).isEqualTo("amos_owner_t");
    assertThat(after.get("history").get(0).get("actor").asString()).isEqualTo("admin:" + OWNER_ID);
    assertThat(after.get("history").get(0).get("reason").asString()).isEqualTo("机器人被封");
    assertThat(emergency.state().on()).isTrue();
    assertThat(audits("telegram.emergency_on")).anyMatch(l -> l.equals("SUCCESS amos_owner_t 机器人被封"));
    verify(mail).send(eq("ops@example.com"), eq("OPS_TG_EMERGENCY"),
        argThat(p -> p.get("actionZh").equals("打开") && p.get("reason").equals("机器人被封") && p.get("admin").contains("#" + OWNER_ID)));
    assertThat(set(amos, on)).as("已经开着").contains("EMERGENCY_UNCHANGED");

    // 关闭：同样要密码和原因
    assertThat(set(otherSuper, "{\"on\":false,\"reason\":\"y\",\"password\":\"" + PASSWORD + "\"}")).startsWith("403");
    assertThat(emergency.state().on()).as("别人关不掉").isTrue();
    assertThat(set(amos, "{\"on\":false,\"reason\":\"机器人恢复了\",\"password\":\"" + PASSWORD + "\"}")).startsWith("200");
    assertThat(emergency.state().on()).isFalse();
    assertThat(audits("telegram.emergency_off")).anyMatch(l -> l.equals("SUCCESS amos_owner_t 机器人恢复了"));
    verify(mail).send(eq("ops@example.com"), eq("OPS_TG_EMERGENCY"), argThat(p -> p.get("actionZh").equals("关闭")));
    assertThat(view(viewer).get("history").get(0).get("on").asBoolean()).isFalse();
  }

  @Test
  void 菜单在系统下面() {
    assertThat(Menus.TREE.stream().filter(i -> i.key().equals("system")).findFirst().orElseThrow().children())
        .anyMatch(i -> i.key().equals("emergency") && i.permission() == Permission.EMERGENCY_VIEW && i.path().equals("/system/emergency"));
  }
}
