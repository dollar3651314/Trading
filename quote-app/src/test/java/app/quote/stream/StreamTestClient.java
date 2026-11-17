package app.quote.stream;

import app.quote.protocol.Message;
import app.quote.protocol.QuoteFrame;
import app.quote.protocol.QuoteStreamClient;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/** 测试用：用正式的 {@link QuoteStreamClient} 连报价服务，记下交出来的每一条（已经过序号检查）。 */
final class StreamTestClient implements AutoCloseable {

  final List<QuoteFrame> frames = new CopyOnWriteArrayList<>();
  final List<Message.Hello> hellos = new CopyOnWriteArrayList<>();
  final List<Message.Heartbeat> heartbeats = new CopyOnWriteArrayList<>();
  final List<Message.Sample> samples = new CopyOnWriteArrayList<>();
  final QuoteStreamClient client;

  StreamTestClient(int port, String key) {
    client = new QuoteStreamClient(URI.create("ws://127.0.0.1:" + port + "/internal/ws/quotes"), key, new QuoteStreamClient.Listener() {
      @Override
      public void onHello(Message.Hello hello, boolean epochChanged) {
        hellos.add(hello);
      }

      @Override
      public void onQuote(QuoteFrame frame) {
        frames.add(frame);
      }

      @Override
      public void onSample(Message.Sample sample) {
        samples.add(sample);
      }

      @Override
      public void onHeartbeat(Message.Heartbeat heartbeat) {
        heartbeats.add(heartbeat);
      }
    }, new QuoteStreamClient.Options(Duration.ofMillis(50), Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofSeconds(2)), Clock.systemUTC());
    client.start();
  }

  /** 这个品种交出来的序号（按收到的顺序） */
  List<Long> seqs(String symbol) {
    return frames.stream().filter(f -> f.symbol().equals(symbol)).map(QuoteFrame::seq).toList();
  }

  List<QuoteFrame> of(String symbol) {
    return frames.stream().filter(f -> f.symbol().equals(symbol)).toList();
  }

  static void await(String what, BooleanSupplier ok) throws InterruptedException {
    long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < end) {
      if (ok.getAsBoolean()) return;
      Thread.sleep(20);
    }
    throw new AssertionError("等了 10 秒还没有：" + what);
  }

  @Override
  public void close() {
    client.close();
  }
}
