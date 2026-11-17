package app.trading.payment.topup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.trading.payment.api.PayNetwork;
import app.trading.payment.api.PaymentApi;
import app.trading.payment.api.PayoutState;
import app.trading.payment.fake.FakeTopUp;
import app.trading.payment.provider.PaymentProvider;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * TopUP 适配对着假 TopUP（按 TopUP 开放 API 的签名和字段）：请求签名、防重键、取地址的 403 绕法、提现、查询、余额、到账列表、回调验签和解析。
 * 不起 Spring、不连数据库。
 */
class TopUpProviderTest {

  static final FakeTopUp FAKE = new FakeTopUp("topup_unit_key", "unit-secret-" + System.nanoTime()).start();
  final TopUpProvider p = new TopUpProvider(new TopUpProperties(FAKE.baseUrl(), FAKE.apiKey, FAKE.apiSecret, Duration.ofSeconds(3)));

  @BeforeEach
  void reset() {
    FAKE.reset();
  }

  @AfterAll
  static void stop() {
    FAKE.close();
  }

  static Map<String, String> lower(Map<String, String> h) {
    Map<String, String> m = new LinkedHashMap<>();
    h.forEach((k, v) -> m.put(k.toLowerCase(), v));
    return m;
  }

  @Test
  void 签名对才收_Secret不对一律401() {
    p.ensureCustomer("1001");
    assertThat(FAKE.customers).containsKey("1001");
    TopUpProvider wrong = new TopUpProvider(new TopUpProperties(FAKE.baseUrl(), FAKE.apiKey, "not-the-secret", Duration.ofSeconds(3)));
    assertThatThrownBy(() -> wrong.ensureCustomer("1002")).isInstanceOfSatisfying(PaymentProvider.ProviderError.class, e -> {
      assertThat(e.status).isEqualTo(401);
      assertThat(e.code).isEqualTo("invalid_signature");
    });
  }

  @Test
  void 没配置算未接入_不发请求() {
    TopUpProvider none = new TopUpProvider(new TopUpProperties("", "", "", Duration.ofSeconds(3)));
    assertThat(none.connected()).isFalse();
    assertThatThrownBy(() -> none.balance()).isInstanceOf(PaymentApi.Unavailable.class);
    assertThat(FAKE.requests).isEmpty();
  }

  @Test
  void 取地址_建单返回403没开订单模式也拿得到_同一客户同一条链同一个地址_TG4前的绕法() {
    String a = p.address("2001", PayNetwork.TRC20);
    assertThat(a).matches("T[1-9A-HJ-NP-Za-km-z]{33}");
    assertThat(p.address("2001", PayNetwork.TRC20)).isEqualTo(a);
    assertThat(FAKE.requests).contains("POST /api/v1/orders/", "GET /api/v1/customers/");
    assertThat(p.address("2001", PayNetwork.ERC20)).matches("0x[0-9a-f]{40}");
  }

  @Test
  void 提现_每次传链_防重键相同只建一笔_查询_余额() {
    var s1 = p.submitPayout("trading-wd-1", "1", "3001", PayNetwork.BSC, "0x" + "a".repeat(40), new BigDecimal("99.5"));
    var s2 = p.submitPayout("trading-wd-1", "1", "3001", PayNetwork.BSC, "0x" + "a".repeat(40), new BigDecimal("99.5"));
    assertThat(s1.ref()).isEqualTo(s2.ref());
    assertThat(s1.state()).isEqualTo(PayoutState.PENDING);
    assertThat(FAKE.created.get()).isEqualTo(1);
    assertThat(FAKE.withdrawals.get(s1.ref()).chain).isEqualTo("bsc");
    // 同一个防重键、不同的请求：TopUP 返回 409
    assertThatThrownBy(() -> p.submitPayout("trading-wd-1", "1", "3001", PayNetwork.BSC, "0x" + "a".repeat(40), new BigDecimal("100")))
        .isInstanceOfSatisfying(PaymentProvider.ProviderError.class, e -> assertThat(e.code).isEqualTo("idempotency_conflict"));
    FAKE.moveWithdrawal(s1.ref(), "completed", "0x" + "c".repeat(64), null);
    var v = p.payout(s1.ref()).orElseThrow();
    assertThat(v.state()).isEqualTo(PayoutState.COMPLETED);
    assertThat(v.txHash()).isEqualTo("0x" + "c".repeat(64));
    assertThat(v.amount()).isEqualByComparingTo("99.5");
    assertThat(v.merchantRef()).isEqualTo("1");
    assertThat(p.payout("WD-nope")).isEmpty();
    assertThat(p.balance().available()).isEqualByComparingTo("99900.5");
  }

