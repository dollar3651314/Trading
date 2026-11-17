package app.quote.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 按品种检查序号：连续的照交，重复的丢，跳号的攒着要补齐，快照重新起点（技术方案 02 页第 2 节：序号连续、断线补齐、快照恢复）。 */
class SeqTrackerTest {

  static QuoteFrame f(String symbol, long seq) {
    return new QuoteFrame(symbol, seq, new BigDecimal("1.0" + seq), new BigDecimal("1.1" + seq), 1000 + seq, "FRESH", "primary", "alltick", true, false);
  }

  static List<Long> seqs(SeqTracker.Result r) {
    return r.deliver().stream().map(QuoteFrame::seq).toList();
  }

  @Test
  void 第一条是起点_之后连续的照交() {
    SeqTracker t = new SeqTracker();
    assertThat(seqs(t.offer(f("EURUSD", 41), 0))).containsExactly(41L);
    assertThat(seqs(t.offer(f("EURUSD", 42), 0))).containsExactly(42L);
    assertThat(t.since()).containsEntry("EURUSD", 42L);
  }

  @Test
  void 重复和更旧的序号丢掉() {
    SeqTracker t = new SeqTracker();
    t.offer(f("EURUSD", 1), 0);
    t.offer(f("EURUSD", 2), 0);
    SeqTracker.Result r = t.offer(f("EURUSD", 2), 0);
    assertThat(r.deliver()).isEmpty();
    assertThat(r.resync()).isEmpty();
    assertThat(t.offer(f("EURUSD", 1), 0).deliver()).isEmpty();
  }

  @Test
  void 跳号时攒着并只要一次补齐_补齐到了按顺序交出去() {
    SeqTracker t = new SeqTracker();
    t.offer(f("EURUSD", 1), 0);
    SeqTracker.Result gap = t.offer(f("EURUSD", 4), 10);
    assertThat(gap.deliver()).isEmpty();
    assertThat(gap.resync()).isEqualTo(Map.of("EURUSD", 1L));
    assertThat(t.offer(f("EURUSD", 5), 20).resync()).as("同一个品种在等补齐时不重复要").isEmpty();
    assertThat(t.waiting()).isEqualTo(1);
    assertThat(seqs(t.offer(f("EURUSD", 2), 30))).containsExactly(2L);
    assertThat(seqs(t.offer(f("EURUSD", 3), 40))).as("接上以后攒着的 4、5 一起交出").containsExactly(3L, 4L, 5L);
    assertThat(t.waiting()).isZero();
    assertThat(t.since()).containsEntry("EURUSD", 5L);
  }

  @Test
  void 品种之间互不影响() {
    SeqTracker t = new SeqTracker();
    t.offer(f("EURUSD", 1), 0);
    t.offer(f("XAUUSD", 7), 0);
    assertThat(t.offer(f("EURUSD", 3), 0).resync()).containsOnlyKeys("EURUSD");
    assertThat(seqs(t.offer(f("XAUUSD", 8), 0))).containsExactly(8L);
  }

  @Test
  void 快照重新起点_丢掉更旧的_交出能接上的() {
    SeqTracker t = new SeqTracker();
    t.offer(f("EURUSD", 1), 0);
    t.offer(f("EURUSD", 6), 0); // 跳号，攒着
    t.offer(f("EURUSD", 9), 0);
    SeqTracker.Result r = t.offer(f("EURUSD", 5).asSnapshot(), 0);
    assertThat(seqs(r)).as("快照 5，接上 6；9 还接不上").containsExactly(5L, 6L);
    assertThat(t.waiting()).isEqualTo(1);
    assertThat(seqs(t.offer(f("EURUSD", 7), 0))).containsExactly(7L);
    assertThat(seqs(t.offer(f("EURUSD", 8), 0))).containsExactly(8L, 9L);
    assertThat(t.waiting()).isZero();
  }

  @Test
  void 快照比当前序号旧也以快照为准() {
    SeqTracker t = new SeqTracker();
    t.offer(f("EURUSD", 10), 0);
    assertThat(seqs(t.offer(f("EURUSD", 3).asSnapshot(), 0))).containsExactly(3L);
    assertThat(seqs(t.offer(f("EURUSD", 4), 0))).containsExactly(4L);
  }

  @Test
  void 等补齐超时改要快照_并重新计时() {
    SeqTracker t = new SeqTracker();
    t.offer(f("EURUSD", 1), 0);
    t.offer(f("EURUSD", 3), 1000);
    assertThat(t.overdue(2999, 2000)).isEmpty();
    assertThat(t.overdue(3000, 2000)).isEqualTo(Map.of("EURUSD", -1L));
    assertThat(t.overdue(3001, 2000)).as("刚要过，重新计时").isEmpty();
  }

  @Test
  void 攒太多就直接要快照() {
    SeqTracker t = new SeqTracker();
    t.offer(f("EURUSD", 1), 0);
    t.offer(f("EURUSD", 3), 0);
    SeqTracker.Result r = null;
    for (int i = 0; i < SeqTracker.MAX_PENDING; i++) r = t.offer(f("EURUSD", 4 + i), 0);
    assertThat(r.resync()).isEqualTo(Map.of("EURUSD", -1L));
  }

  @Test
  void epoch变了丢掉所有序号() {
    SeqTracker t = new SeqTracker();
    assertThat(t.epoch("a")).isTrue();
    t.offer(f("EURUSD", 100), 0);
    assertThat(t.epoch("a")).isFalse();
    assertThat(t.since()).containsEntry("EURUSD", 100L);
    assertThat(t.epoch("b")).as("报价进程重启过").isTrue();
    assertThat(t.since()).isEmpty();
    assertThat(seqs(t.offer(f("EURUSD", 1), 0))).as("重启后序号从 1 开始，不能当成重复丢掉").containsExactly(1L);
  }
}
