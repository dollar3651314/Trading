package app.trading.account.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import app.trading.account.api.TelegramAccountApi;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** TG 客户第一次提交 KYC 是安全事件（TG 机器人第 4 批，03 页场景 H）：24 小时不能提现；网页客户不变；第二次提交（驳回后重交）不算。 */
@SpringBootTest
@ActiveProfiles("test")
class KycFirstSubmitTest {

  @Autowired KycService kyc;
  @Autowired TelegramAccountApi telegram;
  @Autowired JdbcTemplate jdbc;

  static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};

  List<KycService.Image> images() {
    return List.of(new KycService.Image("front", JPG), new KycService.Image("back", JPG), new KycService.Image("selfie", JPG));
  }

  List<String> events(long uid) {
    return jdbc.queryForList("SELECT type FROM account_security_event WHERE user_id = ? ORDER BY id", String.class, uid);
  }

  @Test
  void TG客户第一次提交是安全事件_驳回后再交不算() {
    long uid = telegram.openAccount(3_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L), null).userId();
    assertThat(kyc.submit(uid, "PASSPORT", "K" + uid, images())).as("返回 true：接口带 relogin，页面提示回机器人").isTrue();
    assertThat(events(uid)).containsExactly("KYC_FIRST");
    jdbc.update("UPDATE account_kyc_application SET status = 'REJECTED' WHERE user_id = ?", uid);
    assertThat(kyc.submit(uid, "PASSPORT", "K" + uid, images())).isFalse();
    assertThat(events(uid)).containsExactly("KYC_FIRST");
  }

  @Test
  void 网页客户提交不算() {
    long uid = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 + FLOOR(RAND() * 1000) FROM account_user", Long.class);
    jdbc.update("INSERT INTO account_user (id, email, status) VALUES (?, ?, 'ACTIVE')", uid, "k" + uid + "@example.com");
    assertThat(kyc.submit(uid, "PASSPORT", "W" + uid, images())).isFalse();
    assertThat(events(uid)).isEmpty();
  }
}
