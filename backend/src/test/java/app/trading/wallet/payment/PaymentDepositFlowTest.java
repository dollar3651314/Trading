package app.trading.wallet.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import app.trading.account.auth.SessionTestHooks;
import app.trading.payment.api.PayNetwork;
import app.trading.payment.api.PayoutState;
import app.trading.payment.fake.FakeTopUp;
import app.trading.payment.provider.PaymentProvider;
import app.trading.wallet.api.WalletReconApi;
import app.trading.wallet.withdraw.WithdrawService;
import app.trading.wallet.withdraw.WithdrawTestHooks;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 充值地址按需生成（AC-W1）、到账回调验签只入账一次（AC-W2）、冲正（AC-W3）、切换提供方（AC-W7）、TopUP 不通的告警（AC-W9）、
 * 按单号逐笔对账（AC-W10）、低于 1 USDT 不入账（AC-W11）。TopUP 是假的（{@link FakeTopUp}）。
 */
class PaymentDepositFlowTest extends PaymentTestSupport {

  @Autowired ApplicationContext ctx;
  @Autowired WalletReconApi recon;
  @Autowired WithdrawService withdraws;

  Cookie login(long uid) {
    return SessionTestHooks.cookie(ctx, uid);
  }

  String body(MockHttpServletResponse r) throws Exception {
    return r.getContentAsString();
  }

  MockHttpServletResponse networks(Cookie c) throws Exception {
    return mvc.perform(get("/api/wallet/networks").cookie(c)).andReturn().getResponse();
  }

  MockHttpServletResponse address(Cookie c, String network) throws Exception {
    return mvc.perform(post("/api/wallet/addresses/" + network).cookie(c)).andReturn().getResponse();
  }

  static String addressIn(String json) {
    return json.replaceAll(".*\"address\":\"([^\"]+)\".*", "$1");
  }

