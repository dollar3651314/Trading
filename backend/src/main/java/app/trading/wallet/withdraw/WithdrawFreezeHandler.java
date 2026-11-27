package app.trading.wallet.withdraw;

import app.trading.account.api.WithdrawBlocked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 客户被冻结（账户冻结、提现冻结）以后处理在途的提现（第 4 批审查 🔴4a-1，{@link WithdrawService#blockInFlight}）。
 * 在冻结的事务提交以后执行（没有事务时直接执行）；这里出错只记日志——审核通过、冷静期结束、延迟期满、交钱包提供方前都会再查一次冻结，挡得住。
 */
@Component
class WithdrawFreezeHandler {

  private static final Logger log = LoggerFactory.getLogger(WithdrawFreezeHandler.class);
  private final WithdrawService withdraws;

  WithdrawFreezeHandler(WithdrawService withdraws) {
    this.withdraws = withdraws;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  void on(WithdrawBlocked e) {
    try {
      int n = withdraws.blockInFlight(e.userId(), e.reason());
      if (n > 0) log.warn("客户 {} {}：取消并退回 {} 笔在途提现", e.userId(), e.reason(), n);
    } catch (RuntimeException ex) {
      log.error("客户 {} 冻结后处理在途提现失败（各环节仍会再查冻结）", e.userId(), ex);
    }
  }
}
