package app.trading.wallet.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import app.trading.notification.api.MailApi;
import app.trading.payment.api.PayNetwork;
import app.trading.payment.api.PaymentApi;
import app.trading.payment.api.PaymentListener;
import app.trading.payment.core.ProviderRegistry;
import app.trading.payment.fake.FakeTopUp;
import app.trading.payment.topup.TopUpProperties;
import app.trading.payment.topup.TopUpProvider;
import app.trading.support.FixedClockConfig;
import app.trading.trading.api.LedgerApi;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 充值（S12a），第 0 批推迟、第 1 批"改了搬"：原来是链替身 + 扫块，现在换成假 TopUP（{@link FakeTopUp}）+ 到账回调。
 * 只改造数据的步骤（"链上转一笔、出块、扫块"→"假 TopUP 到账、发回调"），断言要测的东西不变：入账金额、只入账一次、到账通知只发一次、
 * 不是我们的地址不记、冲正不扣成负数、推送、充值记录接口、健康检查、对外编号。真实 MySQL（C37）。
 * <p>原文件 15 个测试的对照（不能原样搬的写明原因，是需求变化，不是放宽）：
 * <ul>
 *   <li>ERC20 达到 12 个确认后入账 → 搬：到账后入账、重放不重复入账、通知只发一次。"之前是待确认、11 个确认"<b>没法搬</b>：TopUP 只在确认后回调一次，
 *       Trading 看不到"待确认"（TG-5，需求 02 第一节）</li>
 *   <li>假币不入账（AC-08）→ 需求淘汰 AC-08（TopUP 只认配置的 USDT 合约）；同一处断言改测"伪造的回调（签名不对）不入账"（AC-W2）</li>
 *   <li>最小充值 1 USDT → 搬："1 USDT 入账"照旧；"0.99 记 BELOW_MIN"<b>没法搬</b>：TopUP 不通知低于 1 USDT 的到账（TD-2 B），改断言"不入账、不出现"（AC-W11）</li>
 *   <li>确认前区块被回滚作废 → <b>没法搬</b>：TopUP 不发"作废"（TG-5）</li>
 *   <li>同一笔重复扫到只记一次 → 搬：同一个回调重复投递只记一次</li>
 *   <li>BSC 和 ERC20 同一个地址、18 位精度、15 个确认 → 搬：BSC 到账入账 5；"同一个地址"按需求改成"以 TopUP 返回为准"，精度和确认数由 TopUP 处理</li>
 *   <li>TRC20 T 开头的地址入账、别人的地址不记 → 搬</li>
 *   <li>没有配置 USDT 合约的链不扫块 → 改成"没有配置 TopUP 时不访问、拿不到地址"</li>
 *   <li>入账后被回滚全额冲正、余额不够挂账并通知风控（AC-09）→ 搬（冲正由提供方通知触发）</li>
 *   <li>推送：发现待确认就推、确认数变化推 → 入账推充值记录和账户照旧；"待确认、确认数变化"没法搬（同上）</li>
 *   <li>作废时也推送、推送失败不影响入账 → "推送失败不影响入账"搬；"作废"没法搬</li>
 *   <li>注册时分配地址、接口返回三条链 → 按需求改成"注册时不分配，第一次选链才生成"（AC-W1）</li>
 *   <li>链节点连不上时健康检查 DOWN（AC2-20）→ 改成 TopUP 接口不通 → wallet.topup DOWN（AC-W9）</li>
 *   <li>充值记录的编号是雪花 ID → 搬</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class DepositFlowTest {

  static final FakeTopUp FAKE = new FakeTopUp("topup_dep_key", "dep-secret-not-real-" + System.nanoTime()).start();

  @Autowired DepositService deposits;
  @Autowired DepositMapper mapper;
  @Autowired ProviderRegistry registry;
  @Autowired TopUpProperties topupProps;
  @Autowired PaymentApi payments;
  @Autowired LedgerApi ledger;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired app.trading.trading.api.FundsApi funds;
  @Autowired app.trading.notification.api.NotifyApi notify;
  @Autowired app.trading.common.id.Snowflake ids;
  @Autowired java.time.Clock clock;
  @MockitoBean MailApi mail;

  @BeforeEach
  void fakeTopUp() {
    FAKE.reset();
    registry.register(new TopUpProvider(new TopUpProperties(FAKE.baseUrl(), FAKE.apiKey, FAKE.apiSecret, Duration.ofSeconds(3))));
  }

  @AfterEach
  void cleanup() {
    registry.register(new TopUpProvider(topupProps));
    ((app.trading.support.MutableClock) clock).reset();
  }

  long newUser() {
    long uid = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 + FLOOR(RAND() * 1000) FROM account_user", Long.class);
    jdbc.update("INSERT INTO account_user (id, email, status) VALUES (?, ?, 'ACTIVE')", uid, "w" + uid + "@example.com");
    ledger.openAccount(uid);
    return uid;
  }

  int deliver(FakeTopUp.Callback cb) throws Exception {
    MockHttpServletRequestBuilder r = post("/api/pay/callback/topup").contentType(MediaType.APPLICATION_JSON).content(cb.body());
    for (var h : cb.headers().entrySet()) if (!h.getKey().equalsIgnoreCase("Content-Type")) r.header(h.getKey(), h.getValue());
    return mvc.perform(r).andReturn().getResponse().getStatus();
  }

  /** 假 TopUP 收到一笔到账并回调，返回这笔的交易哈希 */
  String transfer(String chain, String to, String amount) throws Exception {
    FakeTopUp.Callback cb = FAKE.deposit(chain, to, amount).orElseThrow();
    assertThat(deliver(cb)).isEqualTo(200);
    return FAKE.deposits.getLast().txid();
  }

  String status(String chain, String txHash) {
    return jdbc.queryForObject("SELECT status FROM wallet_deposit WHERE chain = ? AND tx_hash = ?", String.class, chain, txHash);
  }

  BigDecimal balance(long uid) {
    return ledger.snapshot(uid).balance();
  }

  @Test
  void ERC20_到账后入账_重复通知不重复入账_到账通知只发一次() throws Exception {
    long uid = newUser();
    String to = payments.address(uid, PayNetwork.ERC20);
    FakeTopUp.Callback cb = FAKE.deposit("eth", to.toLowerCase(), "100").orElseThrow();
    deliver(cb);
    String tx = FAKE.deposits.getLast().txid();
    assertThat(status("ERC20", tx)).isEqualTo("CREDITED");
    assertThat(balance(uid)).isEqualByComparingTo("100");
    deliver(FAKE.resign(cb));
    assertThat(balance(uid)).as("不会重复入账").isEqualByComparingTo("100");
    // 到账通知（AC-07 / AC-W2），只发一次
    assertThat(jdbc.queryForList("SELECT params FROM notify_message WHERE user_id = ? AND template_code = 'DEPOSIT_CREDITED'", String.class, uid))
        .singleElement().asString().contains("\"amount\":\"100\"").contains("\"chain\":\"ERC20\"");
  }

  @Test
  void 伪造的到账不入账_AC_W2() throws Exception {
    long uid = newUser();
    String to = payments.address(uid, PayNetwork.ERC20);
    FakeTopUp.Callback cb = FAKE.deposit("eth", to, "500").orElseThrow();
    var h = new java.util.LinkedHashMap<>(cb.headers());
    h.put("X-TopUP-Signature", "1".repeat(64));
    assertThat(deliver(new FakeTopUp.Callback(cb.eventId(), cb.type(), h, cb.body()))).isEqualTo(401);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_deposit WHERE user_id = ?", Integer.class, uid)).isZero();
    assertThat(balance(uid)).isEqualByComparingTo("0");
  }

  @Test
  void 最小充值1USDT_低于的不入账_AC41_AC_W11() throws Exception {
    long uid = newUser();
    String to = payments.address(uid, PayNetwork.ERC20);
    assertThat(FAKE.deposit("eth", to, "0.99")).as("TopUP 不入账、不回调").isEmpty();
    String ok = transfer("eth", to, "1");
    assertThat(status("ERC20", ok)).isEqualTo("CREDITED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_deposit WHERE user_id = ?", Integer.class, uid)).isEqualTo(1);
    assertThat(balance(uid)).isEqualByComparingTo("1");
  }

  @Test
  void 同一笔重复通知只记一次() throws Exception {
    long uid = newUser();
    String to = payments.address(uid, PayNetwork.ERC20);
    FakeTopUp.Callback cb = FAKE.deposit("eth", to, "20").orElseThrow();
    deliver(cb);
    deliver(cb);
    String tx = FAKE.deposits.getLast().txid();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_deposit WHERE tx_hash = ?", Integer.class, tx)).isEqualTo(1);
    assertThat(balance(uid)).isEqualByComparingTo("20");
  }

  @Test
  void BSC到账入账() throws Exception {
    long uid = newUser();
    String to = payments.address(uid, PayNetwork.BSC);
    String tx = transfer("bsc", to, "5");
    assertThat(status("BSC", tx)).isEqualTo("CREDITED");
    assertThat(balance(uid)).isEqualByComparingTo("5");
  }

  @Test
  void TRC20_T开头的地址入账_别人的地址不记() throws Exception {
    long uid = newUser();
    String to = payments.address(uid, PayNetwork.TRC20);
    assertThat(to).startsWith("T");
    String tx = transfer("tron", to, "12.345678");
    assertThat(FAKE.deposit("tron", "TXYZnotOurs111111111111111111111", "99")).isEmpty();
    // TopUP 发来一个不是 Trading 客户的到账（客户标识不是数字，也没有这个地址）：不记
    FakeTopUp.Callback stray = FAKE.emit("deposit", Map.of("customer_id", "someone-else", "chain", "tron", "address", "TXYZnotOurs111111111111111111111",
        "txid", "ab".repeat(32), "amount", "99.00", "fee", "0.00", "credited", "99.00", "time", "2026-10-14T10:00:00Z"));
    FAKE.pending.clear();
    assertThat(deliver(stray)).isEqualTo(200);
    assertThat(status("TRC20", tx)).isEqualTo("CREDITED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_deposit WHERE tx_hash = ?", Integer.class, "ab".repeat(32))).isZero();
    assertThat(balance(uid)).isEqualByComparingTo("12.345678");
  }

  @Test
  void 没有配置TopUP时不访问_拿不到地址() {
    registry.register(new TopUpProvider(topupProps));
    long uid = newUser();
    int before = FAKE.requests.size();
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> payments.address(uid, PayNetwork.ERC20)).isInstanceOf(PaymentApi.Unavailable.class);
    assertThat(FAKE.requests.size()).isEqualTo(before);
  }

  long creditedErc20(long uid, String amount) throws Exception {
    String to = payments.address(uid, PayNetwork.ERC20);
    String tx = transfer("eth", to, amount);
    assertThat(status("ERC20", tx)).isEqualTo("CREDITED");
    return jdbc.queryForObject("SELECT id FROM wallet_deposit WHERE tx_hash = ?", Long.class, tx);
  }

  String ref(long depositId) {
    return jdbc.queryForObject("SELECT provider_ref FROM wallet_deposit WHERE id = ?", String.class, depositId);
  }

  @Test
  void 入账后被回滚_可用余额够就全额冲正() throws Exception {
    long uid = newUser();
    long id = creditedErc20(uid, "100");
    assertThat(balance(uid)).isEqualByComparingTo("100");
    deposits.reverseByProviderRef("TOPUP", ref(id));
    assertThat(jdbc.queryForObject("SELECT status FROM wallet_deposit WHERE id = ?", String.class, id)).isEqualTo("REVERSED");
    assertThat(balance(uid)).isEqualByComparingTo("0");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_suspense WHERE deposit_id = ?", Integer.class, id)).isZero();
    deposits.reverseByProviderRef("TOPUP", ref(id));
    assertThat(balance(uid)).as("不会重复冲正").isEqualByComparingTo("0");
  }

  @Test
  void 入账后被回滚_可用余额不够_不扣成负数_挂账并通知风控_AC09() throws Exception {
    long uid = newUser();
    long id = creditedErc20(uid, "100");
    // 客户已经用掉 80（这里用挂单冻结模拟），只剩 20 可以自由动用
    ledger.apply(new app.trading.trading.api.LedgerCommand(uid, app.trading.trading.api.Bucket.ORDER_FROZEN, new BigDecimal("80"),
        app.trading.trading.api.LedgerEntryType.ORDER_FREEZE, "TEST", "frz-" + uid, "测试冻结"));
    app.trading.risk.api.RiskApi riskMock = org.mockito.Mockito.mock(app.trading.risk.api.RiskApi.class);
    DepositService service = new DepositService(mapper, ledger, tx, funds, riskMock, notify, (u, e) -> {}, ids);
    service.reverseByProviderRef("TOPUP", ref(id));
    assertThat(jdbc.queryForObject("SELECT status FROM wallet_deposit WHERE id = ?", String.class, id)).isEqualTo("REVERSED");
    assertThat(balance(uid)).as("只扣了 20，挂单冻结的 80 还在").isEqualByComparingTo("80");
    assertThat(ledger.snapshot(uid).orderFrozen()).isEqualByComparingTo("80");
    assertThat(jdbc.queryForObject("SELECT amount FROM wallet_suspense WHERE deposit_id = ?", BigDecimal.class, id)).isEqualByComparingTo("80");
    org.mockito.Mockito.verify(riskMock).notifyStaff(eq("DEPOSIT_SUSPENSE"), org.mockito.ArgumentMatchers.argThat(m -> "80.00000000".equals(m.get("shortfall"))));
  }

  /** 记下推给每个用户的事件（代替 WebSocket） */
  static final class RecordingEvents implements app.trading.trading.api.UserEventsApi {
    final java.util.List<Map<String, Object>> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
    final long only;

    RecordingEvents(long only) {
      this.only = only;
    }

    @Override
    public void send(long userId, Map<String, Object> event) {
      if (userId == only) sent.add(event);
    }

    java.util.List<Map<String, Object>> deposits() {
      return sent.stream().filter(e -> "deposit".equals(e.get("type"))).toList();
    }
  }

  PaymentListener.Deposit notice(long uid, PayNetwork n, String ref, String amount) {
    return new PaymentListener.Deposit("TOPUP", ref, uid, n, "addr", "0x" + ref, new BigDecimal(amount), BigDecimal.ZERO);
  }

  @Test
  void 入账推给本人_充值记录和账户_E2E2() {
    long uid = newUser();
    RecordingEvents rec = new RecordingEvents(uid);
    DepositService service = new DepositService(mapper, ledger, tx, funds, org.mockito.Mockito.mock(app.trading.risk.api.RiskApi.class), notify, rec, ids);
    service.credit(notice(uid, PayNetwork.ERC20, "evt_push_" + uid, "100"));
    assertThat(rec.sent).as("入账后推充值记录和账户").containsExactly(
        Map.of("type", "deposit", "status", "credited", "network", "ERC20", "amount", "100", "confirmations", 0), Map.of("type", "account"));
    rec.sent.clear();
    service.credit(notice(uid, PayNetwork.ERC20, "evt_push_" + uid, "100"));
    assertThat(rec.sent).as("重复的不推").isEmpty();
    assertThat(balance(uid)).isEqualByComparingTo("100");
  }

  @Test
  void 推送失败不影响入账() {
    long uid = newUser();
    app.trading.trading.api.UserEventsApi broken = (u, e) -> {
      throw new IllegalStateException("连接断了");
    };
    DepositService failing = new DepositService(mapper, ledger, tx, funds, org.mockito.Mockito.mock(app.trading.risk.api.RiskApi.class), notify, broken, ids);
    failing.credit(notice(uid, PayNetwork.ERC20, "evt_broken_" + uid, "7"));
    assertThat(jdbc.queryForObject("SELECT status FROM wallet_deposit WHERE provider_ref = ?", String.class, "evt_broken_" + uid)).isEqualTo("CREDITED");
    assertThat(balance(uid)).isEqualByComparingTo("7");
  }

  @SuppressWarnings("unchecked")
  @Test
  void 注册时不分配地址_第一次选链才生成_接口返回地址和最小充值_充值记录_AC_W1() throws Exception {
    String email = "dep" + System.nanoTime() + "@example.com";
    mvc.perform(post("/api/auth/email-code").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"purpose\":\"REGISTER\"}"));
    ArgumentCaptor<Map<String, String>> p = ArgumentCaptor.forClass(Map.class);
    verify(mail, atLeastOnce()).send(eq(email), eq("EMAIL_CODE"), p.capture());
    Cookie c = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"code\":\"" + p.getValue().get("code")
        + "\",\"password\":\"password123\",\"firstName\":\"A\",\"lastName\":\"B\",\"birthDate\":\"1990-05-01\",\"nationality\":\"CN\"}")).andReturn().getResponse().getCookie("fx_session");
    long uid = jdbc.queryForObject("SELECT id FROM account_user WHERE email = ?", Long.class, email);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_address WHERE user_id = ?", Integer.class, uid)).as("注册时不分配").isZero();
    String tron = mvc.perform(post("/api/wallet/addresses/TRC20").cookie(c)).andReturn().getResponse().getContentAsString();
    String addr = payments.existingAddress(uid, PayNetwork.TRC20).orElseThrow();
    assertThat(tron).isEqualTo("{\"network\":\"TRC20\",\"address\":\"" + addr + "\",\"minDeposit\":\"1\"}");
    assertThat(mvc.perform(get("/api/wallet/networks")).andReturn().getResponse().getStatus()).isEqualTo(401);
    // 充值记录
    String txHash = transfer("tron", addr, "25.5");
    assertThat(FAKE.deposit("tron", addr, "0.5")).isEmpty();
    String list = mvc.perform(get("/api/wallet/deposits").cookie(c)).andReturn().getResponse().getContentAsString();
    assertThat(list).contains("\"network\":\"TRC20\",\"txHash\":\"" + txHash + "\",\"amount\":\"25.5\",\"status\":\"credited\"").doesNotContain("0.5\"");
  }

  @Test
  void TopUP接口连不上_健康检查的alerts变成DOWN_status和HTTP状态码不变_恢复后UP_AC_W9() throws Exception {
    var check = (app.trading.payment.api.PaymentAdminApi) payments;
    check.check();
    assertThat(mvc.perform(get("/api/health")).andReturn().getResponse().getContentAsString()).contains("\"wallet.topup\":\"UP\"");
    FAKE.down = true;
    check.check();
    ((app.trading.support.MutableClock) clock).advance(Duration.ofMinutes(2));
    check.check();
    var r = mvc.perform(get("/api/health")).andReturn().getResponse();
    assertThat(r.getStatus()).as("提供方不通不能让部署脚本回滚").isEqualTo(200);
    assertThat(r.getContentAsString()).contains("\"status\":\"UP\"").contains("\"alert\":\"ALERT\"").contains("\"wallet.topup\":\"DOWN\"").doesNotContain("503");
    FAKE.down = false;
    check.check();
    assertThat(mvc.perform(get("/api/health")).andReturn().getResponse().getContentAsString()).contains("\"wallet.topup\":\"UP\"");
  }

  @Test
  void 充值记录的编号是雪花ID字符串_不是内部id_AC2_22() throws Exception {
    long uid = newUser();
    String to = payments.address(uid, PayNetwork.BSC);
    String txHash = transfer("bsc", to.toLowerCase(), "3");
    long internal = jdbc.queryForObject("SELECT id FROM wallet_deposit WHERE tx_hash = ?", Long.class, txHash);
    long no = jdbc.queryForObject("SELECT public_no FROM wallet_deposit WHERE tx_hash = ?", Long.class, txHash);
    assertThat(no).isNotEqualTo(internal).isGreaterThan((1L << 53) - 1);
    assertThat(app.trading.common.id.Snowflake.nodeOf(no)).isEqualTo(1);
    var records = deposits.history(uid, 10);
    assertThat(records).singleElement().extracting(DepositService.DepositRecord::no).isEqualTo(no);
    assertThat(Optional.of(records.getFirst().status())).contains("CREDITED");
  }
}
