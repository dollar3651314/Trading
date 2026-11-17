package app.quote.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 协议消息编解码：价格原样保留小数位，内部字段（报价源名字）去掉后不出现在文本里。 */
class QuoteCodecTest {

  @Test
  void 报价来回不变_价格是字符串保留末尾的0() {
    QuoteFrame f = new QuoteFrame("EURUSD", 42, new BigDecimal("1.08000"), new BigDecimal("1.08010"), 1_760_436_000_000L, "FRESH", "primary", "alltick",
        true, false);
    String text = QuoteCodec.encode(f);
    assertThat(text).contains("\"bid\":\"1.08000\"").contains("\"seq\":42").doesNotContain("snapshot");
    assertThat(QuoteCodec.decode(text)).isEqualTo(f);
    QuoteFrame snap = f.asSnapshot();
    assertThat(QuoteCodec.decode(QuoteCodec.encode(snap))).isEqualTo(snap);
  }

  @Test
  void 去掉报价源名字后文本里没有feed() {
    QuoteFrame f = new QuoteFrame("XAUUSD", 1, new BigDecimal("4126.50"), new BigDecimal("4126.90"), 1L, "FRESH", "backup", "itick", false, false);
    assertThat(QuoteCodec.encode(f.withoutFeed())).doesNotContain("feed").doesNotContain("itick");
  }

  @Test
  void 其他消息来回不变() {
    List<Message> all = List.of(new Message.Hello("e1", "simulated", 5L, List.of(new Message.Source("EURUSD", "alltick", "primary"), new Message.Source("AAPL", "simulated", null))), new Message.Sample("XAUUSD", new BigDecimal("4197.70"), new BigDecimal("4198.10"), 7L),
        new Message.Heartbeat(9L, List.of(new Message.Alert("quote.alltick", "报价源 alltick 异常", "Quote feed alltick problem", false, "没连上"))),
        new Message.Error("BAD_MESSAGE", "格式不对"), new Message.Subscribe("e1", Map.of("EURUSD", 3L)), new Message.Resync(Map.of("EURUSD", -1L)));
    for (Message m : all) assertThat(QuoteCodec.decode(QuoteCodec.encode(m))).isEqualTo(m);
    assertThat(QuoteCodec.decode("{\"op\":\"subscribe\"}")).isEqualTo(new Message.Subscribe(null, Map.of()));
  }

  @Test
  void 格式不对抛IllegalArgumentException() {
    for (String bad : new String[] {"not json", "[1]", "{\"type\":\"nope\"}", "{\"op\":\"nope\"}", "{\"type\":\"quote\",\"symbol\":\"X\",\"bid\":\"abc\"}",
        "{\"type\":\"quote\",\"bid\":\"1\",\"ask\":\"2\",\"status\":\"FRESH\"}"}) {
      assertThatThrownBy(() -> QuoteCodec.decode(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
    }
  }
}
