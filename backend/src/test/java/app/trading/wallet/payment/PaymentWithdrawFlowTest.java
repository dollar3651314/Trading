package app.trading.wallet.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.trading.common.error.BizException;
import app.trading.payment.api.PayNetwork;
import app.trading.payment.api.PaymentApi;
import app.trading.payment.api.PayoutState;
import app.trading.payment.fake.FakeTopUp;
import app.trading.wallet.withdraw.WithdrawService;
import app.trading.wallet.withdraw.WithdrawTestHooks;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 提现两道审（TD-1 B、AC-W4、AC-W5）：Trading 审核通过 → 1 分钟内交 TopUP（防重键 = 提现单号）→ "等待放款审核" → TopUP 回成功 / 失败 / 驳回 / 未知。
 * 单笔上限 5,000（TD-15 A）、当日累计 &lt; 30,000；回调乱序、重复；TopUP 超时不在事务里；24 小时后重试不重复付款。
 */
class PaymentWithdrawFlowTest extends PaymentTestSupport {

  static final String ETH = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed";
  static final String TRON = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";

  @Autowired WithdrawService withdraws;
  @Autowired ApplicationContext ctx;
  @Autowired TransactionTemplate tx;

  String status(long id) {
    return jdbc.queryForObject("SELECT status FROM wallet_withdrawal WHERE id = ?", String.class, id);
  }

  long publicNo(long id) {
    return jdbc.queryForObject("SELECT public_no FROM wallet_withdrawal WHERE id = ?", Long.class, id);
  }

  /** 冷静期过了、进入待审核的一笔 */
  long reviewing(long uid, String network, String address, String amount) {
    List<Long> have = jdbc.queryForList("SELECT id FROM wallet_whitelist WHERE user_id = ? AND network = ? AND address = ?", Long.class, uid, network, address);
    long wl = have.isEmpty() ? WithdrawTestHooks.activeAddress(ctx, jdbc, uid, network, address) : have.getFirst();
    long id = WithdrawTestHooks.submit(withdraws, uid, wl, amount);
    time().advance(Duration.ofHours(2).plusSeconds(1));
    WithdrawTestHooks.advance(withdraws);
    assertThat(status(id)).isEqualTo("REVIEWING");
    return id;
  }

  FakeTopUp.Withdrawal topup(long id) {
    return FAKE.byMerchantRef(String.valueOf(publicNo(id))).orElseThrow();
  }

  /** 这一笔在 TopUP 建了几单（定时推进会顺带处理库里别的测试留下的提现，所以不看总数） */
  long createdFor(long id) {
    String ref = String.valueOf(publicNo(id));
    return FAKE.withdrawals.values().stream().filter(w -> ref.equals(w.merchantRef)).count();
  }

  String code(Runnable r) {
    try {
      r.run();
      return "OK";
    } catch (BizException e) {
      return e.code();
    }
  }

  @Test
  void 单笔上限5000_5000能提交_5000点01被拒_当日累计要少于30000_AC_W4() {
    long uid = user("40000", true);
    long wl = WithdrawTestHooks.activeAddress(ctx, jdbc, uid, "TRC20", TRON);
    assertThat(code(() -> WithdrawTestHooks.submit(withdraws, uid, wl, "5000.01"))).isEqualTo("AMOUNT_ABOVE_MAX");
    assertThat(code(() -> WithdrawTestHooks.submit(withdraws, uid, wl, "5000"))).isEqualTo("OK");
    for (int i = 0; i < 4; i++) WithdrawTestHooks.submit(withdraws, uid, wl, "5000");
    // 今天已用 25,000：再提 5,000 等于 30,000，不是"少于"
    assertThat(code(() -> WithdrawTestHooks.submit(withdraws, uid, wl, "5000"))).isEqualTo("DAILY_LIMIT_EXCEEDED");
    assertThat(code(() -> WithdrawTestHooks.submit(withdraws, uid, wl, "4999.99"))).isEqualTo("OK");
  }

