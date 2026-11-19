package app.trading.payment.fake;

import app.trading.common.e2e.E2eGuard;
import app.trading.payment.core.PaymentService;
import app.trading.payment.core.ProviderRegistry;
import app.trading.payment.topup.TopUpProperties;
import app.trading.payment.topup.TopUpProvider;
import jakarta.annotation.PreDestroy;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 端到端测试的假 TopUP（只在 profile e2e 下存在）：启动时在本机起一个 {@link FakeTopUp}（Key、Secret 每次随机），把 TOPUP 提供方指过去，
 * 充值、提现走的是和生产一样的支付接口层（签名、回调验签、防重），只是 TopUP 换成了替身。回调直接交给支付接口层处理（和 TopUP 发 HTTP 回调同一个入口方法，同样验签）。
 * <ul>
 *   <li>{@code POST /api/e2e/wallet/deposit {chain, address, amount}}：向充值地址转入 amount USDT 并确认（TopUP 回调到账）；返回交易哈希、客户号、入账状态</li>
 *   <li>{@code POST /api/e2e/wallet/withdrawals/{id}/confirm}：这笔提现在 TopUP 由人工放款审核通过、广播、链上成功（依次回调 approved、broadcast、completed）</li>
 * </ul>
 */
@Component
@Profile(E2eGuard.PROFILE)
class E2ePayment {

  final FakeTopUp fake;
  final PaymentService payments;

  E2ePayment(ProviderRegistry registry, PaymentService payments) {
    SecureRandom r = new SecureRandom();
    byte[] secret = new byte[24];
    r.nextBytes(secret);
    this.fake = new FakeTopUp("topup_e2e_" + HexFormat.of().formatHex(secret, 0, 4), HexFormat.of().formatHex(secret)).start();
    this.payments = payments;
    registry.register(new TopUpProvider(new TopUpProperties(fake.baseUrl(), fake.apiKey, fake.apiSecret, Duration.ofSeconds(10))));
    fake.onCallback = cb -> {
      Map<String, String> h = new LinkedHashMap<>();
      cb.headers().forEach((k, v) -> h.put(k.toLowerCase(), v));
      payments.callback(TopUpProvider.CODE, h, cb.body());
    };
  }

  @PreDestroy
  void stop() {
    fake.close();
  }

  record Deposit(String chain, String address, String amount) {}

  @RestController
  @Profile(E2eGuard.PROFILE)
  static class Api {

    private final E2ePayment e2e;

    Api(E2ePayment e2e) {
      this.e2e = e2e;
    }

    static String chain(String network) {
      return switch (network == null ? "" : network) {
        case "ERC20" -> "eth";
        case "BSC" -> "bsc";
        case "TRC20" -> "tron";
        default -> null;
      };
    }

    @PostMapping("/api/e2e/wallet/deposit")
    ResponseEntity<Map<String, Object>> deposit(@RequestBody Deposit d) {
      String chain = chain(d.chain());
      if (chain == null || d.address() == null || d.amount() == null) return ResponseEntity.badRequest().build();
      Optional<FakeTopUp.Callback> cb = e2e.fake.deposit(chain, d.address(), d.amount());
      if (cb.isEmpty()) return ResponseEntity.notFound().build();
      FakeTopUp.Deposit last = e2e.fake.deposits.getLast();
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("txHash", last.txid());
      out.put("userId", last.customerId());
      out.put("status", "CREDITED");
      return ResponseEntity.ok(out);
    }

    /**
     * 兼容第 0 批的端到端脚本：原来链替身要"出块"才够确认数；TopUP 只在确认后回调一次（{@code deposit} 接口已经入账），这里什么也不做。
     */
    @PostMapping("/api/e2e/wallet/mine")
    ResponseEntity<Map<String, Object>> mine() {
      return ResponseEntity.ok(Map.of("head", 0));
    }

    /** id 是客户端和后台看到的提现对外编号（交给 TopUP 的 merchant_ref 是 trading-<id>） */
    @PostMapping("/api/e2e/wallet/withdrawals/{id}/confirm")
    ResponseEntity<Map<String, String>> confirm(@PathVariable long id) {
      Optional<FakeTopUp.Withdrawal> w = e2e.fake.byMerchantRef(app.trading.payment.core.PaymentService.merchantRef(id));
      if (w.isEmpty()) return ResponseEntity.notFound().build();
      String no = w.get().no;
      e2e.fake.moveWithdrawal(no, "approved", null, null);
      e2e.fake.moveWithdrawal(no, "broadcast", null, null);
      e2e.fake.moveWithdrawal(no, "completed", String.format("0xe2e%061x", id), null);
      return ResponseEntity.ok(Map.of("id", String.valueOf(id), "withdrawalNo", no));
    }
  }
}
