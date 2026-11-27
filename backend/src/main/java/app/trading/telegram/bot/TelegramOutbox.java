package app.trading.telegram.bot;

import app.trading.notification.api.TelegramSender.Priority;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

/**
 * 发送队列（TG 机器人第 1 批"发送分三档优先级"）：一个发送线程，按优先级从高到低取（同一档先进先出），并遵守 Telegram 的频率限制：
 * <ul>
 *   <li>同一个聊天两条之间至少隔 {@code per-chat-interval}（默认 1 秒）；这个聊天还没到时间就先发别的聊天的（低优先级的也可以先发）；</li>
 *   <li>全部聊天每秒最多 {@code global-per-second} 条（默认 25）；</li>
 *   <li>429：按 retry_after 暂停这个聊天（并且全部暂停同样长时间），放回队头重发；</li>
 *   <li>403（客户屏蔽了机器人）、400（消息有问题）：不重发，回调失败原因；网络错误、5xx：最多试 3 次，每次隔 2 秒。</li>
 * </ul>
 * 队列在内存里、有上限（{@code queue-capacity}）：满了先挤掉低档的，高档不丢（见 {@link #offer}）；
 * 进程重启时没发出的提醒由通知模块启动时补发（30 分钟以内的重新排队，更早的记失败，{@code NotifyWorker.resumeTelegram}）。
 */
public class TelegramOutbox {

  private static final Logger log = LoggerFactory.getLogger(TelegramOutbox.class);
  static final int MAX_ATTEMPTS = 3;
  static final long RETRY_NANOS = 2_000_000_000L;

  /** 真正调 Telegram 的那一步（测试里换成假的） */
  @FunctionalInterface
  public interface Api {
    <T extends Serializable> T execute(BotApiMethod<T> method) throws TelegramApiException;
  }

  private static final class Job {
    final long chatId;
    final Priority priority;
    final BotApiMethod<? extends Serializable> method;
    final Consumer<String> done;
    int attempts;

    Job(long chatId, Priority priority, BotApiMethod<? extends Serializable> method, Consumer<String> done) {
      this.chatId = chatId;
      this.priority = priority;
      this.method = method;
      this.done = done;
    }
  }

  private final Api api;
  private final TelegramProperties props;
  private final LongSupplier nanoTime;
  private final Object lock = new Object();
  private final Map<Priority, ArrayDeque<Job>> queues = new EnumMap<>(Priority.class);
  /** 每个聊天下一次最早什么时候能发（System.nanoTime） */
  private final Map<Long, Long> chatNext = new HashMap<>();
  private long globalNext = Long.MIN_VALUE;
  private int size;
  private volatile boolean running;
  private Thread thread;

  public TelegramOutbox(Api api, TelegramProperties props) {
    this(api, props, System::nanoTime);
  }

  TelegramOutbox(Api api, TelegramProperties props, LongSupplier nanoTime) {
    this.api = api;
    this.props = props;
    this.nanoTime = nanoTime;
    for (Priority p : Priority.values()) queues.put(p, new ArrayDeque<>());
  }

  static final String EVICTED = "发送队列满，让给了更重要的消息";
  static final String FULL = "发送队列满";

  /**
   * 排队。{@code done} 在发出后回调 {@code null}，确定发不出时回调原因（不含令牌）；可以为空。
   * 队列满了（审查 🟡1）：先挤掉一条比它低档的（从最低档、最新排进来的挤起）；没有更低档的——低、中档直接拒收，
   * <b>高档（安全提醒、强平预警、对客户操作的回复）不丢</b>，超过上限也收下（高档的量受每人每分钟上限约束）。
   *
   * @return 排上了；拒收返回 false（并已回调失败）
   */
  public boolean offer(long chatId, Priority priority, BotApiMethod<? extends Serializable> method, Consumer<String> done) {
    Job job = new Job(chatId, priority, method, done);
    Job evicted = null;
    boolean accepted = true;
    synchronized (lock) {
      if (size >= props.queueCapacity()) {
        evicted = evictBelow(priority);
        if (evicted == null && priority != Priority.HIGH) accepted = false;
      }
      if (accepted) {
        queues.get(priority).addLast(job);
        size++;
        lock.notifyAll();
      }
    }
    if (evicted != null) {
      log.warn("Telegram 发送队列满，挤掉一条 {} 给 {}", evicted.priority, priority);
      complete(evicted, EVICTED);
    }
    if (!accepted) {
      log.warn("Telegram 发送队列满（{} 条），拒收一条 {}", props.queueCapacity(), priority);
      complete(job, FULL);
    }
    return accepted;
  }

