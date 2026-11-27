package app.trading.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import app.trading.common.config.ConfigKey;
import app.trading.notification.api.MailApi;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * TB-37 资金配置下限（第 4 批 4c）：白名单生效 ≥ 24 小时、冷静期 ≥ 2 小时、每天提现累计 ≤ 30,000；资金配置（withdraw、telegram 分组）改值发告警邮件。
 */
@TestPropertySource(properties = "trading.alert.emails=ops@example.com")
class AdminFundsConfigTest extends AdminTestSupport {

  @Autowired app.trading.common.config.SystemConfig config;
  @MockitoBean MailApi mail;

  String save(Admin a, String key, String value) throws Exception {
    MockHttpServletResponse r = call(put("/api/admin/configs/" + key), a.cookie(), "{\"value\":\"" + value + "\",\"reason\":\"测试\"}");
    return r.getStatus() + " " + r.getContentAsString();
  }

  @Test
  void 冷静期白名单生效有下限_每天累计有上限_改资金配置发告警() throws Exception {
    Admin s = superAdmin();
    try {
      assertThat(save(s, "withdraw.coolingHours", "1")).startsWith("400").contains("belowFloor");
      assertThat(save(s, "whitelist.effectiveHours", "23")).startsWith("400").contains("belowFloor");
      assertThat(save(s, "withdraw.dailyLimit", "30001")).startsWith("400").contains("aboveCeiling");
      assertThat(save(s, "telegram.withdraw.dailyLimit", "2001")).as("TG 每天额度上限 2,000").startsWith("400");
      verify(mail, never()).send(anyString(), anyString(), anyMap());
      assertThat(save(s, "withdraw.coolingHours", "3")).startsWith("200");
      verify(mail).send(eq("ops@example.com"), eq("OPS_ALERT"), argThat(p -> p.get("itemZh").contains("withdraw.coolingHours") && p.get("detail").contains("→ 3")));
      assertThat(save(s, "whitelist.effectiveHours", "24")).startsWith("200");
      assertThat(save(s, "notify.marginCallThrottleMinutes", "31")).as("不是资金配置").startsWith("200");
      verify(mail, never()).send(anyString(), eq("OPS_ALERT"), argThat(p -> p.get("itemZh").contains("notify.")));
    } finally {
      config.setBySystem(ConfigKey.WITHDRAW_COOLING_HOURS, "2", LocalDateTime.now());
      config.setBySystem(ConfigKey.WHITELIST_EFFECTIVE_HOURS, "24", LocalDateTime.now());
      config.setBySystem(ConfigKey.NOTIFY_MARGIN_CALL_THROTTLE_MINUTES, "5", LocalDateTime.now());
    }
  }
}
