package app.quote.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 发送超时（审查 🟡：连接半死时发送一直不完成，不能一直占着锁、卡住重连）：用一个"发出去永远不完成"的假连接，
 * 发送在 sendTimeout 后返回，断开这条连接（abort），并照常进入重连；发送卡着的时候，别的线程触发重连也不会被挡住。
 * 不连网络（重连目标是一个刚关掉的本地端口，连不上就继续按退避重连）。
 */
class QuoteStreamClientSendTimeoutTest {

  /** 发出去的消息永远不完成的连接 */
  static final class StuckSocket implements WebSocket {
    volatile boolean aborted;
    final AtomicInteger sends = new AtomicInteger();

    @Override
    public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
      sends.incrementAndGet();
      return new CompletableFuture<>();
    }

    @Override
    public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
      return new CompletableFuture<>();
    }

    @Override
    public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
      return new CompletableFuture<>();
    }

    @Override
    public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
      return new CompletableFuture<>();
    }

    @Override
    public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
      return new CompletableFuture<>();
    }

    @Override
    public void request(long n) {}

    @Override
    public String getSubprotocol() {
      return "";
    }

    @Override
    public boolean isOutputClosed() {
      return aborted;
    }

    @Override
    public boolean isInputClosed() {
      return aborted;
    }

    @Override
    public void abort() {
      aborted = true;
    }
  }

  static int closedPort() throws Exception {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    }
  }

  QuoteStreamClient client(Duration sendTimeout) throws Exception {
    QuoteStreamClient.Options o = new QuoteStreamClient.Options(Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofSeconds(60), Duration.ofMillis(500),
        sendTimeout);
    return new QuoteStreamClient(URI.create("ws://127.0.0.1:" + closedPort() + "/internal/ws/quotes"), "ci_only_fake_key", f -> {}, o, Clock.systemUTC());
  }

  @Test
  void 发送一直不完成_超时后断开并照常重连() throws Exception {
    try (QuoteStreamClient c = client(Duration.ofMillis(300))) {
      StuckSocket stuck = new StuckSocket();
      c.useConnectionForTest(stuck);
      long t0 = System.nanoTime();
      c.send(stuck, new Message.Resync(Map.of("EURUSD", 1L)));
      long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
      assertThat(ms).as("发送最多等 sendTimeout，不会一直卡着").isBetween(250L, 2000L);
      assertThat(stuck.aborted).as("半死的连接被断开").isTrue();
      assertThat(c.lastError()).isEqualTo("send-timeout");
      assertThat(c.connected()).isFalse();
      // 重连照常发生：目标端口连不上，按退避一次次重连
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (c.reconnects() < 3 && System.nanoTime() < end) Thread.sleep(20);
      assertThat(c.reconnects()).as("超时后进入重连，之后照常按退避重连").isGreaterThanOrEqualTo(3);
    }
  }

  @Test
  void 发送卡着的时候_别的线程触发重连不被挡住() throws Exception {
    try (QuoteStreamClient c = client(Duration.ofSeconds(3))) {
      StuckSocket stuck = new StuckSocket();
      c.useConnectionForTest(stuck);
      CountDownLatch sending = new CountDownLatch(1);
      Thread sender = new Thread(() -> {
        sending.countDown();
        c.send(stuck, new Message.WatchBars(java.util.Set.of("XAUUSD|1m")));
      });
      sender.start();
      sending.await();
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (stuck.sends.get() == 0 && System.nanoTime() < end) Thread.sleep(5);
      assertThat(stuck.sends.get()).as("发送已经卡在那里").isEqualTo(1);
      long t0 = System.nanoTime();
      c.dropConnection(); // 走 scheduleReconnect（对象锁），不能被发送锁挡住
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).as("重连没有等发送超时").isLessThan(1000L);
      assertThat(c.reconnects()).isGreaterThanOrEqualTo(1);
      sender.join(5000);
      assertThat(sender.isAlive()).as("发送在超时后返回").isFalse();
    }
  }
}
