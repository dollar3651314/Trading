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
    // 验收反馈 1a：解冻（安全事件）以后机器人主动发一条「重新授权」（冻结那一下不发：冻结的只回"已冻结"）；先等它到，再看客户自己发消息时的回应
    FakeTelegram.Call prompt = replyContaining(id, "网页已全部退出");
    assertThat(text(prompt)).contains("账户刚解冻").contains("网页已全部退出");
    assertThat(buttons(prompt)).contains("重新授权").contains("\"callback_data\":\"" + UpdateHandler.CB_REAUTH + "\"");
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

  @Autowired app.trading.account.auth.AuthAccess authAccess;

  /** 等发给这个聊天、文字里含 part 的一条回复 */
  FakeTelegram.Call replyContaining(long chat, String part) throws InterruptedException {
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < end) {
      for (FakeTelegram.Call c : replies(chat, 0)) if (text(c).contains(part)) return c;
      Thread.sleep(20);
    }
    throw new AssertionError("没等到含「" + part + "」的回复：" + replies(chat, 0).stream().map(TelegramBotFlowTest::text).toList());
  }

  @Test
  void 设提现密码这类安全事件后_机器人主动发重新授权_高优先级_点了能用() throws Exception {
    long id = newId();
    TgAccount acc = signup(id, null);
    ((MutableClock) clock).advance(Duration.ofSeconds(1));
    tg.clear();
    authAccess.securityEvent(acc.userId(), app.trading.account.api.SecurityEvent.PIN_SET);
    FakeTelegram.Call p = replyContaining(id, "网页已全部退出");
    assertThat(text(p)).contains("提现密码有变化").contains("「重新授权」").contains("「打开交易」");
    assertThat(buttons(p)).contains("\"callback_data\":\"" + UpdateHandler.CB_REAUTH + "\"");
    ((MutableClock) clock).advance(Duration.ofSeconds(1));
    tg.push(tg.callback(id, null, id, "private", UpdateHandler.CB_REAUTH));
    assertThat(text(replyContaining(id, "已重新授权"))).isEqualTo("已重新授权。");
    assertThat(account.lastSecurityEventSince(acc.userId(), accounts.byUserId(acc.userId()).orElseThrow().authorizedAt())).isEmpty();
  }

  @Test
  void 提示页的深链接start_reauth_要重新授权时回提示_不用时回主菜单_主菜单有退出所有网页会话() throws Exception {
    long id = newId();
    TgAccount acc = signup(id, null);
    tg.clear();
    tg.push(tg.text(id, null, "/start reauth"));
    FakeTelegram.Call menu = replies(id, 1).getFirst();
    assertThat(text(menu)).startsWith("客户号 #" + acc.userId());
    // 验收反馈 B：主菜单常驻「退出所有网页会话」
    assertThat(buttons(menu)).contains("退出所有网页会话").contains("\"callback_data\":\"" + app.trading.notification.api.TelegramSender.ACTION_LOGOUT_ALL + "\"");

    ((MutableClock) clock).advance(Duration.ofSeconds(1));
    authAccess.securityEvent(acc.userId(), app.trading.account.api.SecurityEvent.KYC_FIRST);
    replyContaining(id, "网页已全部退出");
    tg.clear();
    tg.push(tg.text(id, null, "/start reauth"));
    FakeTelegram.Call again = replies(id, 1).getFirst();
    assertThat(text(again)).contains("安全变更").contains("提交了身份认证");
    assertThat(buttons(again)).contains("重新授权");
  }

  @Test
  void 主菜单的退出所有网页会话_作废全部网页会话() throws Exception {
    long id = newId();
    TgAccount acc = signup(id, null);
    jdbc.update("INSERT INTO account_session (user_id, token_hash, ip, user_agent, created_at, last_seen_at, expires_at) VALUES (?, ?, '203.0.113.9', 't', NOW(3), NOW(3), NOW(3) + INTERVAL 1 DAY)",
        acc.userId(), String.format("%064x", ThreadLocalRandom.current().nextLong(Long.MAX_VALUE)));
    tg.clear();
    tg.push(tg.callback(id, null, id, "private", app.trading.notification.api.TelegramSender.ACTION_LOGOUT_ALL));
    assertThat(text(replyContaining(id, "已退出所有网页会话"))).isEqualTo(UpdateHandler.TEXT_LOGGED_OUT);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_session WHERE user_id = ? AND revoked_at IS NULL", Integer.class, acc.userId())).isZero();
  }

  long prompts(long chat) throws InterruptedException {
    return replies(chat, 0).stream().filter(c -> text(c).contains("网页已全部退出")).count();
  }

  @Test
  void 重新授权提示去重_同一个客户1分钟内只发一条_过了1分钟再有安全事件再发() throws Exception {
    long id = newId();
    TgAccount acc = signup(id, null);
    ((MutableClock) clock).advance(Duration.ofSeconds(1));
    tg.clear();
    authAccess.securityEvent(acc.userId(), app.trading.account.api.SecurityEvent.PIN_SET);
    authAccess.securityEvent(acc.userId(), app.trading.account.api.SecurityEvent.PIN_CHANGE);
    replyContaining(id, "网页已全部退出");
    // 标记：客户自己发一条，机器人回"最近有安全变更"（这条不是主动提示）；它到了，前面排队的主动提示也都到了
    tg.push(tg.text(id, null, "/start"));
    replyContaining(id, "最近有安全变更");
    assertThat(prompts(id)).as("1 分钟内只发一条").isEqualTo(1);

    ((MutableClock) clock).advance(Duration.ofSeconds(61));
    authAccess.securityEvent(acc.userId(), app.trading.account.api.SecurityEvent.KYC_FIRST);
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (prompts(id) < 2 && System.nanoTime() < end) Thread.sleep(20);
    assertThat(prompts(id)).as("过了 1 分钟再有安全事件，再发一条").isEqualTo(2);
  }

  @Test
  void 冻结的账户_有安全事件也不发重新授权提示() throws Exception {
    long id = newId();
    TgAccount acc = signup(id, null);
    ((MutableClock) clock).advance(Duration.ofSeconds(1));
    account.freeze(acc.userId());
    try {
      tg.clear();
      authAccess.securityEvent(acc.userId(), app.trading.account.api.SecurityEvent.PIN_SET);
      tg.push(tg.text(id, null, "/start"));
      assertThat(text(replyContaining(id, "已冻结"))).isEqualTo(UpdateHandler.TEXT_FROZEN);
      assertThat(prompts(id)).as("冻结的不发「重新授权」").isZero();
    } finally {
      account.unfreeze(acc.userId());
    }
  }

  @Test
  void 主菜单的当前网页会话_列出设备IP前三段登录时间和方式_旁边是退出所有网页会话() throws Exception {
    long id = newId();
    TgAccount acc = signup(id, null);
    String hash1 = String.format("%064x", ThreadLocalRandom.current().nextLong(Long.MAX_VALUE));
    String hash2 = String.format("%064x", ThreadLocalRandom.current().nextLong(Long.MAX_VALUE));
    String hash3 = String.format("%064x", ThreadLocalRandom.current().nextLong(Long.MAX_VALUE));
    jdbc.update("INSERT INTO account_session (user_id, token_hash, kind, ip, user_agent, created_at, last_seen_at, expires_at) VALUES (?, ?, 'BOT', '203.0.113.45', "
        + "'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Telegram-iOS/11.0', '2026-11-28 02:05:00', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) + INTERVAL 1 HOUR)", acc.userId(), hash1);
    jdbc.update("INSERT INTO account_session (user_id, token_hash, kind, ip, user_agent, created_at, last_seen_at, expires_at) VALUES (?, ?, 'WEB', '198.51.100.7', "
        + "'Mozilla/5.0 (Windows NT 10.0) Chrome/130.0', '2026-11-28 03:10:00', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) + INTERVAL 1 HOUR)", acc.userId(), hash2);
    jdbc.update("INSERT INTO account_session (user_id, token_hash, kind, ip, user_agent, created_at, last_seen_at, expires_at, revoked_at) VALUES (?, ?, 'WEB', '192.0.2.9', "
        + "'x', '2026-11-28 04:00:00', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) + INTERVAL 1 HOUR, UTC_TIMESTAMP(3))", acc.userId(), hash3);
    tg.clear();
    tg.push(tg.text(id, null, "/start"));
    FakeTelegram.Call menu = replies(id, 1).getFirst();
    assertThat(buttons(menu)).contains("当前网页会话").contains("\"callback_data\":\"" + UpdateHandler.CB_SESSIONS + "\"").contains("退出所有网页会话");
    tg.push(tg.callback(id, null, id, "private", UpdateHandler.CB_SESSIONS));
    FakeTelegram.Call list = replyContaining(id, "当前网页会话（");
    assertThat(text(list)).contains("2 个").contains("iPhone · Telegram 内置浏览器，IP 203.0.113.*，11-28 02:05 UTC 登录（从机器人进入）")
        .contains("Windows · Chrome，IP 198.51.100.*，11-28 03:10 UTC 登录（网页登录）").doesNotContain("192.0.2").doesNotContain("203.0.113.45");
    assertThat(text(list).indexOf("Windows")).as("新的在前").isLessThan(text(list).indexOf("iPhone"));
    assertThat(buttons(list)).contains("\"callback_data\":\"" + app.trading.notification.api.TelegramSender.ACTION_LOGOUT_ALL + "\"");
    tg.push(tg.callback(id, null, id, "private", app.trading.notification.api.TelegramSender.ACTION_LOGOUT_ALL));
    replyContaining(id, "已退出所有网页会话");
    tg.push(tg.callback(id, null, id, "private", UpdateHandler.CB_SESSIONS));
    replyContaining(id, "现在没有登录着的网页会话");
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
  void 每个TG用户每分钟最多处理20条_超过的不回应() throws Exception {
    long id = newId();
    for (int i = 0; i < 25; i++) tg.push(tg.text(id, null, "/start"));
    replies(id, 20);
    Thread.sleep(1000);
    assertThat(replies(id, 0)).as("需求文档 03 页：每个 TG 账号每分钟最多 20 次").hasSize(20);
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

  // ---------- 第 4 批 4b：找回原账户 ----------

  /** 机器人开户的客户，补上资料和已通过的 KYC（姓名 张三、护照后 4 位 8765） */
  long kycCustomer() throws Exception {
    long tgId = newId();
    long uid = signup(tgId, "orig").userId();
    jdbc.update("INSERT INTO account_profile (user_id, first_name, middle_name, last_name, birth_date, nationality) VALUES (?, '三', '', '张', '1990-01-01', 'CN')", uid);
    jdbc.update("INSERT INTO account_kyc_application (user_id, doc_type, doc_number, front_key, back_key, selfie_key, status) VALUES (?, 'PASSPORT', 'E12348765', 'f', 'b', 's', 'APPROVED')",
        uid);
    return uid;
  }

  @Test
  void 新TG找回原账户_格式不对提示_信息对了建申请_回同一句_原TG点不是我() throws Exception {
    long uid = kycCustomer();
    long newTg = newId();
    tg.push(tg.text(newTg, "newme", "/start"));
    assertThat(buttons(replies(newTg, 1).getFirst())).contains(UpdateHandler.CB_RECOVER);
    tg.push(tg.callback(newTg, "newme", newTg, "private", UpdateHandler.CB_RECOVER));
    assertThat(text(replies(newTg, 2).get(1))).isEqualTo(UpdateHandler.TEXT_RECOVER_ASK);
    tg.push(tg.text(newTg, "newme", "随便写写"));
    assertThat(text(replies(newTg, 3).get(2))).isEqualTo(UpdateHandler.TEXT_RECOVER_FORMAT);
    tg.push(tg.text(newTg, "newme", uid + " 张三 8765"));
    assertThat(text(replies(newTg, 4).get(3))).isEqualTo(UpdateHandler.TEXT_RECOVER_APPLIED);
    Long id = jdbc.queryForObject("SELECT MAX(id) FROM account_recovery WHERE user_id = ?", Long.class, uid);
    assertThat(id).isNotNull();
    assertThat(jdbc.queryForObject("SELECT applicant_tg_id FROM account_recovery WHERE id = ?", Long.class, id)).isEqualTo(newTg);

    // 信息不对：也回同一句，不建申请（/start recover 直接进入）
    long other = newId();
    tg.push(tg.text(other, null, "/start recover"));
    replies(other, 1);
    tg.push(tg.text(other, null, uid + " 李四 8765"));
    assertThat(text(replies(other, 2).get(1))).isEqualTo(UpdateHandler.TEXT_RECOVER_APPLIED);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_recovery WHERE user_id = ?", Integer.class, uid)).isEqualTo(1);

    // 原 TG 点通知里的「不是我」：转加严人工，给出输提现密码和直接冻结两个按钮
    long orig = accounts.byUserId(uid).orElseThrow().telegramId();
    int before = replies(orig, 0).size();
    tg.push(tg.callback(orig, "orig", orig, "private", app.trading.notification.api.TelegramSender.ACTION_RECOVERY_OBJECT + id));
    FakeTelegram.Call r = replies(orig, before + 1).get(before);
    assertThat(text(r)).startsWith("已记下");
    assertThat(buttons(r)).contains(app.trading.notification.api.TelegramSender.ACTION_RECOVERY_FREEZE + id);
    assertThat(jdbc.queryForObject("SELECT disputed FROM account_recovery WHERE id = ?", Boolean.class, id)).isTrue();
    tg.push(tg.callback(orig, "orig", orig, "private", app.trading.notification.api.TelegramSender.ACTION_RECOVERY_FREEZE + id));
    assertThat(text(replies(orig, before + 2).get(before + 1))).startsWith("已冻结提现");
    // 冻结表一个来源一行（4b 审查 🔴R2）：这里只该有争议这一条
    assertThat(jdbc.queryForList("SELECT source FROM account_withdraw_freeze WHERE user_id = ?", String.class, uid)).containsExactly("DISPUTE");
    assertThat(jdbc.queryForList("SELECT reason FROM account_withdraw_freeze WHERE user_id = ? AND source = 'DISPUTE'", String.class, uid))
        .singleElement().asString().contains("找回申请 #" + id).contains("客户选了直接冻结");
  }

  /** 第 4 批 4c：机器人里自己冻结提现（BOT_SELF），先确认；只会减少权限 */
  @Test
  void 机器人里自己冻结提现_先确认() throws Exception {
    long id = newId();
    long uid = signup(id, "selffz").userId();
    tg.push(tg.text(id, "selffz", "/start"));
    assertThat(buttons(replies(id, 4).get(3))).contains(UpdateHandler.CB_FREEZE);
    tg.push(tg.callback(id, "selffz", id, "private", UpdateHandler.CB_FREEZE));
    FakeTelegram.Call ask = replies(id, 5).get(4);
    assertThat(text(ask)).isEqualTo(UpdateHandler.TEXT_FREEZE_ASK);
    assertThat(buttons(ask)).contains(UpdateHandler.CB_FREEZE_YES);
    assertThat(jdbc.queryForList("SELECT source FROM account_withdraw_freeze WHERE user_id = ?", String.class, uid)).as("确认之前不冻结").isEmpty();
    tg.push(tg.callback(id, "selffz", id, "private", UpdateHandler.CB_FREEZE_YES));
    assertThat(text(replies(id, 6).get(5))).isEqualTo(UpdateHandler.TEXT_FREEZE_DONE);
    assertThat(jdbc.queryForList("SELECT source FROM account_withdraw_freeze WHERE user_id = ?", String.class, uid)).containsExactly("BOT_SELF");
    tg.push(tg.text(id, "selffz", "/start"));
    assertThat(buttons(replies(id, 7).get(6))).as("冻结着不再显示冻结按钮").doesNotContain("\"" + UpdateHandler.CB_FREEZE + "\"");
  }
}
