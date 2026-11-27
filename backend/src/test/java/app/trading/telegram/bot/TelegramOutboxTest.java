package app.trading.telegram.bot;

import static org.assertj.core.api.Assertions.assertThat;

import app.trading.notification.api.TelegramSender.Priority;
import java.io.Serializable;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.ApiResponse;
import org.telegram.telegrambots.meta.api.objects.ResponseParameters;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

/** 发送队列：三档优先级、每个聊天的间隔、429 / 403 / 网络错误、队列上限、失败原因里没有令牌。不启动 Spring，不连网络。 */
class TelegramOutboxTest {

  static final String TOKEN = "123456:OUTBOX-test-token-not-real";

  record Sent(long chat, String text, long at) {}

  final List<Sent> sent = new CopyOnWriteArrayList<>();
  TelegramOutbox outbox;

  static TelegramProperties props(Duration perChat, int capacity) {
    return new TelegramProperties(TOKEN, "TradingAssistTestBot", "http://127.0.0.1:1", null, Duration.ofSeconds(1), perChat, 1000, capacity,
        Duration.ofMinutes(10), 20, "login-url");
  }

  /** 正常发送：记下聊天、文字、时间 */
  TelegramOutbox.Api ok() {
    return new TelegramOutbox.Api() {
      @Override
      public <T extends Serializable> T execute(BotApiMethod<T> method) {
        SendMessage m = (SendMessage) method;
        sent.add(new Sent(Long.parseLong(m.getChatId()), m.getText(), System.nanoTime()));
        return null;
      }
    };
  }

  static SendMessage msg(long chat, String text) {
    return SendMessage.builder().chatId(chat).text(text).build();
  }

  static TelegramApiRequestException apiError(int code, Integer retryAfter) {
    ApiResponse<Object> r = ApiResponse.<Object>builder().ok(false).errorCode(code).errorDescription("fake " + code + " " + TOKEN)
        .parameters(retryAfter == null ? null : new ResponseParameters(null, retryAfter)).build();
    return new TelegramApiRequestException("Error", r);
  }

  void awaitSent(int n) throws InterruptedException {
    long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (sent.size() < n && System.nanoTime() < end) Thread.sleep(10);
  }

  @AfterEach
  void stop() {
    if (outbox != null) outbox.stop();
  }

  @Test
  void 高优先级先发_同一档先进先出() throws Exception {
    outbox = new TelegramOutbox(ok(), props(Duration.ZERO, 100));
    outbox.offer(1, Priority.LOW, msg(1, "低1"), null);
    outbox.offer(2, Priority.NORMAL, msg(2, "中1"), null);
    outbox.offer(3, Priority.HIGH, msg(3, "高1"), null);
    outbox.offer(4, Priority.LOW, msg(4, "低2"), null);
    outbox.offer(5, Priority.HIGH, msg(5, "高2"), null);
    outbox.start();
    awaitSent(5);
    assertThat(sent).extracting(Sent::text).containsExactly("高1", "高2", "中1", "低1", "低2");
  }

  @Test
  void 同一个聊天两条之间至少隔开设定的间隔_别的聊天不用等() throws Exception {
    outbox = new TelegramOutbox(ok(), props(Duration.ofMillis(400), 100));
    outbox.offer(1, Priority.HIGH, msg(1, "a1"), null);
    outbox.offer(1, Priority.HIGH, msg(1, "a2"), null);
    outbox.offer(2, Priority.LOW, msg(2, "b1"), null);
    outbox.start();
    awaitSent(3);
    assertThat(sent).extracting(Sent::text).as("a2 要等，先发别的聊天的 b1（即使优先级低）").containsExactly("a1", "b1", "a2");
    long gap = sent.get(2).at() - sent.get(0).at();
    assertThat(Duration.ofNanos(gap)).isGreaterThanOrEqualTo(Duration.ofMillis(390));
  }

