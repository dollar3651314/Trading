package app.trading.payment.fake;

import app.trading.payment.topup.TopUpSigner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 假 TopUP：本机起一个 HTTP 服务，按 TopUP 开放 API 的签名和字段回应（TopUP {@code staging} 的 {@code server/pay/api-v1.js}、{@code ops.js}、
 * {@code core.js}、{@code callbacks.js}，代码走查）。后端测试和端到端测试（profile e2e）用它代替真的 TopUP，不连任何外部服务。
 * <ul>
 *   <li>每个请求都验签（X-TopUP-Key、X-TopUP-Timestamp、X-TopUP-Signature），时间戳和现在差超过 5 分钟拒收</li>
 *   <li>POST 必须带 Idempotency-Key：同一个 Key、同样的请求返回第一次的结果，请求不同返回 409；<b>24 小时后失效</b>（和 TopUP 一样，TG-1 的缺口）</li>
 *   <li>没开订单模式：{@code POST orders/} 先生成地址、再返回 403 order_mode_disabled（TopUP 现在的行为，TG-4）</li>
 *   <li>到账低于 1 USDT 不入账、不回调（TopUP {@code core.js} MIN_CREDIT）</li>
 *   <li>回调：{@code {id, type, created_at, data}}，金额是字符串；签名 = hex(HMAC-SHA256(Secret, 时间戳 \n 请求体))；交给 {@link #onCallback} 的接收方（测试直接发给 Trading 的回调接口）</li>
 * </ul>
 * 测试可以：让它不通（{@link #down}）、变慢（{@link #delayMs}）、按顺序推动一笔提现的状态（{@link #moveWithdrawal}）、把回调扣下来以后再发或乱序发。
 */
public final class FakeTopUp implements AutoCloseable {

  /** 假 TopUP 发出的一个回调：请求头 + 原始请求体 */
  public record Callback(String eventId, String type, Map<String, String> headers, String body) {}

  /** 一笔提现（TopUP 的 withdrawals 表） */
  public static final class Withdrawal {
    public final String no;
    public final String chain;
    public final String to;
    public final BigDecimal amount;
    public final String customerId;
    public final String merchantRef;
    public volatile String status = "pending";
    public volatile String txid;
    public volatile String reason;

    Withdrawal(String no, String chain, String to, BigDecimal amount, String customerId, String merchantRef) {
      this.no = no;
      this.chain = chain;
      this.to = to;
      this.amount = amount;
      this.customerId = customerId;
      this.merchantRef = merchantRef;
    }
  }

  /** 一笔已入账的到账（TopUP 的 deposits 表，result = credited） */
  public record Deposit(long id, String customerId, String chain, String address, String txid, BigDecimal amount, BigDecimal fee, Instant time) {}

  private record Idem(String hash, int status, String response, Instant at) {}

  private static final ObjectMapper JSON = JsonMapper.builder().build();
  static final BigDecimal MIN_CREDIT = BigDecimal.ONE;

  public final String apiKey;
  public final String apiSecret;
  private final Clock wall;
  private HttpServer server;

  /** 打开时所有接口返回 503（"TopUP 不通"） */
  public volatile boolean down;
  /** 每个请求先等这么久再处理（模拟超时） */
  public volatile long delayMs;
  /** 打开时提币请求处理完（已经建单、冻结）但回一个 503：模拟"TopUP 收下了、Trading 没收到回应" */
  public volatile boolean loseWithdrawReply;
  /** Trading 在 TopUP 的可用余额 */
  public volatile BigDecimal available = new BigDecimal("100000");
  /** 回调交给谁；为空时攒在 {@link #pending} 里 */
  public volatile Consumer<Callback> onCallback;

  public final Map<String, Map<String, String>> customers = new ConcurrentHashMap<>();
  public final Map<String, Withdrawal> withdrawals = new ConcurrentHashMap<>();
  public final List<Deposit> deposits = new CopyOnWriteArrayList<>();
  public final List<Callback> pending = new CopyOnWriteArrayList<>();
  /** 收到的请求（方法 + 路径），测试核对"只提交了一次"等 */
  public final List<String> requests = new CopyOnWriteArrayList<>();
  /** 实际新建的提现笔数（防重键命中的不算） */
  public final AtomicInteger created = new AtomicInteger();
  private final Map<String, Idem> idem = new ConcurrentHashMap<>();
  /** 编号（提现单号、到账编号、事件 ID）在所有假 TopUP 之间都不重复：真的 TopUP 不会重号，测试库里会留着以前跑出来的数据 */
  private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() * 1000);
  private final AtomicLong seq = SEQ;

  public FakeTopUp(String apiKey, String apiSecret, Clock wall) {
    this.apiKey = apiKey;
    this.apiSecret = apiSecret;
    this.wall = wall;
  }

  public FakeTopUp(String apiKey, String apiSecret) {
    this(apiKey, apiSecret, Clock.systemUTC());
  }

  public FakeTopUp start() {
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    server.createContext("/", this::handle);
    server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
    server.start();
    return this;
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @Override
  public void close() {
    if (server != null) server.stop(0);
  }

  /** 清空状态（测试之间） */
  public void reset() {
    down = false;
    delayMs = 0;
    loseWithdrawReply = false;
    available = new BigDecimal("100000");
    customers.clear();
    withdrawals.clear();
    deposits.clear();
    pending.clear();
    requests.clear();
    idem.clear();
    created.set(0);
  }

  // ---------- 地址 ----------

  /** 同一个客户同一条链永远是同一个地址；以太坊和 BSC 用同一个（合约地址同一套参数） */
  static String addressOf(String customerId, String chain) {
    // 以太坊、BSC：0x + 40 位十六进制；TRON：T 开头、34 位 Base58 字符（只有格式对，不是真的链上地址）
    String hex = TopUpSigner.signCallback("fake-topup-address", chain.equals("tron") ? "tron" : "evm", customerId);
    if (!chain.equals("tron")) return "0x" + hex.substring(0, 40);
    String alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    StringBuilder sb = new StringBuilder("T");
    for (int i = 0; sb.length() < 34; i++) sb.append(alphabet.charAt((Integer.parseInt(hex.substring((i % 32) * 2, (i % 32) * 2 + 2), 16) + i) % alphabet.length()));
    return sb.toString();
  }

  /** 这个客户在这条链上已经生成的地址 */
  public Optional<String> address(String customerId, String chain) {
    return Optional.ofNullable(customers.getOrDefault(customerId, Map.of()).get(chain));
  }

  // ---------- 测试用：制造到账、推动提现 ----------

  /**
   * 链上向某个地址转了 amount USDT 并确认了。低于 1 USDT 的不入账、不回调（返回空）。
   * 地址不是任何客户的也不回调。
   */
  public Optional<Callback> deposit(String chain, String address, String amount) {
    String customer = null;
    for (var c : customers.entrySet()) {
      String a = c.getValue().get(chain);
      if (a != null && (chain.equals("tron") ? a.equals(address) : a.equalsIgnoreCase(address))) customer = c.getKey();
    }
    if (customer == null) return Optional.empty();
    BigDecimal amt = new BigDecimal(amount);
    if (amt.compareTo(MIN_CREDIT) < 0) return Optional.empty();
    String txid = (chain.equals("tron") ? "" : "0x") + TopUpSigner.signCallback("tx", String.valueOf(seq.incrementAndGet()), address);
    Deposit d = new Deposit(seq.incrementAndGet(), customer, chain, address, txid, amt, BigDecimal.ZERO, wall.instant());
    deposits.add(d);
    available = available.add(amt);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("customer_id", d.customerId());
    data.put("chain", chain);
    data.put("address", address);
    data.put("txid", txid);
    data.put("amount", money(amt));
    data.put("fee", money(BigDecimal.ZERO));
    data.put("credited", money(amt));
    data.put("order_no", null);
    data.put("time", d.time().toString());
    return Optional.of(emit("deposit", data));
  }

  /**
   * 推动一笔提现：pending → approved（Amos 审核签名）→ broadcast → completed / failed；pending → rejected。
   * 返回这次状态变化的回调。
   */
  public Callback moveWithdrawal(String no, String status, String txid, String reason) {
    Withdrawal w = withdrawals.get(no);
    if (w == null) throw new IllegalArgumentException("没有这笔提现：" + no);
    w.status = status;
    if (txid != null) w.txid = txid;
    if (reason != null) w.reason = reason;
    if (status.equals("completed")) {
      // 冻结里扣掉
    } else if (status.equals("failed") || status.equals("rejected") || status.equals("cancelled")) {
      available = available.add(w.amount);
    }
    return emit("withdrawal.updated", withdrawalView(w));
  }

  /** 按商户单号（Trading 提现对外编号）找提现 */
  public Optional<Withdrawal> byMerchantRef(String ref) {
    return withdrawals.values().stream().filter(w -> ref.equals(w.merchantRef)).findFirst();
  }

  /** 生成一个签好名的回调，交给接收方或者攒起来 */
  public Callback emit(String type, Object data) {
    String id = "evt_" + TopUpSigner.signCallback("evt", String.valueOf(seq.incrementAndGet()), type).substring(0, 16);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", id);
    payload.put("type", type);
    payload.put("created_at", wall.instant().toString());
    payload.put("data", data);
    String body = JSON.writeValueAsString(payload);
    Callback cb = signed(id, type, body, String.valueOf(wall.instant().getEpochSecond()));
    Consumer<Callback> sink = onCallback;
    if (sink != null) sink.accept(cb);
    else pending.add(cb);
    return cb;
  }

  /** 同一个回调重新签名（TopUP 重试时每次用新的时间戳） */
  public Callback resign(Callback cb) {
    return signed(cb.eventId(), cb.type(), cb.body(), String.valueOf(wall.instant().getEpochSecond()));
  }

  Callback signed(String id, String type, String body, String ts) {
    Map<String, String> h = new LinkedHashMap<>();
    h.put("Content-Type", "application/json");
    h.put("User-Agent", "TopUP-Callback/1");
    h.put("X-TopUP-Event-Id", id);
    h.put("X-TopUP-Timestamp", ts);
    h.put("X-TopUP-Signature", TopUpSigner.signCallback(apiSecret, ts, body));
    return new Callback(id, type, h, body);
  }

  static String money(BigDecimal v) {
    BigDecimal s = v.setScale(6, RoundingMode.DOWN).stripTrailingZeros();
    if (s.scale() < 2) s = s.setScale(2);
    return s.toPlainString();
  }

  static Map<String, Object> withdrawalView(Withdrawal w) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("withdrawal_no", w.no);
    m.put("kind", "payout");
    m.put("chain", w.chain);
    m.put("batch_no", null);
    m.put("customer_id", w.customerId);
    m.put("to", w.to);
    m.put("amount", money(w.amount));
    m.put("fee", money(BigDecimal.ZERO));
    m.put("status", w.status);
    m.put("txid", w.txid);
    m.put("reason", w.reason);
    m.put("merchant_ref", w.merchantRef);
    m.put("source", "api");
    m.put("created_at", Instant.now().toString());
    return m;
  }

  // ---------- HTTP ----------

  private void handle(HttpExchange ex) throws IOException {
    try {
      String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      String pathAndQuery = ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
      requests.add(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath());
      if (delayMs > 0) Thread.sleep(delayMs);
      if (down) {
        send(ex, 503, error("service_unavailable"));
        return;
      }
      String key = header(ex, "X-TopUP-Key");
      String ts = header(ex, "X-TopUP-Timestamp");
      String sig = header(ex, "X-TopUP-Signature");
      if (key.isEmpty() || ts.isEmpty() || sig.isEmpty() || !key.equals(apiKey)) {
        send(ex, 401, error("invalid_signature"));
        return;
      }
      if (!ts.matches("\\d{9,11}") || Math.abs(wall.instant().getEpochSecond() - Long.parseLong(ts)) > 300) {
        send(ex, 401, error("timestamp_expired"));
        return;
      }
      if (!TopUpSigner.same(TopUpSigner.signRequest(apiSecret, ts, ex.getRequestMethod(), pathAndQuery, raw), sig.toLowerCase())) {
        send(ex, 401, error("invalid_signature"));
        return;
      }
      String path = ex.getRequestURI().getRawPath().replaceFirst("^/api/v1/?", "").replaceAll("/+$", "");
      Map<String, String> q = query(ex.getRequestURI().getRawQuery());
      String route = ex.getRequestMethod() + " " + path;
      if (ex.getRequestMethod().equals("POST")) {
        String k = header(ex, "Idempotency-Key");
        if (!k.matches("[A-Za-z0-9_.:-]{8,128}")) {
          send(ex, 400, error("idempotency_key_required"));
          return;
        }
        String hash = TopUpSigner.signCallback("idem", pathAndQuery, raw);
        Idem prev = idem.get(k);
        // TopUP 的防重记录 24 小时后清掉（server/pay/daily.js）
        if (prev != null && Duration.between(prev.at(), wall.instant()).compareTo(Duration.ofHours(24)) > 0) {
          idem.remove(k);
          prev = null;
        }
        if (prev != null) {
          if (!prev.hash().equals(hash)) send(ex, 409, error("idempotency_conflict"));
          else send(ex, prev.status(), prev.response());
          return;
        }
        Reply r = post(route, JSON.readTree(raw.isEmpty() ? "{}" : raw));
        // 失败的请求不占用这个 Key（和 TopUP 一样），商户修正后可以用同一个 Key 重试
        if (r.status() == 200) idem.put(k, new Idem(hash, r.status(), r.body(), wall.instant()));
        if (route.equals("POST withdrawals") && loseWithdrawReply && r.status() == 200) {
          send(ex, 503, error("lost"));
          return;
        }
        send(ex, r.status(), r.body());
        return;
      }
      Reply r = get(route, q);
      send(ex, r.status(), r.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      send(ex, 500, error("internal_error"));
    } finally {
      ex.close();
    }
  }

  record Reply(int status, String body) {}

  private Reply post(String route, JsonNode b) {
    switch (route) {
      case "POST customers" -> {
        String id = b.path("customer_id").asString("");
        if (!id.matches("[A-Za-z0-9_.@-]{1,128}")) return new Reply(422, error("invalid_param"));
        customers.computeIfAbsent(id, x -> new ConcurrentHashMap<>());
        return new Reply(200, customerView(id));
      }
      case "POST orders" -> {
        String id = b.path("customer_id").asString("");
        String chain = b.path("chain").asString("");
        if (!id.matches("[A-Za-z0-9_.@-]{1,128}")) return new Reply(422, error("invalid_param"));
        if (!chain.isEmpty() && !List.of("eth", "bsc", "tron").contains(chain)) return new Reply(422, error("invalid_param"));
        if (!chain.isEmpty()) customers.computeIfAbsent(id, x -> new ConcurrentHashMap<>()).computeIfAbsent(chain, c -> addressOf(id, c));
        // Trading 这个内部商户没开订单模式：地址已经生成，建单返回 403
        return new Reply(403, error("order_mode_disabled"));
      }
      case "POST withdrawals" -> {
        if (!"payout".equals(b.path("kind").asString(""))) return new Reply(422, error("invalid_param"));
        String chain = b.path("chain").asString("tron");
        if (!List.of("eth", "bsc", "tron").contains(chain)) return new Reply(422, error("invalid_param"));
        String to = b.path("to").asString("");
        boolean okAddr = chain.equals("tron") ? to.matches("T[1-9A-HJ-NP-Za-km-z]{33}") : to.matches("0x[0-9a-fA-F]{40}");
        if (!okAddr) return new Reply(422, error("invalid_address"));
        BigDecimal amt;
        try {
          amt = new BigDecimal(b.path("amount").asString(""));
        } catch (NumberFormatException e) {
          return new Reply(422, error("invalid_param"));
        }
        if (amt.signum() <= 0 || amt.scale() > 6) return new Reply(422, error("invalid_param"));
        if (amt.compareTo(available) > 0) return new Reply(422, error("insufficient_balance"));
        available = available.subtract(amt);
        String no = "WD-" + seq.incrementAndGet();
        Withdrawal w = new Withdrawal(no, chain, to, amt, b.path("customer_id").asString(null), b.path("merchant_ref").asString(null));
        withdrawals.put(no, w);
        created.incrementAndGet();
        emit("withdrawal.updated", withdrawalView(w));
        return new Reply(200, JSON.writeValueAsString(withdrawalView(w)));
      }
      default -> {
        return new Reply(404, error("not_found"));
      }
    }
  }

  private Reply get(String route, Map<String, String> q) {
    switch (route) {
      case "GET customers" -> {
        String id = q.getOrDefault("customer_id", "");
        if (!customers.containsKey(id)) return new Reply(404, error("not_found"));
        return new Reply(200, customerView(id));
      }
      case "GET balance" -> {
        return new Reply(200, JSON.writeValueAsString(Map.of("available", money(available), "frozen", money(BigDecimal.ZERO), "currency", "USDT")));
      }
      case "GET withdrawals" -> {
        Withdrawal w = withdrawals.get(q.getOrDefault("withdrawal_no", ""));
        if (w == null) return new Reply(404, error("not_found"));
        return new Reply(200, JSON.writeValueAsString(withdrawalView(w)));
      }
      case "GET deposits" -> {
        String id = q.getOrDefault("customer_id", "");
        List<Map<String, Object>> items = new ArrayList<>();
        for (Deposit d : deposits.reversed()) {
          if (!id.isEmpty() && !id.equals(d.customerId())) continue;
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("id", String.valueOf(d.id()));
          m.put("customer_id", d.customerId());
          m.put("chain", d.chain());
          m.put("address", d.address());
          m.put("txid", d.txid());
          m.put("amount", money(d.amount()));
          m.put("fee", money(d.fee()));
          m.put("credited", money(d.amount().subtract(d.fee())));
          m.put("result", "credited");
          m.put("order_no", null);
          m.put("matched_by", null);
          m.put("time", d.time().toString());
          items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("next_cursor", null);
        return new Reply(200, JSON.writeValueAsString(out));
      }
      default -> {
        return new Reply(404, error("not_found"));
      }
    }
  }

  private String customerView(String id) {
    List<Map<String, String>> addrs = new ArrayList<>();
    for (String c : List.of("eth", "bsc", "tron")) {
      String a = customers.getOrDefault(id, Map.of()).get(c);
      if (a != null) addrs.add(Map.of("chain", c, "address", a));
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("customer_id", id);
    m.put("name", "");
    m.put("addresses", addrs);
    return JSON.writeValueAsString(m);
  }

  private static String error(String code) {
    return JSON.writeValueAsString(Map.of("error", Map.of("code", code, "message", code)));
  }

  private static String header(HttpExchange ex, String name) {
    String v = ex.getRequestHeaders().getFirst(name);
    return v == null ? "" : v;
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> m = new LinkedHashMap<>();
    if (raw == null || raw.isEmpty()) return m;
    for (String kv : raw.split("&")) {
      int i = kv.indexOf('=');
      String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8);
      String v = i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
      m.put(k, v);
    }
    return m;
  }

  private static void send(HttpExchange ex, int status, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json");
    ex.sendResponseHeaders(status, b.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(b);
    }
  }
}
