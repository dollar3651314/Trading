package app.quote.open;

import app.quote.protocol.QuoteFrame;
import app.quote.stream.QuoteStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 后台"报价源状态"（原型 P11，需求 03 页第一节"报价源状态"）：每个品种最近 24 小时切换报价源的次数、进入"偏差"的次数、最后一次切换时间。
 * 从推出去的报价里看（{@link QuoteStream.Tap}），不改报价源接入和主备切换的代码。重启后从 0 开始（只在内存，推断够用）。
 */
@Component
class SourceStats implements QuoteStream.Tap {

  private static final Duration WINDOW = Duration.ofHours(24);

  private static final class Sym {
    String feed;
    String state;
    final Deque<Instant> switches = new ArrayDeque<>();
    final Deque<Instant> deviations = new ArrayDeque<>();
    Instant lastSwitch;
  }

  record View(int switches24h, int deviations24h, Instant lastSwitchAt) {}

  private final Map<String, Sym> syms = new ConcurrentHashMap<>();
  private final Clock clock;

  SourceStats(QuoteStream stream, Clock clock) {
    this.clock = clock;
    stream.addTap(this);
  }

  @Override
  public void onQuote(QuoteFrame f) {
    Sym s = syms.computeIfAbsent(f.symbol(), k -> new Sym());
    Instant now = clock.instant();
    synchronized (s) {
      if (s.feed != null && f.feed() != null && !s.feed.equals(f.feed())) {
        s.switches.addLast(now);
        s.lastSwitch = now;
      }
      if ("deviation".equals(f.state()) && !"deviation".equals(s.state)) s.deviations.addLast(now);
      if (f.feed() != null) s.feed = f.feed();
      s.state = f.state();
      trim(s, now);
    }
  }

  View of(String symbol) {
    Sym s = syms.get(symbol);
    if (s == null) return new View(0, 0, null);
    synchronized (s) {
      trim(s, clock.instant());
      return new View(s.switches.size(), s.deviations.size(), s.lastSwitch);
    }
  }

  private static void trim(Sym s, Instant now) {
    Instant from = now.minus(WINDOW);
    while (!s.switches.isEmpty() && s.switches.peekFirst().isBefore(from)) s.switches.removeFirst();
    while (!s.deviations.isEmpty() && s.deviations.peekFirst().isBefore(from)) s.deviations.removeFirst();
  }
}