  @Test
  void 限流429按retry_after等待后重发_403不重发并回调原因_原因里没有令牌() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    List<String> results = new CopyOnWriteArrayList<>();
    outbox = new TelegramOutbox(new TelegramOutbox.Api() {
      @Override
      public <T extends Serializable> T execute(BotApiMethod<T> method) throws TelegramApiException {
        SendMessage m = (SendMessage) method;
        int n = calls.incrementAndGet();
        if (m.getText().equals("限流") && n == 1) throw apiError(429, 1);
        if (m.getText().equals("屏蔽")) throw apiError(403, null);
        sent.add(new Sent(Long.parseLong(m.getChatId()), m.getText(), System.nanoTime()));
        return null;
      }
    }, props(Duration.ZERO, 100));
    long t0 = System.nanoTime();
    outbox.offer(1, Priority.HIGH, msg(1, "限流"), e -> results.add("限流:" + e));
    outbox.offer(2, Priority.HIGH, msg(2, "屏蔽"), e -> results.add("屏蔽:" + e));
    outbox.start();
    awaitSent(1);
    long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (results.size() < 2 && System.nanoTime() < end) Thread.sleep(10);
    assertThat(sent).extracting(Sent::text).containsExactly("限流");
    assertThat(Duration.ofNanos(sent.getFirst().at() - t0)).as("429 后至少等 retry_after = 1 秒").isGreaterThanOrEqualTo(Duration.ofMillis(990));
    assertThat(results).contains("限流:null");
    assertThat(results).anyMatch(r -> r.startsWith("屏蔽:Telegram 拒绝（403）"));
    assertThat(String.join("\n", results)).doesNotContain(TOKEN);
    assertThat(calls.get()).as("403 只试一次").isEqualTo(3);
  }

  @Test
  void 网络错误最多试3次() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    List<String> results = new CopyOnWriteArrayList<>();
    outbox = new TelegramOutbox(new TelegramOutbox.Api() {
      @Override
      public <T extends Serializable> T execute(BotApiMethod<T> method) throws TelegramApiException {
        calls.incrementAndGet();
        throw new TelegramApiException("connect failed https://api.telegram.org/bot" + TOKEN + "/sendMessage");
      }
    }, props(Duration.ZERO, 100), new java.util.function.LongSupplier() {
      // 时间走得快：重试间隔 2 秒不用真等
      long t;

      @Override
      public long getAsLong() {
        return t += 1_000_000_000L;
      }
    });
    outbox.offer(1, Priority.NORMAL, msg(1, "x"), results::add);
    outbox.start();
    long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (results.isEmpty() && System.nanoTime() < end) Thread.sleep(10);
    assertThat(calls.get()).isEqualTo(TelegramOutbox.MAX_ATTEMPTS);
    assertThat(results).singleElement().asString().startsWith("连不上 Telegram").doesNotContain(TOKEN).contains("***");
  }

  @Test
  void 队列满了先挤掉低档的_同档或更低的拒收() {
    outbox = new TelegramOutbox(ok(), props(Duration.ZERO, 2));
    List<String> results = new CopyOnWriteArrayList<>();
    assertThat(outbox.offer(1, Priority.LOW, msg(1, "低1"), e -> results.add("低1:" + e))).isTrue();
    assertThat(outbox.offer(1, Priority.NORMAL, msg(1, "中1"), e -> results.add("中1:" + e))).isTrue();
    assertThat(outbox.offer(1, Priority.LOW, msg(1, "低2"), e -> results.add("低2:" + e))).as("满了，低档不挤低档").isFalse();
    assertThat(outbox.offer(1, Priority.NORMAL, msg(1, "中2"), e -> results.add("中2:" + e))).as("满了，挤掉低1").isTrue();
    assertThat(outbox.offer(1, Priority.NORMAL, msg(1, "中3"), e -> results.add("中3:" + e))).as("满了、没有更低档的：中档拒收").isFalse();
    assertThat(results).containsExactly("低2:" + TelegramOutbox.FULL, "低1:" + TelegramOutbox.EVICTED, "中3:" + TelegramOutbox.FULL);
    assertThat(outbox.size()).isEqualTo(2);
  }

  @Test
  void 高档消息不丢_满了先挤低档_全是高档也照收() throws Exception {
    outbox = new TelegramOutbox(ok(), props(Duration.ZERO, 2));
    List<String> results = new CopyOnWriteArrayList<>();
    outbox.offer(1, Priority.LOW, msg(1, "低"), e -> results.add("低:" + e));
    outbox.offer(2, Priority.NORMAL, msg(2, "中"), e -> results.add("中:" + e));
    assertThat(outbox.offer(3, Priority.HIGH, msg(3, "高1"), null)).isTrue();
    assertThat(outbox.offer(4, Priority.HIGH, msg(4, "高2"), null)).isTrue();
    assertThat(results).as("先挤最低档，再挤中档").containsExactly("低:" + TelegramOutbox.EVICTED, "中:" + TelegramOutbox.EVICTED);
    for (int i = 0; i < 5; i++) assertThat(outbox.offer(10 + i, Priority.HIGH, msg(10 + i, "强平" + i), null)).as("全是高档也收").isTrue();
    assertThat(outbox.size()).isEqualTo(7);
    outbox.start();
    awaitSent(7);
    assertThat(sent).extracting(Sent::text).containsExactly("高1", "高2", "强平0", "强平1", "强平2", "强平3", "强平4");
  }
}