  @Test
  void 后台单笔上限最多只能改到5000_TQ13() throws Exception {
    assertThat(app.trading.common.config.ConfigKey.WITHDRAW_MAX_SINGLE.normalize("5000")).contains("5000");
    assertThat(app.trading.common.config.ConfigKey.WITHDRAW_MAX_SINGLE.normalize("5000.01")).isEmpty();
    assertThat(app.trading.common.config.ConfigKey.WITHDRAW_MAX_SINGLE.normalize("10000")).isEmpty();
    assertThat(jdbc.queryForObject("SELECT value FROM common_config WHERE config_key = 'withdraw.maxSingle'", String.class)).isEqualTo("5000");
  }

  @Test
  void 小额_审核通过立刻交TopUP_等待放款审核_TopUP放款成功后已完成_有交易哈希_AC_W4() throws Exception {
    long uid = user("1000", true);
    long id = reviewing(uid, "TRC20", TRON, "100");
    withdraws.approve(id, 7);
    assertThat(status(id)).as("交给 TopUP 了（两道审的第二道）").isEqualTo("BROADCASTING");
    FakeTopUp.Withdrawal w = topup(id);
    assertThat(w.chain).as("每次都传链（TopUP 不传默认 TRON）").isEqualTo("tron");
    assertThat(w.amount).as("付给客户 = 金额 − 手续费").isEqualByComparingTo("99");
    assertThat(w.to).isEqualTo(TRON);
    assertThat(api.payoutInfo(id).orElseThrow().state()).isEqualTo(PayoutState.PENDING);
    assertThat(frozen(uid)).isEqualByComparingTo("100");

    deliverAll();
    deliver(FAKE.moveWithdrawal(w.no, "approved", null, null));
    assertThat(api.payoutInfo(id).orElseThrow().state()).isEqualTo(PayoutState.APPROVED);
    assertThat(status(id)).isEqualTo("BROADCASTING");
    deliver(FAKE.moveWithdrawal(w.no, "broadcast", null, null));
    deliver(FAKE.moveWithdrawal(w.no, "completed", "f".repeat(64), null));
    FAKE.pending.clear();
    assertThat(status(id)).isEqualTo("COMPLETED");
    assertThat(jdbc.queryForObject("SELECT tx_hash FROM wallet_withdrawal WHERE id = ?", String.class, id)).isEqualTo("f".repeat(64));
    assertThat(frozen(uid)).isEqualByComparingTo("0");
    assertThat(balance(uid)).isEqualByComparingTo("900");
    assertThat(createdFor(id)).isEqualTo(1);
  }

  @Test
  void 大额_延迟期结束后1分钟内交TopUP_AC_W4() throws Exception {
    long uid = user("5000", true);
    long id = reviewing(uid, "ERC20", ETH, "3000");
    withdraws.approve(id, 7);
    assertThat(status(id)).isEqualTo("DELAYED");
    assertThat(createdFor(id)).isZero();
    time().advance(Duration.ofHours(6).plusSeconds(1));
    // 定时任务每 30 秒跑一次：延迟期满的这一次就交出去
    WithdrawTestHooks.advance(withdraws);
    assertThat(status(id)).isEqualTo("BROADCASTING");
    assertThat(topup(id).chain).isEqualTo("eth");
    assertThat(topup(id).amount).isEqualByComparingTo("2995");
  }

  @Test
  void 同一笔重复触发放款_TopUP只收到一个防重键_只付一次_AC_W4() throws Exception {
    long uid = user("1000", true);
    long id = reviewing(uid, "TRC20", TRON, "100");
    // TopUP 收下了，但 Trading 没收到回应（超时）：留在"已审核待放款"，不退款
    FAKE.loseWithdrawReply = true;
    withdraws.approve(id, 7);
    assertThat(status(id)).isEqualTo("APPROVED");
    assertThat(api.payoutInfo(id).orElseThrow().state()).isEqualTo(PayoutState.SUBMITTING);
    FAKE.loseWithdrawReply = false;
    // 下一次定时任务用同一个防重键重提：TopUP 返回第一次的结果，不新建
    WithdrawTestHooks.advance(withdraws);
    WithdrawTestHooks.advance(withdraws);
    assertThat(status(id)).isEqualTo("BROADCASTING");
    assertThat(createdFor(id)).as("只建了一笔").isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT submit_count FROM pay_payout WHERE withdrawal_id = ?", Integer.class, id)).as("同一个防重键提交了两次").isEqualTo(2);
    assertThat(api.payoutInfo(id).orElseThrow().providerRef()).isEqualTo(topup(id).no);
  }

