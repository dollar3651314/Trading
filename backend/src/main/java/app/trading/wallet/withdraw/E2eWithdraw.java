package app.trading.wallet.withdraw;

import app.trading.common.e2e.E2eGuard;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 端到端测试的提现推进（S19，只在 profile e2e 下存在）：{@code POST /api/e2e/wallet/withdrawals/advance} 立即跑一次定时推进
 * （冷静期满 → 待审核、交给钱包提供方、查付款结果），不用等 30 秒。
 * 第 1 批起付款走支付接口层（假 TopUP，payment.fake.E2ePayment），这里不再替代广播器；"放款成功"由 E2ePayment 的 confirm 接口让假 TopUP 回调。
 */
@RestController
@Profile(E2eGuard.PROFILE)
class E2eWithdraw {

  private final WithdrawService service;

  E2eWithdraw(WithdrawService service) {
    this.service = service;
  }

  @PostMapping("/api/e2e/wallet/withdrawals/advance")
  ResponseEntity<Void> advance() {
    service.advance();
    service.track();
    return ResponseEntity.noContent().build();
  }
}
