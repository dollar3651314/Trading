package app.trading.telegram.bot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 假 Telegram（Bot API）：本机起一个 HTTP 服务，回应 getMe、deleteWebhook、getUpdates（长轮询）、sendMessage、editMessageText、answerCallbackQuery。
 * 测试不连真实 Telegram（Amos 还没给令牌，也不在开发机填令牌）。和真 Telegram 不同的地方都没验证过，见交付说明"未验证"。
 * 测试可以：推一条更新（{@link #push}）、让某个方法下几次返回指定状态（{@link #failNext}，例如 409、429、403、500）、看收到的请求（{@link #calls}）。
 */
public final class FakeTelegram implements AutoCloseable {

  /** 收到的一个请求：方法名、请求体、收到时间（System.nanoTime） */
  public record Call(String method, JsonNode body, long at) {}

  public final String token;
  private final String username;
  private final ObjectMapper json = JsonMapper.builder().build();
  private final HttpServer server;
  private final LinkedBlockingQueue<String> updates = new LinkedBlockingQueue<>();
  private final List<Call> calls = new CopyOnWriteArrayList<>();
  private final Map<String, Deque<Integer>> failures = new ConcurrentHashMap<>();
  private final AtomicInteger updateId = new AtomicInteger(1000);
  private final AtomicInteger messageId = new AtomicInteger(1);
  private volatile String getMeUsername;

  public FakeTelegram(String token, String username) throws IOException {
    this.token = token;
    this.username = username;
    this.getMeUsername = username;
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
      Thread t = new Thread(r, "fake-telegram");
      t.setDaemon(true);
      return t;
    }));
    server.createContext("/", this::handle);
    server.start();
  }

  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** getMe 返回别的用户名（模拟把别的环境的令牌填进来） */
  public void getMeReturns(String name) {
    this.getMeUsername = name;
  }

  public List<Call> calls() {
    return List.copyOf(calls);
  }

  public List<Call> calls(String method) {
    return calls.stream().filter(c -> c.method.equals(method)).toList();
  }

  public void clear() {
    calls.clear();
  }

  /** 某个方法接下来几次返回这个状态码（429 带 retry_after 1 秒） */
  public void failNext(String method, int status, int times) {
    Deque<Integer> d = failures.computeIfAbsent(method, k -> new ArrayDeque<>());
    synchronized (d) {
      for (int i = 0; i < times; i++) d.addLast(status);
    }
  }

  /** 等到满足条件的请求出现，返回它们 */
  public List<Call> await(String method, Predicate<Call> p, int count, Duration timeout) throws InterruptedException {
    long end = System.nanoTime() + timeout.toNanos();
    while (true) {
      List<Call> got = calls(method).stream().filter(p).toList();
      if (got.size() >= count || System.nanoTime() > end) return got;
      Thread.sleep(20);
    }
  }

  // ---------- 推更新 ----------

  public void push(ObjectNode update) {
    update.put("update_id", updateId.incrementAndGet());
    updates.add(update.toString());
  }

  ObjectNode user(long id, String uname) {
    ObjectNode u = json.createObjectNode().put("id", id).put("is_bot", false).put("first_name", "U" + id);
    if (uname != null) u.put("username", uname);
    return u;
  }

  ObjectNode chat(long id, String type) {
    ObjectNode c = json.createObjectNode().put("id", id).put("type", type);
    if (!"private".equals(type)) c.put("title", "群");
    else c.put("first_name", "U" + id);
    return c;
  }

  /** 私聊里发了一条文字 */
  public ObjectNode text(long fromId, String uname, String text) {
    return message(fromId, uname, fromId, "private", text);
  }

  /** 在群（chatType = group / supergroup）里发了一条文字 */
  public ObjectNode groupText(long fromId, long groupId, String text) {
    return message(fromId, null, groupId, "group", text);
  }

  private ObjectNode message(long fromId, String uname, long chatId, String type, String text) {
    ObjectNode m = json.createObjectNode().put("message_id", messageId.incrementAndGet()).put("date", (int) (System.currentTimeMillis() / 1000)).put("text", text);
    m.set("from", user(fromId, uname));
    m.set("chat", chat(chatId, type));
    ObjectNode u = json.createObjectNode();
    u.set("message", m);
    return u;
  }

  /** 点了按钮：fromId 是点的人，按钮所在的消息在 chatId 这个聊天里 */
  public ObjectNode callback(long fromId, String uname, long chatId, String chatType, String data) {
    ObjectNode m = json.createObjectNode().put("message_id", 77).put("date", (int) (System.currentTimeMillis() / 1000)).put("text", "x");
    m.set("chat", chat(chatId, chatType));
    m.set("from", json.createObjectNode().put("id", 1).put("is_bot", true).put("first_name", "bot").put("username", username));
    ObjectNode q = json.createObjectNode().put("id", "cb" + updateId.get()).put("chat_instance", "ci").put("data", data);
    q.set("from", user(fromId, uname));
    q.set("message", m);
    ObjectNode u = json.createObjectNode();
    u.set("callback_query", q);
    return u;
  }

  // ---------- HTTP ----------

  private void handle(HttpExchange ex) throws IOException {
    try (ex) {
      String path = ex.getRequestURI().getPath();
      String prefix = "/bot" + token + "/";
      if (!path.startsWith(prefix)) {
        reply(ex, 401, "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}");
        return;
      }
      // 方法名不分大小写（真 Telegram 也是；TelegramBots 发 getme、getupdates 这样的小写）
      String method = canonical(path.substring(prefix.length()));
      byte[] raw = ex.getRequestBody().readAllBytes();
      JsonNode body = raw.length == 0 ? json.createObjectNode() : json.readTree(new String(raw, StandardCharsets.UTF_8));
      calls.add(new Call(method, body, System.nanoTime()));
      Deque<Integer> d = failures.get(method);
      Integer fail = null;
      if (d != null) synchronized (d) {
        fail = d.pollFirst();
      }
      if (fail != null) {
        String params = fail == 429 ? ",\"parameters\":{\"retry_after\":1}" : "";
        reply(ex, fail, "{\"ok\":false,\"error_code\":" + fail + ",\"description\":\"fake " + fail + "\"" + params + "}");
        return;
      }
      switch (method) {
        case "getMe" -> reply(ex, 200, "{\"ok\":true,\"result\":{\"id\":1,\"is_bot\":true,\"first_name\":\"bot\",\"username\":\"" + getMeUsername + "\"}}");
        case "deleteWebhook", "answerCallbackQuery" -> reply(ex, 200, "{\"ok\":true,\"result\":true}");
        case "getUpdates" -> {
          List<String> out = new ArrayList<>();
          String first = updates.poll(300, TimeUnit.MILLISECONDS);
          if (first != null) {
            out.add(first);
            updates.drainTo(out);
          }
          reply(ex, 200, "{\"ok\":true,\"result\":[" + String.join(",", out) + "]}");
        }
        case "sendMessage", "editMessageText" -> {
          long chat = body.path("chat_id").asLong();
          ObjectNode m = json.createObjectNode().put("message_id", messageId.incrementAndGet()).put("date", (int) (System.currentTimeMillis() / 1000))
              .put("text", body.path("text").asString(""));
          m.set("chat", chat(chat, "private"));
          ObjectNode r = json.createObjectNode().put("ok", true);
          r.set("result", m);
          reply(ex, 200, r.toString());
        }
        default -> reply(ex, 404, "{\"ok\":false,\"error_code\":404,\"description\":\"Not Found\"}");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static final List<String> METHODS = List.of("getMe", "deleteWebhook", "answerCallbackQuery", "getUpdates", "sendMessage", "editMessageText");

  private static String canonical(String m) {
    return METHODS.stream().filter(x -> x.equalsIgnoreCase(m)).findFirst().orElse(m);
  }

  private static void reply(HttpExchange ex, int status, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json");
    ex.sendResponseHeaders(status, b.length);
    try (OutputStream o = ex.getResponseBody()) {
      o.write(b);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