  @Test
  void 第一次提交23小时后还没拿到结果_不再自动重提_记未知不退款_TG1前的保护() throws Exception {
    long uid = user("1000", true);
    long id = reviewing(uid, "TRC20", TRON, "100");
    FAKE.down = true;
    withdraws.approve(id, 7);
    assertThat(status(id)).isEqualTo("APPROVED");
    time().advance(Duration.ofHours(23).plusMinutes(1));
    // TopUP 的防重键 24 小时后失效：再提可能付两次，所以不提了
    FAKE.down = false;
    WithdrawTestHooks.advance(withdraws);
    time().advance(Duration.ofHours(2));
    WithdrawTestHooks.advance(withdraws);
    assertThat(createdFor(id)).as("没有再提交").isZero();
    assertThat(api.payoutInfo(id).orElseThrow().state()).isEqualTo(PayoutState.UNKNOWN);
    assertThat(status(id)).as("不退款，等人查").isEqualTo("APPROVED");
    assertThat(frozen(uid)).isEqualByComparingTo("100");
  }

  @Test
  void TopUP驳回或失败_冻结全部退回_客户收到通知_AC_W5() throws Exception {
    long uid = user("1000", true);
    long a = reviewing(uid, "TRC20", TRON, "100");
    withdraws.approve(a, 7);
    deliver(FAKE.moveWithdrawal(topup(a).no, "rejected", null, "地址有风险"));
    assertThat(status(a)).isEqualTo("FAILED_REFUNDED");
    long b = reviewing(uid, "TRC20", TRON, "200");
    withdraws.approve(b, 7);
    deliver(FAKE.moveWithdrawal(topup(b).no, "approved", null, null));
    deliver(FAKE.moveWithdrawal(topup(b).no, "failed", null, null));
    assertThat(status(b)).isEqualTo("FAILED_REFUNDED");
    assertThat(frozen(uid)).isEqualByComparingTo("0");
    assertThat(balance(uid)).isEqualByComparingTo("1000");
    assertThat(jdbc.queryForList("SELECT JSON_UNQUOTE(JSON_EXTRACT(params, '$.status')) FROM notify_message WHERE user_id = ? AND template_code = 'WITHDRAW_STATUS'",
        String.class, uid)).contains("FAILED_REFUNDED");
  }

  @Test
  void 查询返回未知_不退款_后台重新查询查清后照常完成_AC_W5() throws Exception {
    long uid = user("1000", true);
    long id = reviewing(uid, "TRC20", TRON, "100");
    withdraws.approve(id, 7);
    String no = topup(id).no;
    // TopUP 那边查不到这笔（例如被人在数据库里删了）：未知
    FakeTopUp.Withdrawal gone = FAKE.withdrawals.remove(no);
    assertThat(api.refreshPayout(id)).isEqualTo(PayoutState.UNKNOWN);
    time().advance(Duration.ofMinutes(10));
    WithdrawTestHooks.track(withdraws);
    assertThat(status(id)).as("未知不退款").isEqualTo("BROADCASTING");
    assertThat(frozen(uid)).isEqualByComparingTo("100");
    FAKE.withdrawals.put(no, gone);
    gone.status = "completed";
    gone.txid = "a".repeat(64);
    assertThat(api.refreshPayout(id)).isEqualTo(PayoutState.COMPLETED);
    assertThat(status(id)).isEqualTo("COMPLETED");
  }

