package app.trading.wallet.withdraw;

import app.trading.account.api.WithdrawBlocked;
import app.trading.common.health.AlertProperties;
import app.trading.notification.api.MailApi;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 客户被冻结（账户冻结、提现冻结）以后处理在途的提现（第 4 批审查 🔴4a-1，{@link WithdrawService#blockInFlight}）。
 * 在冻结的事务提交以后执行（没有事务时直接执行）。这里出错时记日志并给告警邮箱发告警邮件（OPS_ALERT，4b 审查 🟡3，收件人为空只记日志）——
 * 审核通过、冷静期结束、延迟期满、交钱包提供方前都会再查一次冻结，钱挡得住，但在途的没有取消退回，要人工看一下。
 */
@Component
class WithdrawFreezeHandler {

  private static final Logger log = LoggerFactory.getLogger(WithdrawFreezeHandler.class);
  private final WithdrawService withdraws;
  private final MailApi mail;
  private final AlertProperties alerts;
  private final Clock clock;

  WithdrawFreezeHandler(WithdrawService withdraws, MailApi mail, AlertProperties alerts, Clock clock) {
    this.withdraws = withdraws;
    this.mail = mail;
    this.alerts = alerts;
    this.clock = clock;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  void on(WithdrawBlocked e) {
    try {
      int n = withdraws.blockInFlight(e.userId(), e.reason());
      if (n > 0) log.warn("客户 {} {}：取消并退回 {} 笔在途提现", e.userId(), e.reason(), n);
    } catch (RuntimeException ex) {
      log.error("客户 {} 冻结后处理在途提现失败（各环节仍会再查冻结）", e.userId(), ex);
      alert(e, ex);
    }
  }

  private void alert(WithdrawBlocked e, RuntimeException ex) {
    Map<String, String> p = Map.of("itemZh", "冻结后处理在途提现失败", "itemEn", "Failed to stop in-flight withdrawals after a freeze", "detail",
        "客户 " + e.userId() + "（" + e.reason() + "）：" + ex.getClass().getSimpleName() + "。各环节仍会再查冻结、钱不会交出去，但冷静期 / 待审核 / 延迟中的没有自动取消退回，请在后台出金审核里人工处理。",
        "since", Instant.now(clock).toString());
    var to = alerts.recipients();
    if (to.isEmpty()) log.warn("没有配置告警邮箱（FX_ALERT_EMAILS），冻结后处理在途提现失败只记了日志");
    for (String email : to) {
      try {
        mail.send(email, "OPS_ALERT", p);
      } catch (RuntimeException mailError) {
        log.error("冻结后处理在途提现失败的告警邮件写入发送任务失败", mailError);
      }
    }
  }
}
