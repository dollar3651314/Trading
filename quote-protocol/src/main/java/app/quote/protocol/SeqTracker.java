package app.quote.protocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 客户端按品种检查序号（不联网、不看时钟以外的东西，测试里直接喂消息）：
 * <ul>
 *   <li>快照：以它为新的起点，交出去；之前攒着的、序号更大的报价接着按顺序交出去。</li>
 *   <li>这个品种还没有起点：第一条就是起点（每条报价都是完整的买卖价，不是增量）。</li>
 *   <li>序号 = 上一条 + 1：交出去。序号 ≤ 上一条：重复（例如补齐和实时推送重叠），丢掉。</li>
 *   <li>序号跳了：先攒着，返回要补齐的品种和最后连续的序号（调用方发 {@code resync}）；补齐的报价到了以后按顺序交出去。
 *       同一个品种在等补齐时不重复要；等太久（{@link #overdue}）或者攒得太多就直接要快照。</li>
 * </ul>
 * 交出去的报价对每个品种都是严格按序号、不重复、不跳号的（除非是快照重新起点）。线程安全（方法都加锁）。
 */
public final class SeqTracker {

  /** 一个品种最多攒多少条跳号后的报价，超过就要快照 */
  static final int MAX_PENDING = 1000;

  /**
   * offer 的结果。
   *
   * @param deliver 按顺序交给业务的报价
   * @param resync 要补齐的品种 → 最后连续的序号（-1 = 要快照）；不需要时为空
   */
  public record Result(List<QuoteFrame> deliver, Map<String, Long> resync) {}

  private final Map<String, Long> last = new HashMap<>();
  private final Map<String, TreeMap<Long, QuoteFrame>> pending = new HashMap<>();
  /** 品种 → 开始等补齐的时间（毫秒） */
  private final Map<String, Long> waitingSince = new HashMap<>();
  private String epoch;

  /** 报价进程的 epoch 变了（重启过）：丢掉所有序号，返回 true */
  public synchronized boolean epoch(String e) {
    if (e != null && e.equals(epoch)) return false;
    epoch = e;
    last.clear();
    pending.clear();
    waitingSince.clear();
    return true;
  }

  public synchronized String epoch() {
    return epoch;
  }

  /** 每个品种最后连续收到的序号（断线重连时放进 subscribe 的 since） */
  public synchronized Map<String, Long> since() {
    return new LinkedHashMap<>(last);
  }

  /** 这个品种最后连续收到的序号；没有为空 */
  public synchronized Long last(String symbol) {
    return last.get(symbol);
  }

  /** 收到一条报价；nowMillis 用来记开始等补齐的时间 */
  public synchronized Result offer(QuoteFrame f, long nowMillis) {
    String s = f.symbol();
    List<QuoteFrame> out = new ArrayList<>();
    Map<String, Long> resync = new LinkedHashMap<>();
    Long prev = last.get(s);
    if (f.snapshot() || prev == null) {
      last.put(s, f.seq());
      out.add(f);
      drain(s, out);
    } else if (f.seq() <= prev) {
      // 重复，丢掉
    } else if (f.seq() == prev + 1) {
      last.put(s, f.seq());
      out.add(f);
      drain(s, out);
    } else {
      TreeMap<Long, QuoteFrame> p = pending.computeIfAbsent(s, k -> new TreeMap<>());
      p.put(f.seq(), f);
      if (p.size() > MAX_PENDING) {
        p.clear();
        waitingSince.put(s, nowMillis);
        resync.put(s, -1L);
      } else if (!waitingSince.containsKey(s)) {
        waitingSince.put(s, nowMillis);
        resync.put(s, prev);
      }
    }
    return new Result(out, resync);
  }

  /** 交出攒着的、能接上的报价；丢掉已经过时的 */
  private void drain(String s, List<QuoteFrame> out) {
    TreeMap<Long, QuoteFrame> p = pending.get(s);
    if (p == null) {
      waitingSince.remove(s);
      return;
    }
    long cur = last.get(s);
    p.headMap(cur, true).clear();
    while (!p.isEmpty() && p.firstKey() == cur + 1) {
      QuoteFrame next = p.pollFirstEntry().getValue();
      out.add(next);
      cur = next.seq();
    }
    last.put(s, cur);
    if (p.isEmpty()) {
      pending.remove(s);
      waitingSince.remove(s);
    }
  }

  /** 等补齐超过 timeoutMillis 还没接上的品种：直接要快照（返回品种 → -1），并重新计时 */
  public synchronized Map<String, Long> overdue(long nowMillis, long timeoutMillis) {
    Map<String, Long> out = new LinkedHashMap<>();
    for (var e : waitingSince.entrySet()) {
      if (nowMillis - e.getValue() >= timeoutMillis) out.put(e.getKey(), -1L);
    }
    out.keySet().forEach(s -> waitingSince.put(s, nowMillis));
    return out;
  }

  /** 正在等补齐的品种数 */
  public synchronized int waiting() {
    return waitingSince.size();
  }
}