  @Test
  void 回调乱序_先到成功再到已批准_不回退() throws Exception {
    long uid = user("1000", true);
    long id = reviewing(uid, "BSC", ETH, "100");
    withdraws.approve(id, 7);
    String no = topup(id).no;
    FAKE.pending.clear();
    FakeTopUp.Callback approved = FAKE.moveWithdrawal(no, "approved", null, null);
    FakeTopUp.Callback broadcast = FAKE.moveWithdrawal(no, "broadcast", null, null);
    FakeTopUp.Callback completed = FAKE.moveWithdrawal(no, "completed", "0x" + "b".repeat(64), null);
    assertThat(deliver(completed)).isEqualTo(200);
    assertThat(deliver(approved)).isEqualTo(200);
    assertThat(deliver(broadcast)).isEqualTo(200);
    assertThat(api.payoutInfo(id).orElseThrow().state()).isEqualTo(PayoutState.COMPLETED);
    assertThat(status(id)).isEqualTo("COMPLETED");
    assertThat(balance(uid)).isEqualByComparingTo("900");
  }

  @Test
  void 回调比提交的返回先到_按商户单号找到这笔() throws Exception {
    long uid = user("1000", true);
    long id = reviewing(uid, "TRC20", TRON, "100");
    // 假 TopUP 一建单就把回调发过来（在 Trading 拿到返回之前）
    FAKE.onCallback = cb -> {
      try {
        deliver(cb);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    };
    withdraws.approve(id, 7);
    FAKE.onCallback = null;
    assertThat(status(id)).isEqualTo("BROADCASTING");
    assertThat(api.payoutInfo(id).orElseThrow().providerRef()).isEqualTo(topup(id).no);
  }

  @Test
  void 同一个回调重复投递只处理一次_签名不对的拒收() throws Exception {
    long uid = user("1000", true);
    long id = reviewing(uid, "TRC20", TRON, "100");
    withdraws.approve(id, 7);
    FakeTopUp.Callback done = FAKE.moveWithdrawal(topup(id).no, "completed", "c".repeat(64), null);
    FakeTopUp.Callback forged = new FakeTopUp.Callback(done.eventId() + "x", done.type(), new java.util.LinkedHashMap<>(done.headers()), done.body());
    forged.headers().put("X-TopUP-Signature", "0".repeat(64));
    assertThat(deliver(forged)).isEqualTo(401);
    assertThat(status(id)).isEqualTo("BROADCASTING");
    assertThat(deliver(done)).isEqualTo(200);
    assertThat(deliver(FAKE.resign(done))).as("TopUP 重试（新的时间戳、同一个事件 ID）").isEqualTo(200);
    assertThat(deliver(done)).isEqualTo(200);
    assertThat(status(id)).isEqualTo("COMPLETED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_ledger l JOIN trading_account a ON a.id = l.account_id WHERE a.user_id = ? AND l.entry_type = 'WITHDRAW_PAYOUT'",
        Integer.class, uid)).isEqualTo(1);
  }

  @Test
  void 回调丢了_过5分钟主动向TopUP查_补上成功状态_AC_W6() throws Exception {
    long uid = user("1000", true);
    long id = reviewing(uid, "TRC20", TRON, "100");
    withdraws.approve(id, 7);
    FAKE.moveWithdrawal(topup(id).no, "completed", "d".repeat(64), null);
    FAKE.pending.clear();
    WithdrawTestHooks.track(withdraws);
    assertThat(status(id)).as("刚提交，不查").isEqualTo("BROADCASTING");
    time().advance(Duration.ofMinutes(6));
    WithdrawTestHooks.track(withdraws);
    assertThat(status(id)).isEqualTo("COMPLETED");
  }

  @Test
  void TopUP不通时_提现停在已审核待放款_不丢_恢复后继续_AC_W9() throws Exception {
    long uid = user("1000", true);
    long id = reviewing(uid, "TRC20", TRON, "100");
    FAKE.down = true;
    withdraws.approve(id, 7);
    WithdrawTestHooks.advance(withdraws);
    assertThat(status(id)).isEqualTo("APPROVED");
    assertThat(frozen(uid)).isEqualByComparingTo("100");
    FAKE.down = false;
    WithdrawTestHooks.advance(withdraws);
    assertThat(status(id)).isEqualTo("BROADCASTING");
  }

