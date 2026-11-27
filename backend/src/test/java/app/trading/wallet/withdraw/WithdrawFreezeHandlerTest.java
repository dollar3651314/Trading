package app.trading.wallet.withdraw;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.trading.account.api.WithdrawBlocked;
import app.trading.common.health.AlertProperties;
import app.trading.notification.api.MailApi;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 4b 审查 🟡3：冻结后处理在途提现失败时，除了记日志还要给告警邮箱发告警邮件（OPS_ALERT）。 */
class WithdrawFreezeHandlerTest {

  final WithdrawService withdraws = mock(WithdrawService.class);
  final MailApi mail = mock(MailApi.class);
  final Clock clock = Clock.fixed(Instant.parse("2026-11-27T10:00:00Z"), ZoneOffset.UTC);

  AlertProperties alerts(String... emails) {
    return new AlertProperties(true, List.of(emails), Duration.ofMinutes(30), Duration.ofSeconds(30), Duration.ofMinutes(10), 3, 5);
  }

  @Test
  void 处理失败_发告警邮件_说明客户和要人工处理() {
    when(withdraws.blockInFlight(anyLong(), anyString())).thenThrow(new IllegalStateException("数据库断了"));
    new WithdrawFreezeHandler(withdraws, mail, alerts("ops@example.com", "amos@example.com"), clock).on(new WithdrawBlocked(42L, "提现已冻结：测试"));
    verify(mail).send(eq("ops@example.com"), eq("OPS_ALERT"), argThat(p -> p.get("detail").contains("客户 42") && p.get("detail").contains("IllegalStateException")
        && "2026-11-27T10:00:00Z".equals(p.get("since"))));
    verify(mail).send(eq("amos@example.com"), eq("OPS_ALERT"), anyMap());
  }

  @Test
  void 处理成功_不发邮件_没配告警邮箱时失败也只记日志() {
    new WithdrawFreezeHandler(withdraws, mail, alerts("ops@example.com"), clock).on(new WithdrawBlocked(42L, "账户已冻结"));
    when(withdraws.blockInFlight(anyLong(), anyString())).thenThrow(new IllegalStateException("x"));
    new WithdrawFreezeHandler(withdraws, mail, alerts(), clock).on(new WithdrawBlocked(43L, "账户已冻结"));
    verify(mail, never()).send(anyString(), anyString(), anyMap());
  }
}
