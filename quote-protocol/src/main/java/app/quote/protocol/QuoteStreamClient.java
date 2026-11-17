package app.quote.protocol;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 报价内部 WebSocket 的客户端（trading-app 用；测试里也用它连真实的 quote-app）：
 * <ul>
 *   <li>连上 → 收到 hello → 发 subscribe（带上次的 epoch 和每个品种的序号，服务器补齐断线期间的报价）；</li>
 *   <li>每条报价先过 {@link SeqTracker}：重复的丢掉，跳号的攒着并发 resync，交给 {@link Listener} 的一定是按序号连续的；</li>
 *   <li>断线（服务器关闭、网络错误、超过 idleTimeout 没收到任何消息）后按 reconnectInitial 起每次翻倍、最长 reconnectMax 重连；</li>
 *   <li>等补齐超过 2 秒还没接上的品种直接要快照。</li>
 * </ul>
 * 回调都在 JDK WebSocket 的接收线程上依次执行（同一条连接上不会并发），不要做耗时操作。Key 只放在请求头，不写日志。
 */
public final class QuoteStreamClient implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(QuoteStreamClient.class);
  /** 等补齐多久还没接上就直接要快照 */
  static final long RESYNC_TIMEOUT_MILLIS = 2000;
  public static final String KEY_HEADER = "X-Quote-Key";

  /** 业务回调 */
  public interface Listener {
    /** 连上并收到 hello；epochChanged 表示报价进程重启过（之前的序号作废） */
    default void onHello(Message.Hello hello, boolean epochChanged) {}

    /** 一条报价（同一品种按序号连续，快照除外） */
    void onQuote(QuoteFrame frame);

    default void onSample(Message.Sample sample) {}

    default void onHeartbeat(Message.Heartbeat heartbeat) {}

    /** 关注的 K 线有变化（{@link #watchBars}） */
    default void onBar(Message.Bar bar) {}

    /** 连接状态变化 */
    default void onConnection(boolean connected) {}
  }

  /**
   * 连接参数。
   *
   * @param idleTimeout 多久没收到任何消息就断开重连（服务器每秒一条心跳）
   */
  public record Options(Duration reconnectInitial, Duration reconnectMax, Duration idleTimeout, Duration connectTimeout) {
    public static Options defaults() {
      return new Options(Duration.ofMillis(500), Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(5));
    }
  }

  private final URI uri;
  private final String key;
  private final Listener listener;
  private final Options options;
  private final Clock clock;
  private final SeqTracker tracker = new SeqTracker();
  private final HttpClient http;
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "quote-stream-client");
    t.setDaemon(true);
    return t;
  });
  private volatile WebSocket ws;
  private volatile boolean closed;
  private volatile boolean connected;
  private volatile Instant lastMessageAt;
  private volatile long reconnects;
  private volatile String lastError;
  /** 关注的 K 线（"品种|周期"），每次连上都重新告诉服务器 */
  private volatile java.util.Set<String> watchedBars = java.util.Set.of();
  private Duration backoff;
  /** 连接代数：旧连接的回调晚到时不处理 */
  private volatile long generation;

  public QuoteStreamClient(URI uri, String key, Listener listener, Options options, Clock clock) {
    this.uri = uri;
    this.key = key == null ? "" : key;
    this.listener = listener;
    this.options = options;
    this.clock = clock;
    this.backoff = options.reconnectInitial();
    this.http = HttpClient.newBuilder().connectTimeout(options.connectTimeout()).build();
  }

  public void start() {
    timer.execute(this::connect);
    long idleCheck = Math.max(100, Math.min(1000, options.idleTimeout().toMillis() / 2));
    timer.scheduleWithFixedDelay(this::watchdog, idleCheck, idleCheck, TimeUnit.MILLISECONDS);
  }

  public boolean connected() {
    return connected;
  }

  /** 最后一次收到任何消息的时间；从没收到为空 */
  public Instant lastMessageAt() {
    return lastMessageAt;
  }

  public long reconnects() {
    return reconnects;
  }

  public String lastError() {
    return lastError;
  }

  /** 每个品种最后连续收到的序号（测试和后台看） */
  public Map<String, Long> since() {
    return tracker.since();
  }

  public String epoch() {
    return tracker.epoch();
  }

  /** 要推哪些 K 线的实时变化（整份替换）；连着时立即告诉服务器，重连后自动再告诉一次 */
  public void watchBars(java.util.Set<String> keys) {
    watchedBars = java.util.Set.copyOf(keys);
    WebSocket w = ws;
    if (connected && w != null) send(w, new Message.WatchBars(watchedBars));
  }

  /** 测试用：从客户端这边断开当前连接（不发关闭帧），之后照常重连 */
  public void dropConnection() {
    WebSocket w = ws;
    if (w != null) w.abort();
    lastError = "dropped";
    scheduleReconnect(generation);
  }

  private void connect() {
    if (closed) return;
    long gen = ++generation;
    try {
      http.newWebSocketBuilder().header(KEY_HEADER, key).connectTimeout(options.connectTimeout()).buildAsync(uri, new Handler(gen))
          .whenComplete((w, e) -> {
            if (e != null) {
              lastError = e.getClass().getSimpleName();
              log.warn("连接报价服务失败：{}，{} 毫秒后重连", lastError, backoff.toMillis());
              scheduleReconnect(gen);
            } else {
              ws = w;
            }
          });
    } catch (RuntimeException e) {
      lastError = e.getClass().getSimpleName();
      scheduleReconnect(gen);
    }
  }

  private synchronized void scheduleReconnect(long gen) {
    if (closed || gen != generation) return;
    generation++; // 这一代作废，晚到的回调不再处理
    if (connected) {
      connected = false;
      listener.onConnection(false);
    }
    reconnects++;
    long wait = backoff.toMillis();
    backoff = backoff.multipliedBy(2).compareTo(options.reconnectMax()) > 0 ? options.reconnectMax() : backoff.multipliedBy(2);
    timer.schedule(this::connect, wait, TimeUnit.MILLISECONDS);
  }

  private void watchdog() {
    try {
      WebSocket w = ws;
      Instant last = lastMessageAt;
      if (connected && w != null && last != null && Duration.between(last, clock.instant()).compareTo(options.idleTimeout()) > 0) {
        log.warn("报价服务 {} 毫秒没有任何消息，断开重连", options.idleTimeout().toMillis());
        lastError = "idle";
        w.abort();
        scheduleReconnect(generation);
        return;
      }
      if (connected && w != null) {
        Map<String, Long> overdue = tracker.overdue(clock.millis(), RESYNC_TIMEOUT_MILLIS);
        if (!overdue.isEmpty()) send(w, new Message.Resync(overdue));
      }
    } catch (RuntimeException e) {
      log.error("报价连接检查失败", e);
    }
  }

  /** JDK WebSocket 上一条没发完不能发下一条：加锁、等发完（消息都很小） */
  private synchronized void send(WebSocket w, Message m) {
    try {
      w.sendText(QuoteCodec.encode(m), true).join();
    } catch (RuntimeException e) {
      log.debug("发给报价服务失败：{}", e.getClass().getSimpleName());
    }
  }

  private void handle(WebSocket w, String text) {
    lastMessageAt = clock.instant();
    Message m;
    try {
      m = QuoteCodec.decode(text);
    } catch (IllegalArgumentException e) {
      log.warn("忽略无法解析的报价消息：{}", e.getMessage());
      return;
    }
    switch (m) {
      case Message.Hello h -> {
        boolean changed = tracker.epoch(h.epoch());
        backoff = options.reconnectInitial();
        connected = true;
        send(w, new Message.Subscribe(h.epoch(), changed ? Map.of() : tracker.since()));
        if (!watchedBars.isEmpty()) send(w, new Message.WatchBars(watchedBars));
        listener.onConnection(true);
        listener.onHello(h, changed);
      }
      case QuoteFrame f -> {
        SeqTracker.Result r = tracker.offer(f, clock.millis());
        for (QuoteFrame d : r.deliver()) {
          try {
            listener.onQuote(d);
          } catch (RuntimeException e) {
            log.error("处理报价失败 {}", d.symbol(), e);
          }
        }
        if (!r.resync().isEmpty()) send(w, new Message.Resync(r.resync()));
      }
      case Message.Sample s -> listener.onSample(s);
      case Message.Heartbeat h -> listener.onHeartbeat(h);
      case Message.Bar b -> listener.onBar(b);
      case Message.Error e -> log.warn("报价服务返回错误 {}：{}", e.code(), e.message());
      default -> log.debug("忽略客户端方向的消息");
    }
  }

  private final class Handler implements WebSocket.Listener {
    private final long gen;
    private final StringBuilder buf = new StringBuilder();

    Handler(long gen) {
      this.gen = gen;
    }

    @Override
    public void onOpen(WebSocket w) {
      w.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
      if (gen == generation) {
        buf.append(data);
        if (last) {
          String text = buf.toString();
          buf.setLength(0);
          handle(w, text);
        }
      }
      w.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket w, int status, String reason) {
      lastError = "closed " + status;
      log.warn("报价服务关闭了连接（{}），重连", status);
      scheduleReconnect(gen);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket w, Throwable error) {
      lastError = error.getClass().getSimpleName();
      log.warn("报价连接出错：{}，重连", lastError);
      scheduleReconnect(gen);
    }
  }

  @Override
  public void close() {
    closed = true;
    timer.shutdownNow();
    WebSocket w = ws;
    if (w != null) w.abort();
    if (connected) {
      connected = false;
      listener.onConnection(false);
    }
  }
}