  @Test
  void TopUP不通或限流_算暂时不可用() {
    FAKE.down = true;
    assertThatThrownBy(p::balance).isInstanceOf(PaymentApi.Unavailable.class);
  }

  @Test
  void 到账列表_按客户() {
    String a = p.address("4001", PayNetwork.TRC20);
    FAKE.deposit("tron", a, "12.5");
    var list = p.deposits("4001");
    assertThat(list).singleElement().satisfies(d -> {
      assertThat(d.network()).isEqualTo(PayNetwork.TRC20);
      assertThat(d.amount()).isEqualByComparingTo("12.5");
      assertThat(d.address()).isEqualTo(a);
    });
  }

  @Test
  void 回调验签_解析到账和提现_不认识的事件忽略_签名错时间戳过期事件ID对不上都拒收() {
    String a = p.address("5001", PayNetwork.ERC20);
    FakeTopUp.Callback dep = FAKE.deposit("eth", a, "88").orElseThrow();
    var e = p.parseCallback(lower(dep.headers()), dep.body()).orElseThrow();
    assertThat(e).isInstanceOfSatisfying(PaymentProvider.DepositEvent.class, d -> {
      assertThat(d.eventId()).isEqualTo(dep.eventId());
      assertThat(d.customerId()).isEqualTo("5001");
      assertThat(d.network()).isEqualTo(PayNetwork.ERC20);
      assertThat(d.amount()).isEqualByComparingTo("88");
    });
    var s = p.submitPayout("trading-wd-9", "9", "5001", PayNetwork.TRC20, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", BigDecimal.TEN);
    FakeTopUp.Callback upd = FAKE.moveWithdrawal(s.ref(), "rejected", null, "风险地址");
    assertThat(p.parseCallback(lower(upd.headers()), upd.body()).orElseThrow()).isInstanceOfSatisfying(PaymentProvider.PayoutEvent.class, w -> {
      assertThat(w.ref()).isEqualTo(s.ref());
      assertThat(w.merchantRef()).isEqualTo("9");
      assertThat(w.state()).isEqualTo(PayoutState.REJECTED);
      assertThat(w.reason()).isEqualTo("风险地址");
    });
    FakeTopUp.Callback other = FAKE.emit("order.matched", Map.of("order_no", "x"));
    assertThat(p.parseCallback(lower(other.headers()), other.body())).isEmpty();

    Map<String, String> bad = lower(dep.headers());
    bad.put("x-topup-signature", "0".repeat(64));
    assertThatThrownBy(() -> p.parseCallback(bad, dep.body())).isInstanceOf(PaymentProvider.BadSignature.class);
    assertThatThrownBy(() -> p.parseCallback(lower(dep.headers()), dep.body().replace("88.00", "880.00"))).isInstanceOf(PaymentProvider.BadSignature.class);
    Map<String, String> stale = lower(dep.headers());
    String ts = String.valueOf(java.time.Instant.now().getEpochSecond() - 301);
    stale.put("x-topup-timestamp", ts);
    stale.put("x-topup-signature", TopUpSigner.signCallback(FAKE.apiSecret, ts, dep.body()));
    assertThatThrownBy(() -> p.parseCallback(stale, dep.body())).isInstanceOf(PaymentProvider.BadSignature.class).hasMessageContaining("时间戳");
    Map<String, String> otherId = lower(dep.headers());
    otherId.put("x-topup-event-id", "evt_other");
    assertThatThrownBy(() -> p.parseCallback(otherId, dep.body())).isInstanceOf(PaymentProvider.BadSignature.class);
  }

  @Test
  void 签名和TopUP开发者文档的算法一致() {
    // TopUP site/src/docs 的写法：hex(HMAC-SHA256(secret, ts \n method \n path \n hex(sha256(body))))；空请求体的 SHA-256 是固定值
    assertThat(TopUpSigner.sha256("")).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    String sig = TopUpSigner.signRequest("s", "1700000000", "GET", "/api/v1/balance/", "");
    assertThat(sig).isEqualTo(TopUpSigner.hmac("s", "1700000000\nGET\n/api/v1/balance/\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
    assertThat(TopUpSigner.signCallback("s", "1", "{}")).isEqualTo(TopUpSigner.hmac("s", "1\n{}"));
  }
}
