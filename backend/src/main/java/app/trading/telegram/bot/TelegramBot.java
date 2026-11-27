package app.trading.telegram.bot;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.BotSession;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

/**
 * 机器人的启停（TG 机器人第 1 批）：没配令牌什么都不做（不建线程、不建连接）。配了令牌：
 * <ol>
 *   <li>getMe 核对令牌属于配置的用户名（{@code FX_TG_BOT_USERNAME}），不一致就不启动、告警（防止把生产令牌填到测试机）；连不上时每分钟重试；</li>
 *   <li>启动发送队列和 TelegramBots 的长轮询（只收 message、callback_query；收到的交给 {@link UpdateHandler}）。</li>
 * </ol>
 * 所有请求经过一个 OkHttp 拦截器：记录成功 / 失败 / 409 给 {@link TelegramHealth}。不打请求网址日志（网址里有令牌），异常信息先去掉令牌。
 */
public class TelegramBot implements TelegramOutbox.Api {

  private static final Logger log = LoggerFactory.getLogger(TelegramBot.class);

  private final TelegramProperties props;
  private final TelegramHealth health;
  private final OkHttpClient http;
  private final OkHttpTelegramClient client;
  private final TelegramUrl url;
  private TelegramOutbox outbox;
  private UpdateHandler handler;
  private TelegramBotsLongPollingApplication app;
  private ScheduledExecutorService retry;
  private volatile boolean polling;

  public TelegramBot(TelegramProperties props, TelegramHealth health) {
    this.props = props;
    this.health = health;
    URI u = URI.create(props.apiUrl());
    int port = u.getPort() > 0 ? u.getPort() : ("http".equals(u.getScheme()) ? 80 : 443);
    this.url = new TelegramUrl(u.getScheme(), u.getHost(), port, false);
    Duration read = props.pollTimeout().plusSeconds(15);
    this.http = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(10)).readTimeout(read).writeTimeout(Duration.ofSeconds(20)).callTimeout(read.plusSeconds(10))
        .addInterceptor(this::observe)
        .build();
    this.client = new OkHttpTelegramClient(http, props.token(), url);
  }

  void wire(TelegramOutbox outbox, UpdateHandler handler) {
    this.outbox = outbox;
    this.handler = handler;
  }

  /** 看每个请求的结果（不看网址、不打日志） */
  private Response observe(Interceptor.Chain chain) throws IOException {
    Response r;
    try {
      r = chain.proceed(chain.request());
    } catch (IOException e) {
      health.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
      throw e;
    }
    int code = r.code();
    if (code == 409) health.conflict();
    else if (code == 401 || code == 404) health.fatal("Telegram 返回 " + code + "：机器人令牌不对或已被撤销，请 Amos 重新 set-key FX_TG_BOT_TOKEN");
    else if (code >= 200 && code < 300) health.ok();
    else if (code == 429 || code >= 500) health.failed("HTTP " + code);
    // 其他 4xx（400 消息内容不对、403 客户屏蔽了机器人）是单条消息的问题，不算机器人故障
    return r;
  }

  @Override
  public <T extends Serializable> T execute(BotApiMethod<T> method) throws TelegramApiException {
    return client.execute(method);
  }

  public boolean polling() {
    return polling;
  }

  /** 应用启动后调：核对用户名，然后开始轮询；连不上 Telegram 时每分钟再试 */
  public synchronized void start() {
    if (retry != null) return;
    retry = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().name("tg-start").daemon(true).unstarted(r));
    retry.execute(this::tryStart);
  }

  private void tryStart() {
    try {
      User me = client.execute(new GetMe());
      String want = props.bareUsername();
      if (!want.isEmpty() && !want.equalsIgnoreCase(me.getUserName())) {
        health.fatal("机器人令牌属于 @" + me.getUserName() + "，和配置的 FX_TG_BOT_USERNAME=@" + want + " 不一致：没有启动机器人（是不是把别的环境的令牌填到这里了？）");
        log.error("机器人令牌属于 @{}，和配置的 @{} 不一致，不启动机器人", me.getUserName(), want);
        return;
      }
      outbox.start();
      app = new TelegramBotsLongPollingApplication(tools.jackson.databind.json.JsonMapper::new, () -> http,
          () -> Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().name("tg-poll").daemon(true).unstarted(r)));
      BotSession s = app.registerBot(props.token(), () -> url, this::getUpdates, handler::consume);
      polling = s.isRunning();
      log.info("Telegram 机器人 @{} 已启动（长轮询）", me.getUserName());
    } catch (TelegramApiRequestException e) {
      if (e.getErrorCode() != null && (e.getErrorCode() == 401 || e.getErrorCode() == 404)) {
        log.error("机器人令牌不对或已被撤销（{}），不启动机器人", e.getErrorCode());
        return;
      }
      later(e);
    } catch (TelegramApiException | RuntimeException e) {
      later(e);
    }
  }

  private void later(Exception e) {
    log.warn("连不上 Telegram，1 分钟后再试：{}", props.scrub(e.getClass().getSimpleName() + ": " + e.getMessage()));
    closeApp();
    retry.schedule(this::tryStart, 1, TimeUnit.MINUTES);
  }

  /** 只收私聊会用到的两种更新；offset = 上次收到的 + 1 */
  private GetUpdates getUpdates(Integer lastReceived) {
    return GetUpdates.builder().offset(lastReceived + 1).limit(100).timeout((int) props.pollTimeout().toSeconds())
        .allowedUpdates(List.of("message", "callback_query")).build();
  }

  private void closeApp() {
    if (app == null) return;
    try {
      app.close();
    } catch (Exception ignored) {
      // 关闭失败不影响
    }
    app = null;
    polling = false;
  }

  public synchronized void stop() {
    if (retry != null) retry.shutdownNow();
    closeApp();
    if (outbox != null) outbox.stop();
    http.dispatcher().executorService().shutdown();
    http.connectionPool().evictAll();
  }
}