  @Test
  void TopUP慢_超时_访问提供方不在数据库事务里() throws Exception {
    long uid = user("1000", true);
    long id = reviewing(uid, "TRC20", TRON, "100");
    // 在事务里直接调用会被拒绝
    assertThatThrownBy(() -> tx.executeWithoutResult(s -> api.payout(id, publicNo(id), uid, PayNetwork.TRC20, TRON, new BigDecimal("99"))))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("事务");
    // TopUP 超过超时时间（测试里 3 秒）才回应：当作暂时交不了，留在已审核待放款
    FAKE.delayMs = 3500;
    long started = System.nanoTime();
    withdraws.approve(id, 7);
    FAKE.delayMs = 0;
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    assertThat(status(id)).isEqualTo("APPROVED");
    // 审核本身已经提交（另一个连接能看到），不会因为 TopUP 慢而一起回滚
    assertThat(jdbc.queryForObject("SELECT reviewer_id FROM wallet_withdrawal WHERE id = ?", Long.class, id)).isEqualTo(7L);
  }

  @Test
  void 客户接口_每笔提现带付款编号_第二道审核在哪_每一步的时间_AC_W12() throws Exception {
    long uid = user("1000", true);
    long id = reviewing(uid, "TRC20", TRON, "100");
    withdraws.approve(id, 7);
    var rows = jdbc.queryForList("SELECT to_status FROM wallet_withdraw_event WHERE withdrawal_id = ? ORDER BY id", String.class, id);
    assertThat(rows).containsExactly("COOLING", "REVIEWING", "APPROVED", "BROADCASTING");
    var cookie = app.trading.account.auth.SessionTestHooks.cookie(ctx, uid);
    java.util.function.Supplier<String> list = () -> {
      try {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/wallet/withdrawals").cookie(cookie)).andReturn().getResponse()
            .getContentAsString();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    };
    assertThat(list.get()).contains("\"payoutNo\":\"" + topup(id).no + "\"").contains("\"payoutStatus\":\"awaitingRelease\"")
        .containsPattern("\\{\"status\":\"broadcasting\",\"at\":\"\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d");
    deliverAll();
    deliver(FAKE.moveWithdrawal(topup(id).no, "approved", null, null));
    assertThat(list.get()).contains("\"payoutStatus\":\"paying\"");
    // 提供方那边"未知"的，客户也只看到"放款中"
    jdbc.update("UPDATE pay_payout SET status = 'UNKNOWN' WHERE withdrawal_id = ?", id);
    assertThat(list.get()).contains("\"payoutStatus\":\"paying\"");
  }

  @Test
  void 地址不合法_TopUP拒收_确定没付_退回() throws Exception {
    long uid = user("1000", true);
    // 白名单只做格式检查，这里直接改成 TopUP 认为不合法的地址
    long id = reviewing(uid, "TRC20", TRON, "100");
    jdbc.update("UPDATE wallet_withdrawal SET address = 'Tbad' WHERE id = ?", id);
    withdraws.approve(id, 7);
    assertThat(status(id)).isEqualTo("FAILED_REFUNDED");
    assertThat(frozen(uid)).isEqualByComparingTo("0");
    assertThat(createdFor(id)).isZero();
  }

  @Test
  void 余额不够等TopUP补钱_不退款() throws Exception {
    long uid = user("1000", true);
    long id = reviewing(uid, "TRC20", TRON, "100");
    FAKE.available = BigDecimal.ONE;
    withdraws.approve(id, 7);
    assertThat(status(id)).isEqualTo("APPROVED");
    FAKE.available = new BigDecimal("1000");
    WithdrawTestHooks.advance(withdraws);
    assertThat(status(id)).isEqualTo("BROADCASTING");
    assertThat(List.of(api.payoutState(id).orElseThrow())).containsExactly(PayoutState.PENDING);
    assertThat(api).isInstanceOf(PaymentApi.class);
  }
}