  @Test
  void 第一次选某条链才向TopUP要地址_再选同一条不变_关掉的链不显示_TopUP不通可重试_AC_W1() throws Exception {
    long uid = user("0", false);
    Cookie c = login(uid);
    assertThat(body(networks(c))).isEqualTo("[{\"network\":\"ERC20\",\"address\":null,\"minDeposit\":\"1\"},{\"network\":\"BSC\",\"address\":null,\"minDeposit\":\"1\"},"
        + "{\"network\":\"TRC20\",\"address\":null,\"minDeposit\":\"1\"}]");
    assertThat(FAKE.customers).as("打开充值页不生成地址").doesNotContainKey(String.valueOf(uid));

    String tron = addressIn(body(address(c, "TRC20")));
    assertThat(tron).matches("T[1-9A-HJ-NP-Za-km-z]{33}");
    assertThat(FAKE.address(String.valueOf(uid), "tron")).contains(tron);
    assertThat(FAKE.address(String.valueOf(uid), "eth")).as("只生成选了的那条").isEmpty();
    int calls = FAKE.requests.size();
    assertThat(addressIn(body(address(c, "TRC20")))).isEqualTo(tron);
    assertThat(FAKE.requests.size()).as("已有地址不再访问 TopUP").isEqualTo(calls);
    assertThat(body(networks(c))).contains("{\"network\":\"TRC20\",\"address\":\"" + tron + "\",\"minDeposit\":\"1\"}");

    // 运营临时关掉 BSC：选择里没有，直接要也不给
    jdbc.update("UPDATE common_config SET value = 'false' WHERE config_key = 'payment.network.BSC'");
    assertThat(body(networks(c))).doesNotContain("BSC");
    assertThat(body(address(c, "BSC"))).contains("NETWORK_CLOSED");

    // TopUP 不通：503 + "暂时无法获取充值地址"，已生成的照常
    FAKE.down = true;
    MockHttpServletResponse r = address(c, "ERC20");
    assertThat(r.getStatus()).isEqualTo(503);
    assertThat(r.getContentAsString()).contains("WALLET_UNAVAILABLE").contains("暂时无法获取充值地址");
    assertThat(addressIn(body(address(c, "TRC20")))).isEqualTo(tron);
    FAKE.down = false;
    assertThat(addressIn(body(address(c, "ERC20")))).as("恢复后重试就有").matches("0x[0-9a-f]{40}");
    assertThat(mvc.perform(post("/api/wallet/addresses/TRC20")).andReturn().getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void 到账回调_验签通过入账_重放3次只入账一次_签名错的拒收不入账_AC_W2() throws Exception {
    long uid = user("0", false);
    String addr = api.address(uid, PayNetwork.TRC20);
    FakeTopUp.Callback cb = FAKE.deposit("tron", addr, "250.5").orElseThrow();
    FakeTopUp.Callback forged = new FakeTopUp.Callback(cb.eventId(), cb.type(), new java.util.LinkedHashMap<>(cb.headers()), cb.body().replace("250.50", "2500.50"));
    assertThat(deliver(forged)).isEqualTo(401);
    assertThat(balance(uid)).isEqualByComparingTo("0");
    assertThat(deliver(cb)).isEqualTo(200);
    for (int i = 0; i < 3; i++) assertThat(deliver(FAKE.resign(cb))).isEqualTo(200);
    assertThat(balance(uid)).isEqualByComparingTo("250.5");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_deposit WHERE user_id = ?", Integer.class, uid)).isEqualTo(1);
    assertThat(jdbc.queryForList("SELECT params FROM notify_message WHERE user_id = ? AND template_code = 'DEPOSIT_CREDITED'", String.class, uid))
        .singleElement().asString().contains("\"amount\":\"250.5\"").contains("\"chain\":\"TRC20\"");
    // 时间戳超过 5 分钟的（被截获后重放）也拒收
    FakeTopUp.Callback cb2 = FAKE.deposit("tron", addr, "10").orElseThrow();
    String old = String.valueOf(java.time.Instant.now().getEpochSecond() - 600);
    var stale = new java.util.LinkedHashMap<>(cb2.headers());
    stale.put("X-TopUP-Timestamp", old);
    stale.put("X-TopUP-Signature", app.trading.payment.topup.TopUpSigner.signCallback(FAKE.apiSecret, old, cb2.body()));
    assertThat(deliver(new FakeTopUp.Callback(cb2.eventId(), cb2.type(), stale, cb2.body()))).isEqualTo(401);
    assertThat(balance(uid)).isEqualByComparingTo("250.5");
  }

  @Test
  void 同一笔交易里两次转账_两个到账编号_各入账一次() throws Exception {
    long uid = user("0", false);
    String addr = api.address(uid, PayNetwork.ERC20);
    FakeTopUp.Callback a = FAKE.deposit("eth", addr, "10").orElseThrow();
    FakeTopUp.Callback b = FAKE.deposit("eth", addr, "10").orElseThrow();
    // 两笔交易哈希改成一样（TopUP 的回调没有链上日志序号，TG-5）：按到账编号去重，不会把第二笔当成重复
    String tx = a.body().replaceAll(".*\"txid\":\"([^\"]+)\".*", "$1");
    String bBody = b.body().replaceAll("\"txid\":\"[^\"]+\"", "\"txid\":\"" + tx + "\"");
    deliver(a);
    var h = new java.util.LinkedHashMap<>(b.headers());
    h.put("X-TopUP-Signature", app.trading.payment.topup.TopUpSigner.signCallback(FAKE.apiSecret, h.get("X-TopUP-Timestamp"), bBody));
    deliver(new FakeTopUp.Callback(b.eventId(), b.type(), h, bBody));
    assertThat(balance(uid)).isEqualByComparingTo("20");
  }

  @Test
  void 低于1USDT的转账_TopUP不入账不通知_Trading不报错不出现_AC_W11() throws Exception {
    long uid = user("0", false);
    String addr = api.address(uid, PayNetwork.TRC20);
    assertThat(FAKE.deposit("tron", addr, "0.99")).isEmpty();
    assertThat(FAKE.pending).isEmpty();
    Cookie c = login(uid);
    assertThat(body(mvc.perform(get("/api/wallet/deposits").cookie(c)).andReturn().getResponse())).isEqualTo("[]");
    assertThat(balance(uid)).isEqualByComparingTo("0");
  }

  @Test
  void 充值记录接口_已到账_编号是雪花ID字符串_AC2_22() throws Exception {
    long uid = user("0", false);
    String addr = api.address(uid, PayNetwork.BSC);
    deliver(FAKE.deposit("bsc", addr, "3").orElseThrow());
    String list = body(mvc.perform(get("/api/wallet/deposits").cookie(login(uid))).andReturn().getResponse());
    assertThat(list).contains("\"network\":\"BSC\"").contains("\"amount\":\"3\"").contains("\"status\":\"credited\"");
    long no = Long.parseLong(list.replaceAll(".*\"id\":\"(\\d+)\".*", "$1"));
    assertThat(no).isGreaterThan((1L << 53) - 1);
  }

  /** 测试替身提供方（AC-W7）：内存里的，能发冲正通知（TopUP 还没有，TG-11） */
  static final class StandIn implements PaymentProvider {
    final Map<String, String> addrs = new ConcurrentHashMap<>();

    @Override
    public String code() {
      return "STANDIN";
    }

    @Override
    public String name() {
      return "测试替身提供方";
    }

    @Override
    public boolean connected() {
      return true;
    }

    @Override
    public void ensureCustomer(String customerId) {}

    @Override
    public String address(String customerId, PayNetwork network) {
      return addrs.computeIfAbsent(customerId + network, k -> network == PayNetwork.TRC20 ? "TStandIn" + "1".repeat(26) : "0x" + "5".repeat(40));
    }

    @Override
    public Submitted submitPayout(String idemKey, String merchantRef, String customerId, PayNetwork network, String address, BigDecimal amount) {
      return new Submitted("SI-" + merchantRef, PayoutState.PENDING);
    }

    @Override
    public Optional<PayoutView> payout(String ref) {
      return Optional.empty();
    }

    @Override
    public BalanceView balance() {
      return new BalanceView(new BigDecimal("1"), BigDecimal.ZERO);
    }

    @Override
    public List<DepositView> deposits(String customerId) {
      return List.of();
    }

    @Override
    public Optional<Event> parseCallback(Map<String, String> headers, String body) {
      return Optional.empty();
    }
  }

  @Test
  void 切换提供方_新选的链地址来自新提供方_老地址到账照常入账_进行中的提现由原提供方付完_写审计_AC_W7() throws Exception {
    long uid = user("1000", true);
    String tronOld = api.address(uid, PayNetwork.TRC20);
    long wl = WithdrawTestHooks.activeAddress(ctx, jdbc, uid, "TRC20", "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t");
    long wid = WithdrawTestHooks.submit(withdraws, uid, wl, "100");
    time().advance(Duration.ofHours(2).plusSeconds(1));
    WithdrawTestHooks.advance(withdraws);
    withdraws.approve(wid, 7);
    assertThat(api.payoutInfo(wid).orElseThrow().provider()).isEqualTo("TOPUP");

    registry.register(new StandIn());
    String why = "测试切换 " + uid;
    payments.switchProvider("STANDIN", 9, why);
    assertThat(jdbc.queryForObject("SELECT detail FROM pay_audit WHERE action = 'SWITCH_PROVIDER' AND reason = ?", String.class, why)).isEqualTo("TOPUP → STANDIN");
    assertThat(api.address(uid, PayNetwork.ERC20)).as("新选的链用新提供方").isEqualTo("0x" + "5".repeat(40));
    assertThat(api.address(uid, PayNetwork.TRC20)).as("老地址不变").isEqualTo(tronOld);
    assertThat(jdbc.queryForObject("SELECT provider FROM pay_address WHERE user_id = ? AND network = 'TRC20'", String.class, uid)).isEqualTo("TOPUP");
    // 老地址的到账照常入账（TopUP 的回调）
    deliverAll();
    deliver(FAKE.deposit("tron", tronOld, "50").orElseThrow());
    assertThat(balance(uid)).isEqualByComparingTo("950");
    // 进行中的提现由 TopUP 付完
    String no = FAKE.byMerchantRef(app.trading.payment.core.PaymentService.merchantRef(jdbc.queryForObject("SELECT public_no FROM wallet_withdrawal WHERE id = ?", Long.class, wid))).orElseThrow().no;
    deliver(FAKE.moveWithdrawal(no, "completed", "e".repeat(64), null));
    assertThat(jdbc.queryForObject("SELECT status FROM wallet_withdrawal WHERE id = ?", String.class, wid)).isEqualTo("COMPLETED");
  }

  @Test
  void 冲正_余额不够不扣成负数_挂账并通知风控_AC_W3() throws Exception {
    long uid = user("0", false);
    String addr = api.address(uid, PayNetwork.TRC20);
    FakeTopUp.Callback cb = FAKE.deposit("tron", addr, "100").orElseThrow();
    deliver(cb);
    String ref = cb.eventId();
    // 客户已经用掉 80（挂单冻结模拟）
    ledger.apply(new app.trading.trading.api.LedgerCommand(uid, app.trading.trading.api.Bucket.ORDER_FROZEN, new BigDecimal("80"),
        app.trading.trading.api.LedgerEntryType.ORDER_FREEZE, "TEST", "frz-" + uid, "测试冻结"));
    // 测试替身提供方发来冲正（TopUP 还没有冲正通知，TG-11）
    ctx.getBean(WalletPaymentListener.class).onDepositReversed("TOPUP", ref);
    long id = jdbc.queryForObject("SELECT id FROM wallet_deposit WHERE provider_ref = ?", Long.class, ref);
    assertThat(jdbc.queryForObject("SELECT status FROM wallet_deposit WHERE id = ?", String.class, id)).isEqualTo("REVERSED");
    assertThat(balance(uid)).as("只扣了能自由动用的 20，挂单冻结的 80 还在").isEqualByComparingTo("80");
    assertThat(jdbc.queryForObject("SELECT amount FROM wallet_suspense WHERE deposit_id = ?", BigDecimal.class, id)).isEqualByComparingTo("80");
    ctx.getBean(WalletPaymentListener.class).onDepositReversed("TOPUP", ref);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_suspense WHERE deposit_id = ?", Integer.class, id)).as("不重复冲正").isEqualTo(1);
  }

  @Test
  void TopUP接口连续1分钟不通_健康检查出现wallet_topup_DOWN_恢复后UP_AC_W9() throws Exception {
    var monitor = ctx.getBeansOfType(app.trading.common.health.AlertCheck.class).values().stream()
        .filter(a -> a.getClass().getSimpleName().equals("PaymentMonitor")).findFirst().orElseThrow();
    payments.check();
    assertThat(mvc.perform(get("/api/health")).andReturn().getResponse().getContentAsString()).contains("\"wallet.topup\":\"UP\"");
    FAKE.down = true;
    payments.check();
    assertThat(monitor.evaluate()).singleElement().matches(i -> i.ok(), "刚不通，还没到 1 分钟");
    time().advance(Duration.ofSeconds(61));
    payments.check();
    var r = mvc.perform(get("/api/health")).andReturn().getResponse();
    assertThat(r.getStatus()).as("提供方不通不能让部署回滚").isEqualTo(200);
    assertThat(r.getContentAsString()).contains("\"wallet.topup\":\"DOWN\"").contains("\"alert\":\"ALERT\"");
    FAKE.down = false;
    payments.check();
    assertThat(mvc.perform(get("/api/health")).andReturn().getResponse().getContentAsString()).contains("\"wallet.topup\":\"UP\"");
  }

  @Test
  void 对账_按单号逐笔核对_金额不一致的列出来_一致的不报_TopUP不通时记没跑成_AC_W10() throws Exception {
    long uid = user("0", false);
    String addr = api.address(uid, PayNetwork.TRC20);
    FakeTopUp.Callback ok = FAKE.deposit("tron", addr, "30").orElseThrow();
    deliver(ok);
    recon.run();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_recon_issue WHERE user_id = ? AND kind LIKE 'DEPOSIT_PROVIDER%' AND status = 'OPEN'", Integer.class, uid))
        .as("一致时不报").isZero();
    assertThat(recon.lastRun().orElseThrow().providerOk()).isTrue();
    // 人为让 Trading 这边的金额和 TopUP 不一致
    jdbc.update("UPDATE wallet_deposit SET amount = 31 WHERE user_id = ?", uid);
    recon.run();
    assertThat(jdbc.queryForList("SELECT kind FROM wallet_recon_issue WHERE user_id = ? AND status = 'OPEN'", String.class, uid)).contains("DEPOSIT_PROVIDER_MISMATCH");
    // TopUP 有、Trading 没有（回调丢了）
    FAKE.deposit("tron", addr, "5").orElseThrow();
    FAKE.pending.clear();
    recon.run();
    assertThat(jdbc.queryForList("SELECT kind FROM wallet_recon_issue WHERE user_id = ? AND status = 'OPEN'", String.class, uid)).contains("DEPOSIT_TRADING_MISSING");
    FAKE.down = true;
    recon.run();
    assertThat(recon.lastRun().orElseThrow().providerOk()).isFalse();
  }

  @Test
  void 对账_提现付款状态对不上的列出来() throws Exception {
    long uid = user("1000", true);
    long wl = WithdrawTestHooks.activeAddress(ctx, jdbc, uid, "TRC20", "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t");
    long wid = WithdrawTestHooks.submit(withdraws, uid, wl, "100");
    time().advance(Duration.ofHours(2).plusSeconds(1));
    WithdrawTestHooks.advance(withdraws);
    withdraws.approve(wid, 7);
    long no = jdbc.queryForObject("SELECT public_no FROM wallet_withdrawal WHERE id = ?", Long.class, wid);
    FakeTopUp.Withdrawal w = FAKE.byMerchantRef(app.trading.payment.core.PaymentService.merchantRef(no)).orElseThrow();
    recon.run();
    assertThat(jdbc.queryForList("SELECT kind FROM wallet_recon_issue WHERE user_id = ? AND status = 'OPEN'", String.class, uid)).as("都还在等放款：一致")
        .doesNotContain("PAYOUT_STATUS_MISMATCH");
    // TopUP 已经成功、Trading 还是放款中（回调丢了）：列出来（原型 P6 的例子）
    w.status = "completed";
    recon.run();
    assertThat(jdbc.queryForList("SELECT kind FROM wallet_recon_issue WHERE user_id = ? AND status = 'OPEN'", String.class, uid)).contains("PAYOUT_STATUS_MISMATCH");
    jdbc.update("UPDATE wallet_recon_issue SET status = 'RESOLVED' WHERE user_id = ?", uid);
    // TopUP 已经驳回了，Trading 还以为已完成（例如被人改了库）
    w.status = "rejected";
    jdbc.update("UPDATE wallet_withdrawal SET status = 'COMPLETED' WHERE id = ?", wid);
    recon.run();
    assertThat(jdbc.queryForList("SELECT kind FROM wallet_recon_issue WHERE user_id = ? AND status = 'OPEN'", String.class, uid)).contains("PAYOUT_STATUS_MISMATCH");
    jdbc.update("UPDATE wallet_withdrawal SET status = 'BROADCASTING' WHERE id = ?", wid);
    jdbc.update("UPDATE wallet_recon_issue SET status = 'RESOLVED' WHERE user_id = ?", uid);
  }

  @Test
  void 自动审批R12_看Trading在TopUP的可用余额_减去已通过未付合计_TD12() throws Exception {
    FAKE.available = new BigDecimal("12345.67");
    payments.check();
    var hot = ctx.getBean(app.trading.wallet.api.HotWalletApi.class).hotBalances();
    assertThat(hot).singleElement().satisfies(b -> {
      assertThat(b.chain()).isEqualTo("ALL");
      assertThat(b.balance()).isEqualByComparingTo("12345.67");
      assertThat(b.checkedAt()).isNotNull();
    });
  }

  /** 生产方案 v1 07 页 R-5：后台"系统配置"的钱包提供方可用余额告警下限（payment.balance.minUsdt，0 = 不告警），代替以前写死的 0 */
  @Test
  void 余额告警_低于后台设的下限出现告警项_恢复后正常_0或查不到余额时不检查_R5() throws Exception {
    var config = ctx.getBean(app.trading.common.config.SystemConfig.class);
    var hot = ctx.getBean(PaymentHotWallet.class);
    String key = app.trading.common.config.ConfigKey.PAYMENT_BALANCE_MIN_USDT.key;
    assertThat(jdbc.queryForObject("SELECT value FROM common_config WHERE config_key = ?", String.class, key)).as("迁移 V34 的初始值 0 = 不告警").isEqualTo("0");
    try {
      FAKE.available = new BigDecimal("4999.99");
      payments.check();
      config.refresh();
      assertThat(hot.evaluate()).as("下限 0：不检查").isEmpty();
      assertThat(hot.hotBalances()).singleElement().satisfies(b -> {
        assertThat(b.threshold()).isEqualByComparingTo("0");
        assertThat(b.low()).isFalse();
      });

      jdbc.update("UPDATE common_config SET value = '5000' WHERE config_key = ?", key);
      config.refresh();
      assertThat(hot.evaluate()).singleElement().satisfies(i -> {
        assertThat(i.name()).isEqualTo("wallet.balance");
        assertThat(i.ok()).as("4,999.99 < 5,000").isFalse();
        assertThat(i.detail()).contains("4999.99").contains("5000");
      });
      assertThat(hot.hotBalances()).singleElement().satisfies(b -> {
        assertThat(b.threshold()).isEqualByComparingTo("5000");
        assertThat(b.low()).isTrue();
      });
      // 健康检查（后台域名、服务器本机）的 alerts 里也有，trading status 看得到
      assertThat(mvc.perform(get("/api/health").header("X-Fx-Health-Alerts", "1")).andReturn().getResponse().getContentAsString())
          .contains("\"wallet.balance\":\"DOWN\"");

      FAKE.available = new BigDecimal("5000");
      payments.check();
      assertThat(hot.evaluate()).singleElement().satisfies(i -> assertThat(i.ok()).as("等于下限算正常").isTrue());
      assertThat(hot.hotBalances()).singleElement().satisfies(b -> assertThat(b.low()).isFalse());

      jdbc.update("UPDATE common_config SET value = 'abc' WHERE config_key = ?", key);
      config.refresh();
      assertThat(hot.evaluate()).as("值读不出来按 0（不告警）").isEmpty();
    } finally {
      jdbc.update("UPDATE common_config SET value = '0' WHERE config_key = ?", key);
      config.refresh();
    }
  }
}
