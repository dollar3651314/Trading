package app.quote.stream;

import static app.quote.stream.StreamTestClient.await;
import static org.assertj.core.api.Assertions.assertThat;

import app.quote.pipeline.QuoteProcessor;
import app.quote.pipeline.RawQuote;
import app.quote.protocol.QuoteFrame;
import app.quote.support.FixedClockConfig;
import app.quote.support.QuoteAppTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** 断线太久、缓存里已经没有要补的那几条（每个品种只留 replay-buffer 条）：重连后发快照，客户端以快照为新起点，之后照常连续。 */
@QuoteAppTest
@Import(FixedClockConfig.class)
@TestPropertySource(properties = "quote.stream.replay-buffer=4")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReplayBufferTest {

  @LocalServerPort int port;
  @Autowired QuoteProcessor processor;
  @Autowired QuoteStream stream;

  void feed(int n, int from) {
    for (int i = 0; i < n; i++) {
      BigDecimal bid = new BigDecimal("1.25000").add(BigDecimal.valueOf(from + i, 5));
      processor.process(new RawQuote("EURGBP", bid, bid.add(new BigDecimal("0.00010")), FixedClockConfig.NOW));
    }
  }

  @Test
  void 缓存不够补齐时发快照_之后照常按序号() throws Exception {
    feed(1, 0);
    try (StreamTestClient c = new StreamTestClient(port, "ci_only_fake_internal_key")) {
      await("快照", () -> !c.of("EURGBP").isEmpty());
      long base = c.of("EURGBP").getFirst().seq();
      stream.dropAll();
      await("断开了", () -> stream.connections() == 0);
      feed(10, 1); // 断线期间 10 条，缓存只留 4 条
      await("重连后收到快照", () -> c.of("EURGBP").size() >= 2);
      QuoteFrame snap = c.of("EURGBP").get(1);
      assertThat(snap.snapshot()).isTrue();
      assertThat(snap.seq()).isEqualTo(base + 10);
      assertThat(snap.bid()).isEqualByComparingTo("1.25010");
      feed(1, 11);
      await("之后的实时报价", () -> c.of("EURGBP").size() == 3);
      assertThat(c.seqs("EURGBP")).containsExactly(base, base + 10, base + 11);
    }
  }
}
