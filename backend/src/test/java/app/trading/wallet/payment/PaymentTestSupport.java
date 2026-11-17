package app.trading.wallet.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import app.trading.notification.api.MailApi;
import app.trading.payment.api.PaymentApi;
import app.trading.payment.core.PaymentService;
import app.trading.payment.core.ProviderRegistry;
import app.trading.payment.fake.FakeTopUp;
import app.trading.payment.topup.TopUpProperties;
import app.trading.payment.topup.TopUpProvider;
import app.trading.support.FixedClockConfig;
import app.trading.support.MutableClock;
import app.trading.trading.api.Bucket;
import app.trading.trading.api.LedgerApi;
import app.trading.trading.api.LedgerCommand;
import app.trading.trading.api.LedgerEntryType;
import app.trading.wallet.withdraw.WithdrawSenders;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 支付接口层测试的公共部分：真实 MySQL、Redis（C37），TopUP 换成本机的假 TopUP（{@link FakeTopUp}，按 TopUP 开放 API 的签名和字段）。
 * 每个测试前把 TOPUP 提供方指向假 TopUP、三条链的付款器换成支付接口层；测试后恢复成没配置的 TopUP（不影响共用同一个 Spring 上下文的其他测试）。
 * 假 TopUP 的回调默认攒着，由测试用 {@link #deliver} 发到 Trading 的回调接口（{@code POST /api/pay/callback/topup}），可以重复、乱序、不发。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
abstract class PaymentTestSupport {

  static final FakeTopUp FAKE = new FakeTopUp("topup_test_key", "test-secret-not-real-" + System.nanoTime()).start();

  @Autowired ProviderRegistry registry;
  @Autowired PaymentService payments;
  @Autowired PaymentApi api;
  @Autowired TopUpProperties topupProps;
  @Autowired WithdrawSenders senders;
  @Autowired PaymentWithdrawSender paymentSender;
  @Autowired LedgerApi ledger;
  @Autowired JdbcTemplate jdbc;
  @Autowired Clock clock;
  @Autowired MockMvc mvc;
  @MockitoBean MailApi mail;

  static TopUpProvider fakeProvider() {
    return new TopUpProvider(new TopUpProperties(FAKE.baseUrl(), FAKE.apiKey, FAKE.apiSecret, Duration.ofSeconds(3)));
  }

  @BeforeEach
  void useFakeTopUp() {
    FAKE.reset();
    FAKE.onCallback = null;
    registry.register(fakeProvider());
    paymentSender.register();
    jdbc.update("UPDATE common_config SET value = 'TOPUP' WHERE config_key = 'payment.provider'");
    jdbc.update("UPDATE common_config SET value = 'true' WHERE config_key LIKE 'payment.network.%'");
  }

  @AfterEach
  void restore() {
    registry.register(new TopUpProvider(topupProps));
    registry.remove("STANDIN");
    jdbc.update("UPDATE common_config SET value = 'TOPUP' WHERE config_key = 'payment.provider'");
    jdbc.update("UPDATE common_config SET value = 'true' WHERE config_key LIKE 'payment.network.%'");
    ((MutableClock) clock).reset();
  }

  MutableClock time() {
    return (MutableClock) clock;
  }

  /** 新客户（可选有余额、KYC 已通过） */
  long user(String balance, boolean kyc) {
    long uid = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 + FLOOR(RAND() * 1000) FROM account_user", Long.class);
    jdbc.update("INSERT INTO account_user (id, email, status) VALUES (?, ?, 'ACTIVE')", uid, "pay" + uid + "@example.com");
    ledger.openAccount(uid);
    if (new BigDecimal(balance).signum() > 0) {
      ledger.apply(new LedgerCommand(uid, Bucket.BALANCE, new BigDecimal(balance), LedgerEntryType.DEPOSIT, "TEST", "dep-" + uid, "测试入金"));
    }
    if (kyc) {
      jdbc.update("INSERT INTO account_kyc_application (user_id, doc_type, doc_number, front_key, back_key, selfie_key, status) VALUES (?, 'PASSPORT', ?, 'f', 'b', 's', 'APPROVED')",
          uid, "P" + uid);
    }
    return uid;
  }

  BigDecimal balance(long uid) {
    return ledger.snapshot(uid).balance();
  }

  BigDecimal frozen(long uid) {
    return ledger.snapshot(uid).withdrawFrozen();
  }

  /** 把假 TopUP 的一个回调发给 Trading，返回 HTTP 状态 */
  int deliver(FakeTopUp.Callback cb) throws Exception {
    MockHttpServletRequestBuilder r = post("/api/pay/callback/topup").contentType(MediaType.APPLICATION_JSON).content(cb.body());
    for (var h : cb.headers().entrySet()) if (!h.getKey().equalsIgnoreCase("Content-Type")) r.header(h.getKey(), h.getValue());
    MockHttpServletResponse res = mvc.perform(r).andReturn().getResponse();
    return res.getStatus();
  }

  /** 把攒着的回调按顺序全部发出去 */
  void deliverAll() throws Exception {
    var all = new java.util.ArrayList<>(FAKE.pending);
    FAKE.pending.clear();
    for (FakeTopUp.Callback cb : all) deliver(cb);
  }
}
