package app.trading.telegram.bot;

import static org.assertj.core.api.Assertions.assertThat;

import app.trading.account.api.AccountApi;
import app.trading.account.api.TelegramAccountApi;
import app.trading.account.api.TelegramAccountApi.TgAccount;
import app.trading.common.config.ConfigKey;
import app.trading.common.config.SystemConfig;
import app.trading.notification.api.TelegramCategory;
import app.trading.notification.api.TelegramPrefsApi;
import app.trading.support.FixedClockConfig;
import app.trading.support.MutableClock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 机器人对话（TG 机器人第 1 批，技术方案 05 页第 5 节必测项）：整个 trading-app 起来，Telegram 换成本机的假 Telegram（{@link FakeTelegram}），
 * 真的走长轮询 → 处理 → 发送队列 → sendMessage。真实 Telegram 没测（还没有令牌），见交付说明"未验证"。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class TelegramBotFlowTest {

  static final String TOKEN = "555000:FLOW-test-token-not-real";
  static final FakeTelegram tg;

  static {
    try {
      tg = new FakeTelegram(TOKEN, "TradingAssistTestBot");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void telegram(DynamicPropertyRegistry r) {
    r.add("trading.telegram.token", () -> TOKEN);
    r.add("trading.telegram.username", () -> "TradingAssistTestBot");
    r.add("trading.telegram.api-url", tg::url);
    r.add("trading.telegram.poll-timeout", () -> "1s");
    r.add("trading.telegram.per-chat-interval", () -> "0s");
  }

  @AfterAll
  static void close() {
    tg.close();
  }

  @Autowired TelegramAccountApi accounts;
  @Autowired AccountApi account;
  @Autowired TelegramPrefsApi prefs;
  @Autowired SystemConfig config;
  @Autowired JdbcTemplate jdbc;
  @Autowired Clock clock;
  @Autowired app.trading.notification.api.TelegramSender sender;

  @BeforeEach
  void open() {
    config.setBySystem(ConfigKey.TELEGRAM_SIGNUP_ALLOW_IDS, "*", LocalDateTime.now());
    config.setBySystem(ConfigKey.TELEGRAM_ENABLED, "true", LocalDateTime.now());
  }

  static long newId() {
    return 7_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
  }

  /** 等发给这个聊天的第 n 条回复（新消息或改消息），返回全部 */
  List<FakeTelegram.Call> replies(long chat, int n) throws InterruptedException {
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (true) {
      List<FakeTelegram.Call> got = tg.calls().stream()
          .filter(c -> (c.method().equals("sendMessage") || c.method().equals("editMessageText")) && c.body().path("chat_id").asString("").equals(String.valueOf(chat)))
          .toList();
      if (got.size() >= n || System.nanoTime() > end) return got;
      Thread.sleep(20);
    }
  }

  static String text(FakeTelegram.Call c) {
    return c.body().path("text").asString("");
  }

  static String buttons(FakeTelegram.Call c) {
    return c.body().path("reply_markup").toString();
  }

  /** 开户（走机器人：/start → 授权开户） */
  TgAccount signup(long id, String uname) throws InterruptedException {
    tg.push(tg.text(id, uname, "/start"));
    tg.push(tg.callback(id, uname, id, "private", UpdateHandler.CB_SIGNUP));
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (accounts.byTelegramId(id).isEmpty() && System.nanoTime() < end) Thread.sleep(20);
    replies(id, 3);
    return accounts.byTelegramId(id).orElseThrow();
  }

  @Test
  void 群里的消息一律不回应_只认私聊() throws Exception {
    long a = newId();
    long group = -1_000_000_000_000L - ThreadLocalRandom.current().nextLong(1_000_000L);
    long marker = newId();
    tg.push(tg.groupText(a, group, "/start"));
    tg.push(tg.callback(a, null, group, "supergroup", UpdateHandler.CB_SIGNUP));
    tg.push(tg.text(marker, null, "/start"));
    assertThat(replies(marker, 1)).hasSize(1);
    Thread.sleep(300);
    assertThat(replies(group, 0)).isEmpty();
    assertThat(replies(a, 0)).as("群里点的「授权开户」也不开户、不私聊回复").isEmpty();
    assertThat(accounts.byTelegramId(a)).isEmpty();
  }

  @Test
  void 开户名单为空时谁都不能开户() throws Exception {
    config.setBySystem(ConfigKey.TELEGRAM_SIGNUP_ALLOW_IDS, "", LocalDateTime.now());
    long a = newId();
    tg.push(tg.text(a, "nobody", "/start"));
    tg.push(tg.callback(a, "nobody", a, "private", UpdateHandler.CB_SIGNUP));
    List<FakeTelegram.Call> r = replies(a, 2);
    assertThat(r).extracting(TelegramBotFlowTest::text).containsExactly(UpdateHandler.TEXT_NOT_OPEN, UpdateHandler.TEXT_NOT_OPEN);
    assertThat(accounts.byTelegramId(a)).isEmpty();
  }

  @Test
  void 开户名单只放行名单里的编号() throws Exception {
    long allowed = newId();
    long other = newId();
    config.setBySystem(ConfigKey.TELEGRAM_SIGNUP_ALLOW_IDS, "1," + allowed, LocalDateTime.now());
    tg.push(tg.text(other, null, "/start"));
    assertThat(text(replies(other, 1).getFirst())).isEqualTo(UpdateHandler.TEXT_NOT_OPEN);
    tg.push(tg.text(allowed, null, "/start"));
    FakeTelegram.Call c = replies(allowed, 1).getFirst();
    assertThat(text(c)).contains("风险提示").contains("两步验证");
    assertThat(buttons(c)).contains("授权开户").contains("不同意");
  }

  @Test
  void 授权开户只认TG用户编号_没有邮箱_重复点只开一个户() throws Exception {
    long a = newId();
    tg.push(tg.text(a, "same_name", "/start"));
    tg.push(tg.callback(a, "same_name", a, "private", UpdateHandler.CB_SIGNUP));
    tg.push(tg.callback(a, "same_name", a, "private", UpdateHandler.CB_SIGNUP));
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (accounts.byTelegramId(a).isEmpty() && System.nanoTime() < end) Thread.sleep(20);
    List<FakeTelegram.Call> r = replies(a, 4);
    TgAccount acc = accounts.byTelegramId(a).orElseThrow();
    assertThat(acc.username()).isEqualTo("same_name");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_telegram WHERE telegram_id = ?", Integer.class, a)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT email FROM account_user WHERE id = ?", String.class, acc.userId())).isNull();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_account WHERE user_id = ?", Integer.class, acc.userId())).as("交易账户也开好").isEqualTo(1);
    assertThat(r).extracting(TelegramBotFlowTest::text).anyMatch(t -> t.startsWith("开户成功，客户号 #" + acc.userId()));
    assertThat(r.stream().filter(c -> buttons(c).contains("通知设置"))).isNotEmpty();
    assertThat(r.stream().map(TelegramBotFlowTest::buttons)).as("网页地址没配置：不显示 login_url 按钮").noneMatch(b -> b.contains("login_url"));

    // 换了一个人，用户名一样（用户名可以转让）：不认，当新客户
    long b = newId();
    tg.push(tg.text(b, "same_name", "/start"));
    assertThat(text(replies(b, 1).getFirst())).contains("风险提示");
    assertThat(accounts.byTelegramId(b)).isEmpty();
  }

  @Test
  void 两个纯TG客户邮箱都为空_唯一约束允许多个空值() throws Exception {
    TgAccount a = accounts.openAccount(newId(), null);
    TgAccount b = accounts.openAccount(newId(), "x");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_user WHERE id IN (?, ?) AND email IS NULL", Integer.class, a.userId(), b.userId())).isEqualTo(2);
    assertThat(account.email(a.userId())).isEmpty();
  }

  @Test
  void 账户冻结后每个命令都被拒_不显示账户信息() throws Exception {
    long id = newId();
    TgAccount acc = signup(id, null);
    ((MutableClock) clock).advance(Duration.ofSeconds(1));
    account.freeze(acc.userId());
    tg.clear();
    for (String cmd : List.of("/start", "/settings", "随便说点什么")) tg.push(tg.text(id, null, cmd));
    for (String data : List.of(UpdateHandler.CB_PREFS, "pref:TRADE", UpdateHandler.CB_REAUTH, UpdateHandler.CB_MENU, UpdateHandler.CB_SIGNUP)) {
      tg.push(tg.callback(id, null, id, "private", data));
    }
    List<FakeTelegram.Call> r = replies(id, 8);
    assertThat(r).hasSize(8).extracting(TelegramBotFlowTest::text).containsOnly(UpdateHandler.TEXT_FROZEN);
    assertThat(r.stream().map(TelegramBotFlowTest::buttons)).allMatch(b -> b.equals("") || b.equals("null") || b.isBlank() || b.equals("{}"));
    assertThat(prefs.telegramPrefs(acc.userId())).containsEntry(TelegramCategory.TRADE, true);
    account.unfreeze(acc.userId());
  }

  @Test
  void 授权后出过安全事件要重新授权() throws Exception {
    long id = newId();
    TgAccount acc = signup(id, null);
    ((MutableClock) clock).advance(Duration.ofSeconds(1));
    account.freeze(acc.userId());
    account.unfreeze(acc.userId());
    tg.clear();
    tg.push(tg.text(id, null, "/start"));
    FakeTelegram.Call c = replies(id, 1).getFirst();
    assertThat(text(c)).contains("安全变更").contains("账户刚解冻").doesNotContain("客户号");
    assertThat(buttons(c)).contains("重新授权");
    tg.push(tg.callback(id, null, id, "private", UpdateHandler.CB_PREFS));
    assertThat(text(replies(id, 2).get(1))).as("别的按钮也先要重新授权").contains("重新授权");

    ((MutableClock) clock).advance(Duration.ofSeconds(1));
    tg.push(tg.callback(id, null, id, "private", UpdateHandler.CB_REAUTH));
    List<FakeTelegram.Call> r = replies(id, 4);
    assertThat(text(r.get(2))).isEqualTo("已重新授权。");
    assertThat(text(r.get(3))).startsWith("客户号 #" + acc.userId());
    tg.push(tg.text(id, null, "/start"));
    assertThat(text(replies(id, 5).get(4))).startsWith("客户号 #" + acc.userId());
  }

  @Test
  void 通知设置按类开关_强平和安全提醒不能关() throws Exception {
    long id = newId();
    TgAccount acc = signup(id, null);
    tg.clear();
    tg.push(tg.text(id, null, "/settings"));
    FakeTelegram.Call s = replies(id, 1).getFirst();
    assertThat(buttons(s)).contains("✅ 成交和持仓").contains("🔒 保证金预警和强平（不能关）").contains("🔒 安全提醒（不能关）");
    tg.push(tg.callback(id, null, id, "private", "pref:TRADE"));
    FakeTelegram.Call edit = replies(id, 2).get(1);
    assertThat(edit.method()).isEqualTo("editMessageText");
    assertThat(buttons(edit)).contains("⬜ 成交和持仓");
    assertThat(prefs.telegramPrefs(acc.userId())).containsEntry(TelegramCategory.TRADE, false).containsEntry(TelegramCategory.FUNDS, true);
    tg.push(tg.callback(id, null, id, "private", "pref:RISK"));
    assertThat(text(replies(id, 3).get(2))).contains("不能关闭");
    assertThat(prefs.telegramPrefs(acc.userId())).containsEntry(TelegramCategory.RISK, true);
    // 设置只影响自己
    TgAccount other = accounts.openAccount(newId(), null);
    assertThat(prefs.telegramPrefs(other.userId())).containsEntry(TelegramCategory.TRADE, true);
  }

  @Test
  void 提醒交给发送队列_发到客户的TG私聊_发出后回调() throws Exception {
    long id = newId();
    TgAccount acc = accounts.openAccount(id, null);
    java.util.concurrent.CompletableFuture<String> done = new java.util.concurrent.CompletableFuture<>();
    var r = sender.send(acc.userId(), "保证金预警\n\n保证金水平 80%", app.trading.notification.api.TelegramSender.Priority.HIGH, "/positions",
        e -> done.complete(e == null ? "OK" : e));
    assertThat(r).isEqualTo(app.trading.notification.api.TelegramSender.Result.QUEUED);
    assertThat(done.get(15, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("OK");
    assertThat(text(replies(id, 1).getFirst())).isEqualTo("保证金预警\n\n保证金水平 80%");
    assertThat(sender.send(-1L, "x", app.trading.notification.api.TelegramSender.Priority.LOW, null, null))
        .isEqualTo(app.trading.notification.api.TelegramSender.Result.NOT_LINKED);
  }

  @Test
  void 后台关了机器人只回暂停服务() throws Exception {
    config.setBySystem(ConfigKey.TELEGRAM_ENABLED, "false", LocalDateTime.now());
    long id = newId();
    tg.push(tg.text(id, null, "/start"));
    assertThat(text(replies(id, 1).getFirst())).isEqualTo(UpdateHandler.TEXT_PAUSED);
    assertThat(accounts.byTelegramId(id)).isEmpty();
  }

  @Test
  void 发送失败重试_日志里不出现令牌(CapturedOutput out) throws Exception {
    long id = newId();
    tg.failNext("sendMessage", 500, 2);
    tg.push(tg.text(id, null, "/start"));
    assertThat(replies(id, 3)).as("前两次 500，第三次成功").hasSize(3);
    assertThat(out.getAll()).doesNotContain(TOKEN).doesNotContain(TOKEN.substring(TOKEN.indexOf(':') + 1));
  }
}
