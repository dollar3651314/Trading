package app.trading.market.quote;

import app.quote.protocol.Message;
import app.quote.protocol.QuoteFrame;
import app.quote.protocol.QuoteStreamClient;
import app.trading.common.health.AlertCheck;
import app.trading.market.MarketProperties;
import app.trading.market.config.SymbolCatalog;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import tools.jackson.databind.ObjectMapper;

/**
 * 连报价服务（{@code trading.market.source=remote}，第 2 批）：
 * <ul>
 *   <li>内部 WebSocket（{@link QuoteStreamClient}：序号检查、断线补齐、快照恢复）收到的报价交给 {@link QuoteProcessor}（按品种分线程，同品种有序）；</li>
 *   <li>启动时、每次连上报价服务时、后台改了品种 / 时段 / 休市日 / 模拟起始价以后，把这些整份同步给报价服务（失败按 sync-retry 重试）；</li>
 *   <li>告警（{@link AlertCheck}）：{@code quote.stream} 连不上报价服务；报价服务心跳里带来的告警项（报价源断线、切备源、偏差）原样列出，照样发告警邮件。</li>
 * </ul>
 * 报价服务断了：不再有新报价，{@link QuoteProcessor#latest} 3 秒后不可成交（拒新开仓），撮合没有新报价也不会触发强平；连回来后按序号补齐。
 */
public class RemoteQuotes implements AlertCheck {

  private static final Logger log = LoggerFactory.getLogger(RemoteQuotes.class);

  private final QuoteProcessor processor;
  private final SymbolCatalog catalog;
  private final QuoteAppClient client;
  private final QuoteAppProperties props;
  private final Clock clock;
  private final QuoteStreamClient stream;
  private final ScheduledExecutorService sync = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "quote-catalog-sync"));
  private final AtomicBoolean syncPending = new AtomicBoolean();
  private final Instant startedAt;
  private volatile List<Message.Alert> remoteAlerts = List.of();
  private volatile boolean synced;

  RemoteQuotes(QuoteProcessor processor, SymbolCatalog catalog, QuoteAppProperties props, MarketProperties market, ObjectMapper json, Clock clock) {
    this.processor = processor;
    this.catalog = catalog;
    this.props = props;
    this.clock = clock;
    this.client = new QuoteAppClient(props, json);
    this.startedAt = clock.instant();
    if (props.url().isEmpty()) throw new IllegalStateException("trading.market.source=remote 时要配置 trading.market.quote-app.url（FX_QUOTE_APP_URL）");
    this.stream = new QuoteStreamClient(client.streamUri(), props.key(), new Listener(),
        new QuoteStreamClient.Options(props.reconnectInitial(), props.reconnectMax(), props.idleTimeout(), Duration.ofSeconds(5)), Clock.systemUTC());
    catalog.onReload(this::requestSync);
  }

  @EventListener(ApplicationReadyEvent.class)
  void start() {
    stream.start();
    requestSync();
  }

  public QuoteAppClient client() {
    return client;
  }

  /** 内部 WebSocket 是否连着 */
  public boolean connected() {
    return stream.connected();
  }

  /** 测试用：客户端这边断开当前连接（之后照常重连、补齐） */
  public void dropConnection() {
    stream.dropConnection();
  }

  /** 品种、时段同步过至少一次 */
  public boolean synced() {
    return synced;
  }

  /** 安排一次整份同步（多次请求合并成一次） */
  public void requestSync() {
    if (syncPending.compareAndSet(false, true)) sync.execute(this::doSync);
  }

  private void doSync() {
    syncPending.set(false);
    try {
      client.syncCatalog(catalog.syncPayload());
      synced = true;
    } catch (RuntimeException e) {
      log.warn("同步品种和时段到报价服务失败：{}，{} 秒后重试", e.getMessage(), props.syncRetry().toSeconds());
      if (syncPending.compareAndSet(false, true)) sync.schedule(this::doSync, props.syncRetry().toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  @Override
  public List<Item> evaluate() {
    List<Item> out = new ArrayList<>();
    boolean ok = stream.connected() || Duration.between(startedAt, clock.instant()).compareTo(props.idleTimeout()) < 0;
    String detail = ok ? "正常" : "连不上报价服务（已重连 " + stream.reconnects() + " 次" + (stream.lastError() == null ? "" : "，最后的错误：" + stream.lastError())
        + "），所有品种按暂无报价拒新开仓";
    out.add(new Item("quote.stream", "连不上报价服务", "Quote service unreachable", ok, detail));
    for (Message.Alert a : remoteAlerts) out.add(new Item(a.name(), a.titleZh(), a.titleEn(), a.ok(), a.detail()));
    return out;
  }

  private final class Listener implements QuoteStreamClient.Listener {
    @Override
    public void onHello(Message.Hello hello, boolean epochChanged) {
      processor.connected(hello);
      if (epochChanged) log.info("报价服务（模式 {}）已连接，新的 epoch，全部品种从快照开始", hello.mode());
      requestSync();
    }

    @Override
    public void onQuote(QuoteFrame frame) {
      processor.submit(frame);
    }

    @Override
    public void onSample(Message.Sample sample) {
      processor.acceptSample(sample);
    }

    @Override
    public void onHeartbeat(Message.Heartbeat heartbeat) {
      remoteAlerts = heartbeat.alerts();
    }

    @Override
    public void onConnection(boolean connected) {
      if (!connected) {
        remoteAlerts = List.of();
        log.warn("和报价服务的连接断开，重连中；期间所有品种 3 秒后按暂无报价拒新开仓");
      }
    }
  }

  @PreDestroy
  void stop() {
    stream.close();
    sync.shutdownNow();
  }
}