  /** 从比 p 低的档里挤掉一条（最低档、最新的先挤）；没有返回 null。调用方持有 lock */
  private Job evictBelow(Priority p) {
    Priority[] all = Priority.values();
    for (int i = all.length - 1; i > p.ordinal(); i--) {
      Job j = queues.get(all[i]).pollLast();
      if (j != null) {
        size--;
        return j;
      }
    }
    return null;
  }

  public int size() {
    synchronized (lock) {
      return size;
    }
  }

  public void start() {
    synchronized (lock) {
      if (running) return;
      running = true;
      thread = Thread.ofPlatform().name("tg-outbox").daemon(true).start(this::loop);
    }
  }

  public void stop() {
    synchronized (lock) {
      running = false;
      lock.notifyAll();
    }
  }

  private long perChatNanos() {
    return props.perChatInterval().toNanos();
  }

  private long globalGapNanos() {
    return 1_000_000_000L / Math.max(1, props.globalPerSecond());
  }

  private void loop() {
    while (running) {
      Job job;
      try {
        job = next();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      if (job == null) continue;
      send(job);
    }
  }

  /** 取下一条能发的；都还没到时间就等（新消息进来会被叫醒） */
  private Job next() throws InterruptedException {
    synchronized (lock) {
      while (running) {
        long now = nanoTime.getAsLong();
        long wait = Long.MAX_VALUE;
        if (globalNext != Long.MIN_VALUE && globalNext - now > 0) {
          wait = globalNext - now;
        } else {
          for (Priority p : Priority.values()) {
            for (Iterator<Job> it = queues.get(p).iterator(); it.hasNext(); ) {
              Job j = it.next();
              Long at = chatNext.get(j.chatId);
              if (at == null || at - now <= 0) {
                it.remove();
                size--;
                chatNext.put(j.chatId, now + perChatNanos());
                globalNext = now + globalGapNanos();
                if (chatNext.size() > 10_000) chatNext.values().removeIf(t -> t - now <= 0);
                return j;
              }
              wait = Math.min(wait, at - now);
            }
          }
        }
        if (size == 0) lock.wait(1_000);
        else lock.wait(Math.max(1, wait / 1_000_000), 0);
      }
      return null;
    }
  }

  private void send(Job j) {
    j.attempts++;
    try {
      api.execute(j.method);
      complete(j, null);
    } catch (TelegramApiRequestException e) {
      Integer code = e.getErrorCode();
      if (code != null && code == 429) {
        int after = e.getParameters() != null && e.getParameters().getRetryAfter() != null ? e.getParameters().getRetryAfter() : 1;
        log.warn("Telegram 限流（429），暂停 {} 秒", after);
        requeueFront(j, after * 1_000_000_000L, true);
      } else if (code != null && code >= 400 && code < 500) {
        // 403：客户屏蔽了机器人；400：消息内容不对（例如按钮网址不合法）。重发也一样，不重发
        complete(j, "Telegram 拒绝（" + code + "）：" + props.scrub(e.getApiResponse()));
      } else {
        retryOrFail(j, "Telegram 返回 " + code + "：" + props.scrub(e.getApiResponse()));
      }
    } catch (TelegramApiException | RuntimeException e) {
      retryOrFail(j, "连不上 Telegram：" + props.scrub(e.getClass().getSimpleName() + ": " + e.getMessage()));
    }
  }

  private void retryOrFail(Job j, String reason) {
    if (j.attempts >= MAX_ATTEMPTS) {
      log.warn("Telegram 消息发送失败（试了 {} 次），不再重发：{}", j.attempts, reason);
      complete(j, reason);
    } else {
      requeueFront(j, RETRY_NANOS, false);
    }
  }

  private void requeueFront(Job j, long delayNanos, boolean global) {
    synchronized (lock) {
      long until = nanoTime.getAsLong() + delayNanos;
      chatNext.put(j.chatId, until);
      if (global) globalNext = until;
      queues.get(j.priority).addFirst(j);
      size++;
      lock.notifyAll();
    }
  }

  private void complete(Job j, String error) {
    if (j.done == null) return;
    try {
      j.done.accept(error);
    } catch (RuntimeException e) {
      log.warn("Telegram 发送回调出错：{}", e.toString());
    }
  }
}
