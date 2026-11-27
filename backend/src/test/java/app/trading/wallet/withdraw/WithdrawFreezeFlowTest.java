package app.trading.wallet.withdraw;

import static org.assertj.core.api.Assertions.assertThat;

import app.trading.account.api.AccountApi;
import app.trading.account.api.WithdrawPinApi;
import app.trading.common.error.BizException;
import app.trading.notification.api.MailApi;
import app.trading.support.FixedClockConfig;
import app.trading.support.MutableClock;
import app.trading.trading.api.Bucket;
import app.trading.trading.api.LedgerApi;
import app.trading.trading.api.LedgerCommand;
import app.trading.trading.api.LedgerEntryType;
import app.trading.wallet.Chain;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 冻结挡住在途的提现（第 4 批审查 🔴4a-1，08 页第 2 节"冻结禁提，在途的也算"），真实 MySQL：
 * <ul>
 *   <li>各环节的闸：审核通过、冷静期结束、大额延迟期满、交钱包提供方之前都查冻结（这里直接往库里写冻结，不走冻结事件，单独测闸）；</li>
 *   <li>冻结的时候（提现冻结、账户冻结）：冷静期、待审核、大额延迟中的取消并退回余额，已审核待放款的暂停（不交出去，解除后继续）。</li>
 * </ul>
 * 广播用 {@link WithdrawFlowTest.FakeSender}，记下交出去的单。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class WithdrawFreezeFlowTest {

  static final String TRON_ADDR = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";

  @Autowired WithdrawService withdraws;
  @Autowired WhitelistService whitelist;
  @Autowired WithdrawSenders senders;
  @Autowired LedgerApi ledger;
  @Autowired AccountApi accounts;
  @Autowired WithdrawPinApi pins;
  @Autowired JdbcTemplate jdbc;
  @Autowired Clock clock;
  @MockitoBean MailApi mail;

  /** 每个用例开始时没有广播器（应用启动时注册的钱包提供方付款器也去掉），需要的用例自己注册假广播器 */
  @org.junit.jupiter.api.BeforeEach
  void noSenders() {
    for (Chain c : Chain.values()) senders.remove(c);
  }

  @AfterEach
  void reset() {
    ((MutableClock) clock).reset();
    for (Chain c : Chain.values()) senders.remove(c);
  }

  /** 这个测试类建的客户（收尾用） */
  final java.util.List<Long> users = new java.util.ArrayList<>();

  /**
   * 收尾：没有广播器时审核通过的提现留在"已审核待放款"，后面别的测试类跑提现推进会把它交给自己的假广播器（共用一个库），
   * 所以把这个类的客户还在途的提现标成已撤销（只改测试数据，不影响本类的断言）
   */
  @AfterEach
  void closeInFlight() {
    for (Long u : users) {
      jdbc.update("UPDATE wallet_withdrawal SET status = 'CANCELLED' WHERE user_id = ? AND status IN ('COOLING', 'REVIEWING', 'DELAYED', 'APPROVED')", u);
    }
    users.clear();
  }

  MutableClock time() {
    return (MutableClock) clock;
  }

  WithdrawFlowTest.FakeSender sender() {
    WithdrawFlowTest.FakeSender s = new WithdrawFlowTest.FakeSender();
    senders.register(Chain.TRC20, s);
    return s;
  }

  long user() {
    long uid = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 + FLOOR(RAND() * 1000) FROM account_user", Long.class);
    jdbc.update("INSERT INTO account_user (id, email, status) VALUES (?, ?, 'ACTIVE')", uid, "fz" + uid + "@example.com");
    ledger.openAccount(uid);
    ledger.apply(new LedgerCommand(uid, Bucket.BALANCE, new BigDecimal("10000"), LedgerEntryType.DEPOSIT, "TEST", "fzdep-" + uid, "测试入金"));
    jdbc.update("INSERT INTO account_kyc_application (user_id, doc_type, doc_number, front_key, back_key, selfie_key, status) VALUES (?, 'PASSPORT', ?, 'f', 'b', 's', 'APPROVED')",
        uid, "FZ" + uid);
    users.add(uid);
    return uid;
  }

  /** 每个客户一个已生效的地址（同一个地址不能加两次） */
  final java.util.Map<Long, Long> addresses = new java.util.HashMap<>();

  long address(long uid) {
    return addresses.computeIfAbsent(uid, this::newAddress);
  }

  long newAddress(long uid) {
    WhitelistRow r = whitelist.add(uid, "TRC20", TRON_ADDR, "测试");
    jdbc.update("UPDATE wallet_whitelist SET active_at = created_at - INTERVAL 1 HOUR WHERE id = ?", r.id);
    return r.id;
  }

  WithdrawalRow submit(long uid, String amount) {
    return withdraws.submit(uid, String.valueOf(address(uid)), amount);
  }

  WithdrawalRow reviewing(long uid, String amount) {
    WithdrawalRow w = submit(uid, amount);
    time().advance(Duration.ofHours(3));
    withdraws.advance();
    assertThat(status(w.id)).isEqualTo("REVIEWING");
    return w;
  }

  /** 直接往库里写提现冻结（不发冻结事件），单独测各环节的闸 */
  void freezeRow(long uid) {
    jdbc.update("INSERT INTO account_withdraw_freeze (user_id, source, reason, created_at) VALUES (?, 'WEB_REQUEST', '测试', UTC_TIMESTAMP())", uid);
  }

  void unfreezeRow(long uid) {
    jdbc.update("DELETE FROM account_withdraw_freeze WHERE user_id = ?", uid);
  }

  String status(long id) {
    return jdbc.queryForObject("SELECT status FROM wallet_withdrawal WHERE id = ?", String.class, id);
  }

  BigDecimal bal(long uid) {
    return ledger.snapshot(uid).balance();
  }

  BigDecimal frozen(long uid) {
    return ledger.snapshot(uid).withdrawFrozen();
  }

  String lastNote(long id) {
    return jdbc.queryForObject("SELECT note FROM wallet_withdraw_event WHERE withdrawal_id = ? ORDER BY id DESC LIMIT 1", String.class, id);
  }

  // ---------- 各环节的闸 ----------

  @Test
  void 提交_冻结_审核通过被拒_什么都没交出去() {
    var s = sender();
    long uid = user();
    WithdrawalRow w = reviewing(uid, "100");
    freezeRow(uid);
    BizException e = org.junit.jupiter.api.Assertions.assertThrows(BizException.class, () -> withdraws.approve(w.id, 9L, true));
    assertThat(e.code()).isEqualTo("WITHDRAW_FROZEN");
    assertThat(e.getMessage()).contains("客户已冻结提现，请先核对");
    assertThat(status(w.id)).isEqualTo("REVIEWING");
    assertThat(s.sent).isEmpty();
    withdraws.advance();
    assertThat(s.sent).isEmpty();
    // 解除以后可以正常审核
    unfreezeRow(uid);
    withdraws.approve(w.id, 9L);
    assertThat(status(w.id)).isEqualTo("BROADCASTING");
    assertThat(s.sent).hasSize(1);
  }

  @Test
  void 账户冻结时审核通过也被拒() {
    var s = sender();
    long uid = user();
    WithdrawalRow w = reviewing(uid, "100");
    jdbc.update("UPDATE account_user SET status = 'FROZEN' WHERE id = ?", uid);
    BizException e = org.junit.jupiter.api.Assertions.assertThrows(BizException.class, () -> withdraws.approve(w.id, 9L));
    assertThat(e.code()).isEqualTo("WITHDRAW_FROZEN");
    assertThat(e.getMessage()).contains("账户已冻结");
    assertThat(s.sent).isEmpty();
  }

  @Test
  void 冷静期结束时冻结了_取消退回_不进审核() {
    var s = sender();
    long uid = user();
    WithdrawalRow w = submit(uid, "100");
    assertThat(frozen(uid)).isEqualByComparingTo("100");
    freezeRow(uid);
    time().advance(Duration.ofHours(3));
    withdraws.advance();
    assertThat(status(w.id)).isEqualTo("CANCELLED");
    assertThat(lastNote(w.id)).contains("冻结，自动取消并退回").contains("提现已冻结");
    assertThat(bal(uid)).isEqualByComparingTo("10000");
    assertThat(frozen(uid)).isEqualByComparingTo("0");
    assertThat(s.sent).isEmpty();
  }

  @Test
  void 大额延迟期满时冻结了_取消退回_不交出去() {
    var s = sender();
    long uid = user();
    WithdrawalRow w = reviewing(uid, "3000");
    withdraws.approve(w.id, 9L);
    assertThat(status(w.id)).isEqualTo("DELAYED");
    freezeRow(uid);
    time().advance(Duration.ofHours(7));
    withdraws.advance();
    assertThat(status(w.id)).isEqualTo("CANCELLED");
    assertThat(bal(uid)).isEqualByComparingTo("10000");
    assertThat(frozen(uid)).isEqualByComparingTo("0");
    assertThat(s.sent).isEmpty();
  }

  /** 4b 审查 🟡1：冻结时暂停的已审核待放款提现，解除冻结后不自动继续，要后台逐笔选"继续放款"或"取消并退回" */
  @Test
  void 已审核待放款时冻结了_暂停_解除冻结后不自动继续_后台选继续放款才交出去() {
    long uid = user();
    WithdrawalRow w = reviewing(uid, "100");
    // 没有广播器：审核通过后留在待放款
    withdraws.approve(w.id, 9L);
    assertThat(status(w.id)).isEqualTo("APPROVED");
    freezeRow(uid);
    var s = sender();
    withdraws.advance();
    assertThat(status(w.id)).as("冻结期间不交出去").isEqualTo("APPROVED");
    assertThat(s.sent).isEmpty();
    assertThat(withdraws.held(uid)).singleElement().satisfies(h -> {
      assertThat(h.reason()).isEqualTo("提现已冻结");
      assertThat(h.sentBefore()).isFalse();
    });
    assertThat(org.junit.jupiter.api.Assertions.assertThrows(BizException.class, () -> withdraws.releaseHeld(w.id, 9L, "核对过")).code())
        .as("还冻结着不能继续").isEqualTo("WITHDRAW_FROZEN");
    unfreezeRow(uid);
    withdraws.advance();
    assertThat(status(w.id)).as("解除冻结后不自动继续").isEqualTo("APPROVED");
    assertThat(s.sent).isEmpty();
    withdraws.releaseHeld(w.id, 9L, "视频核对本人后继续");
    assertThat(status(w.id)).isEqualTo("BROADCASTING");
    assertThat(s.sent).hasSize(1);
    assertThat(withdraws.held(uid)).isEmpty();
  }

  @Test
  void 暂停中从没交过钱包提供方的_可以取消并退回() {
    long uid = user();
    WithdrawalRow w = reviewing(uid, "100");
    withdraws.approve(w.id, 9L); // 没有广播器：没认领过
    pins.freezeWithdraw(uid, "WEB_REQUEST", "测试");
    assertThat(withdraws.held(uid)).hasSize(1);
    jdbc.update("DELETE FROM account_withdraw_freeze WHERE user_id = ?", uid);
    withdraws.cancelHeld(w.id, 9L, "客户要求取消");
    assertThat(status(w.id)).isEqualTo("CANCELLED");
    assertThat(bal(uid)).isEqualByComparingTo("10000");
    assertThat(frozen(uid)).isEqualByComparingTo("0");
    assertThat(org.junit.jupiter.api.Assertions.assertThrows(BizException.class, () -> withdraws.cancelHeld(w.id, 9L, "再来")).code())
        .isEqualTo("WITHDRAW_NOT_HELD");
  }

  @Test
  void 暂停中但交过钱包提供方的_不能直接取消退回_防重复出金() {
    long uid = user();
    WithdrawalRow w = reviewing(uid, "100");
    var s = sender();
    s.next = new WithdrawSender.Wait("提供方说等一等"); // 交过一次，结果不确定，留在待放款
    withdraws.approve(w.id, 9L);
    assertThat(status(w.id)).isEqualTo("APPROVED");
    assertThat(s.sent).hasSize(1);
    pins.freezeWithdraw(uid, "WEB_REQUEST", "测试");
    assertThat(withdraws.held(uid)).singleElement().satisfies(h -> assertThat(h.sentBefore()).isTrue());
    assertThat(org.junit.jupiter.api.Assertions.assertThrows(BizException.class, () -> withdraws.cancelHeld(w.id, 9L, "取消")).code())
        .isEqualTo("WITHDRAW_SENT_BEFORE");
    assertThat(status(w.id)).isEqualTo("APPROVED");
    assertThat(frozen(uid)).isEqualByComparingTo("100");
    withdraws.advance();
    assertThat(s.sent).as("暂停中不再交出去").hasSize(1);
  }

  // ---------- 冻结的时候处理在途的 ----------

  @Test
  void 提现冻结时_冷静期_待审核_延迟中的取消退回_待放款的暂停() {
    long uid = user();
    WithdrawalRow cooling = submit(uid, "100");
    time().advance(Duration.ofHours(3));
    withdraws.advance();
    WithdrawalRow reviewing = cooling; // 冷静期满进了待审核
    WithdrawalRow delayed = reviewing(uid, "3000");
    withdraws.approve(delayed.id, 9L);
    WithdrawalRow approved = reviewing(uid, "200");
    withdraws.approve(approved.id, 9L); // 没有广播器，留在待放款
    WithdrawalRow fresh = submit(uid, "50"); // 冷静期中
    assertThat(status(reviewing.id)).isEqualTo("REVIEWING");
    assertThat(status(delayed.id)).isEqualTo("DELAYED");
    assertThat(status(approved.id)).isEqualTo("APPROVED");
    assertThat(status(fresh.id)).isEqualTo("COOLING");

    pins.freezeWithdraw(uid, "WEB_REQUEST", "客户在网页申请冻结");

    assertThat(status(reviewing.id)).isEqualTo("CANCELLED");
    assertThat(status(delayed.id)).isEqualTo("CANCELLED");
    assertThat(status(fresh.id)).isEqualTo("CANCELLED");
    assertThat(lastNote(fresh.id)).contains("提现已冻结：客户在网页申请冻结");
    assertThat(status(approved.id)).as("已审核待放款的不取消（可能正在交出去），暂停").isEqualTo("APPROVED");
    assertThat(frozen(uid)).as("只剩待放款那笔").isEqualByComparingTo("200");
    assertThat(bal(uid)).isEqualByComparingTo("9800");
    var s = sender();
    withdraws.advance();
    assertThat(s.sent).isEmpty();
  }

  @Test
  void 账户冻结时在途的取消退回() {
    long uid = user();
    WithdrawalRow cooling = submit(uid, "100");
    WithdrawalRow reviewing = reviewing(uid, "300");
    accounts.freeze(uid);
    assertThat(status(cooling.id)).isEqualTo("CANCELLED");
    assertThat(status(reviewing.id)).isEqualTo("CANCELLED");
    assertThat(lastNote(reviewing.id)).contains("账户已冻结");
    assertThat(frozen(uid)).isEqualByComparingTo("0");
    assertThat(bal(uid)).isEqualByComparingTo("10000");
  }

  @Test
  void 提现密码30天锁两次冻结时在途的也取消() {
    long uid = user();
    WithdrawalRow w = submit(uid, "100"); // 先按原规则提交一笔（TG 提现总开关默认关），再变成 TG 客户
    app.trading.support.PinTestSupport.makeTg(jdbc, uid);
    jdbc.update("INSERT INTO account_withdraw_pin_lock (user_id, locked_at) VALUES (?, ?)", uid,
        java.time.LocalDateTime.now(clock.withZone(java.time.ZoneOffset.UTC)).minusDays(1));
    app.trading.support.PinTestSupport.setPin(jdbc, uid, "135790");
    jdbc.update("UPDATE account_withdraw_pin SET failed_count = 4 WHERE user_id = ?", uid);
    // 第 5 次输错：锁定，30 天内第 2 次 → 冻结提现
    assertThat(org.junit.jupiter.api.Assertions.assertThrows(BizException.class, () -> pins.verify(uid, app.trading.support.PinTestSupport.input(redis, uid, "000111"))).code())
        .isEqualTo("PIN_LOCKED");
    assertThat(pins.status(uid).withdrawFrozen()).isTrue();
    assertThat(status(w.id)).isEqualTo("CANCELLED");
    assertThat(frozen(uid)).isEqualByComparingTo("0");
  }

  @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
}
