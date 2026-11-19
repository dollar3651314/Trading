package app.trading.wallet.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import app.trading.trading.api.UserEventsApi;
import app.trading.wallet.withdraw.WithdrawService;
import app.trading.wallet.withdraw.WithdrawTestHooks;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 端到端偶发失败（PR #29 第一次 CI，"后台出金审核通过 → 客户看到'等待放款审核'"卡在"已批准 · 待放款"）的复现：
 * TopUP 的回调比提交的返回先到（假 TopUP 在建单时就同步发 withdrawal.updated 回调，真 TopUP 也可能先到）时，回调处理
 * （PaymentService.callback 的事务 → WithdrawService.payoutUpdate → submittedByProvider）在<b>回调事务还没提交</b>时就推了
 * withdraw / account 事件。客户端收到推送马上重新拉提现记录，读到的还是"已审核待放款"；提交返回后 send() 看到已经是"放款中"，
 * 不再推送 —— 客户端就一直停在旧状态。
 * <p>这里在推送 withdraw 事件的那一刻，用另一个连接（另一个线程）读数据库里的状态，必须已经是事件说的状态（事务已提交）。
 */
class PayoutNotifyCommitTest extends PaymentTestSupport {

  @MockitoSpyBean UserEventsApi events;
  @Autowired ApplicationContext ctx;
  @Autowired WithdrawService withdraws;

  /** 每次推送 withdraw 事件时：事件里的状态、同一时刻别的连接读到的状态 */
  record Seen(String event, String db) {}

  @Test
  void 回调比提交的返回先到_推送时别的连接已经能读到放款中() throws Exception {
    long uid = user("1000", true);
    long wl = WithdrawTestHooks.activeAddress(ctx, jdbc, uid, "TRC20", "TNPeeaaFB7K9cmo4uQpcU32zGK8G1NYqeL");
    long id = WithdrawTestHooks.submit(withdraws, uid, wl, "100");
    time().advance(Duration.ofHours(2).plusSeconds(1));
    WithdrawTestHooks.advance(withdraws);

    List<Seen> seen = new CopyOnWriteArrayList<>();
    var other = Executors.newSingleThreadExecutor();
    try {
      doAnswer(inv -> {
        Map<?, ?> e = inv.getArgument(1);
        if ("withdraw".equals(e.get("type"))) {
          String db = other.submit(() -> jdbc.queryForObject("SELECT status FROM wallet_withdrawal WHERE id = ?", String.class, id)).get();
          seen.add(new Seen(String.valueOf(e.get("status")), db.toLowerCase()));
        }
        return inv.callRealMethod();
      }).when(events).send(anyLong(), any());
      // 和端到端一样：假 TopUP 建单时同步把回调交给 Trading（回调比提交的返回先到）
      FAKE.onCallback = cb -> {
        try {
          deliver(cb);
        } catch (Exception ex) {
          throw new IllegalStateException(ex);
        }
      };

      withdraws.approve(id, 7);

      assertThat(jdbc.queryForObject("SELECT status FROM wallet_withdrawal WHERE id = ?", String.class, id)).isEqualTo("BROADCASTING");
      assertThat(seen).extracting(Seen::event).containsExactly("approved", "broadcasting");
      assertThat(seen).as("推送的时候状态已经提交：客户端收到推送去拉，拉到的就是推送说的状态").allSatisfy(s -> assertThat(s.db()).isEqualTo(s.event()));
    } finally {
      other.shutdownNow();
      FAKE.onCallback = null;
    }
  }
}
