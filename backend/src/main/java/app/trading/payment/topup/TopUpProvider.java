package app.trading.payment.topup;

import app.trading.payment.api.PayNetwork;
import app.trading.payment.api.PaymentApi;
import app.trading.payment.api.PayoutState;
import app.trading.payment.provider.PaymentProvider;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * TopUP 开放 API 的适配（Trading 是 TopUP 的内部商户，Trading 的每个客户是这个商户下的一个"客户"，客户标识 = Trading 客户号）。
 * 接口和字段照 TopUP {@code staging} 的 {@code server/pay/api-v1.js}、{@code ops.js}、{@code core.js}（代码走查，没有连过 TopUP 测试环境；联调在第 4 批）：
 * <ul>
 *   <li>开户：{@code POST /api/v1/customers/}（有就更新、没有就建）</li>
 *   <li>取地址：TopUP 没有"只要地址"的接口（TG-4），照 TopUP 官方测试脚本 {@code scripts/testnet/fund-addresses.mjs}：
 *       {@code POST /api/v1/orders/} 带 chain（没开订单模式返回 403 order_mode_disabled，但地址在建单之前已经生成），再 {@code GET /api/v1/customers/} 读出这条链的地址</li>
 *   <li>代付：{@code POST /api/v1/withdrawals/}，kind=payout，<b>每次都传 chain</b>（不传 TopUP 默认 TRON），Idempotency-Key = Trading 提现单号</li>
 *   <li>查付款：{@code GET /api/v1/withdrawals/?withdrawal_no=}；查余额：{@code GET /api/v1/balance/}；到账：{@code GET /api/v1/deposits/?customer_id=}</li>
 *   <li>回调：{@code deposit}、{@code withdrawal.updated}；X-TopUP-Event-Id、X-TopUP-Timestamp、X-TopUP-Signature</li>
 * </ul>
 * 网络错误、超时、5xx、429 抛 {@link PaymentApi.Unavailable}（可以重试）；其余 4xx 抛 {@link ProviderError}。
 */
public class TopUpProvider implements PaymentProvider {

  public static final String CODE = "TOPUP";
  /** 回调时间戳和现在最多差 5 分钟（和 TopUP 收请求时的规矩一样） */
  static final long MAX_SKEW_S = 300;
  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private final TopUpProperties props;
  private final HttpClient http;
  /** 签名用真实时间（不是业务时钟）：TopUP 拒收和它的时间差超过 5 分钟的请求 */
  private final Clock wall;

  public TopUpProvider(TopUpProperties props) {
    this(props, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC());
  }

  public TopUpProvider(TopUpProperties props, HttpClient http, Clock wall) {
    this.props = props;
    this.http = http;
    this.wall = wall;
  }

  @Override
  public String code() {
    return CODE;
  }

  @Override
  public String name() {
    return "TopUP";
  }

  @Override
  public boolean connected() {
    return props.configured();
  }

  static String chain(PayNetwork n) {
    return switch (n) {
      case ERC20 -> "eth";
      case BSC -> "bsc";
      case TRC20 -> "tron";
    };
  }

  static PayNetwork network(String chain) {
    return switch (chain == null ? "" : chain) {
      case "eth" -> PayNetwork.ERC20;
      case "bsc" -> PayNetwork.BSC;
      case "tron" -> PayNetwork.TRC20;
      default -> null;
    };
  }

  /** TopUP 对外的提现状态（core.js 的 WD_PUBLIC） → Trading 口径；不认识的算"未知" */
  static PayoutState state(String s) {
    return switch (s == null ? "" : s) {
      case "pending" -> PayoutState.PENDING;
      case "approved" -> PayoutState.APPROVED;
      case "broadcast" -> PayoutState.BROADCAST;
      case "completed" -> PayoutState.COMPLETED;
      case "rejected" -> PayoutState.REJECTED;
      case "cancelled" -> PayoutState.CANCELLED;
      case "failed" -> PayoutState.FAILED;
      default -> PayoutState.UNKNOWN;
    };
  }

  /** 金额：TopUP 对外一律是字符串（"500.00"），最多 6 位小数 */
  static BigDecimal money(JsonNode n) {
    if (n == null || n.isNull() || n.isMissingNode()) return BigDecimal.ZERO;
    return new BigDecimal(n.asString("0").replace(",", ""));
  }

  static String amountText(BigDecimal v) {
    return v.stripTrailingZeros().toPlainString();
  }

  // ---------- 调用 ----------

  record Reply(int status, JsonNode body) {
    String errorCode() {
      return body == null ? "" : body.path("error").path("code").asString("");
    }
  }

  private static String q(String v) {
    return URLEncoder.encode(v, StandardCharsets.UTF_8);
  }

  Reply call(String method, String path, Map<String, String> query, Object body, String idemKey) {
    if (!connected()) throw new PaymentApi.Unavailable("TopUP 没有配置", null);
    StringBuilder pq = new StringBuilder("/api/v1/").append(path);
    if (query != null && !query.isEmpty()) {
      pq.append('?');
      boolean first = true;
      for (var e : query.entrySet()) {
        if (!first) pq.append('&');
        pq.append(q(e.getKey())).append('=').append(q(e.getValue()));
        first = false;
      }
    }
    String text = body == null ? "" : JSON.writeValueAsString(body);
    String ts = String.valueOf(wall.instant().getEpochSecond());
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(props.baseUrl().replaceAll("/+$", "") + pq)).timeout(props.timeout())
        .header("X-TopUP-Key", props.apiKey()).header("X-TopUP-Timestamp", ts)
        .header("X-TopUP-Signature", TopUpSigner.signRequest(props.apiSecret(), ts, method, pq.toString(), text));
    if ("GET".equals(method)) b.GET();
    else {
      b.header("Content-Type", "application/json").header("Idempotency-Key", idemKey).method(method, HttpRequest.BodyPublishers.ofString(text));
    }
    HttpResponse<String> res;
    try {
      res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new PaymentApi.Unavailable("TopUP 连不上：" + e.getClass().getSimpleName(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PaymentApi.Unavailable("TopUP 请求被中断", e);
    }
    JsonNode json = null;
    try {
      json = res.body() == null || res.body().isBlank() ? null : JSON.readTree(res.body());
    } catch (RuntimeException e) {
      json = null;
    }
    Reply r = new Reply(res.statusCode(), json);
    if (r.status() >= 500 || r.status() == 429) throw new PaymentApi.Unavailable("TopUP 返回 " + r.status() + " " + r.errorCode(), null);
    // 同一个防重键的上一次请求还没处理完：等一下再提，结果一样
    if (r.status() == 409 && "request_in_progress".equals(r.errorCode())) throw new PaymentApi.Unavailable("TopUP 还在处理上一次请求", null);
    return r;
  }

  private Reply ok(Reply r) {
    if (r.status() != 200) throw new ProviderError(r.status(), r.errorCode());
    return r;
  }

  // ---------- 动作 ----------

  @Override
  public void ensureCustomer(String customerId) {
    ok(call("POST", "customers/", null, Map.of("customer_id", customerId), "tr-cust-" + customerId));
  }

  @Override
  public String address(String customerId, PayNetwork network) {
    String chain = chain(network);
    String orderNo = "tr-addr-" + customerId + "-" + chain;
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("customer_id", customerId);
    body.put("chain", chain);
    body.put("amount", "1");
    body.put("merchant_order_no", orderNo);
    Reply made = call("POST", "orders/", null, body, orderNo);
    // 200：开了订单模式，建了一张单（地址也生成了）；403 order_mode_disabled：没开订单模式，地址已经生成；409：同一张单建过了
    boolean fine = made.status() == 200 || "order_mode_disabled".equals(made.errorCode()) || "duplicate_merchant_order_no".equals(made.errorCode());
    if (!fine) throw new ProviderError(made.status(), made.errorCode());
    JsonNode c = ok(call("GET", "customers/", Map.of("customer_id", customerId), null, null)).body();
    for (JsonNode a : c.path("addresses")) {
      if (chain.equals(a.path("chain").asString("")) && !a.path("address").asString("").isBlank()) return a.path("address").asString();
    }
    throw new ProviderError(200, "address_missing");
  }

  @Override
  public Submitted submitPayout(String idemKey, String merchantRef, String customerId, PayNetwork network, String address, BigDecimal amount) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("kind", "payout");
    body.put("chain", chain(network));
    body.put("to", address);
    body.put("amount", amountText(amount));
    body.put("customer_id", customerId);
    body.put("merchant_ref", merchantRef);
    JsonNode w = ok(call("POST", "withdrawals/", null, body, idemKey)).body();
    return new Submitted(w.path("withdrawal_no").asString(), state(w.path("status").asString("")));
  }

  @Override
  public Optional<PayoutView> payout(String ref) {
    Reply r = call("GET", "withdrawals/", Map.of("withdrawal_no", ref), null, null);
    if (r.status() == 404) return Optional.empty();
    return Optional.of(view(ok(r).body()));
  }

  static PayoutView view(JsonNode w) {
    String tx = w.path("txid").asString("");
    String reason = w.path("reason").asString("");
    return new PayoutView(w.path("withdrawal_no").asString(), w.path("merchant_ref").asString(""), state(w.path("status").asString("")), money(w.get("amount")),
        network(w.path("chain").asString("")), w.path("to").asString(""), tx.isBlank() ? null : tx, reason.isBlank() ? null : reason);
  }

  @Override
  public BalanceView balance() {
    JsonNode b = ok(call("GET", "balance/", null, null, null)).body();
    return new BalanceView(money(b.get("available")), money(b.get("frozen")));
  }

  /** 最多翻 20 页（每页 100 条） */
  @Override
  public List<DepositView> deposits(String customerId) {
    List<DepositView> out = new ArrayList<>();
    String cursor = null;
    for (int page = 0; page < 20; page++) {
      Map<String, String> query = new LinkedHashMap<>();
      query.put("customer_id", customerId);
      query.put("limit", "100");
      if (cursor != null) query.put("cursor", cursor);
      JsonNode r = ok(call("GET", "deposits/", query, null, null)).body();
      for (JsonNode d : r.path("items")) {
        out.add(new DepositView(d.path("id").asString(), d.path("customer_id").asString(), network(d.path("chain").asString("")), d.path("address").asString(""),
            d.path("txid").asString(""), money(d.get("amount")), money(d.get("fee")), parseTime(d.path("time").asString(""))));
      }
      cursor = r.path("next_cursor").isNull() || r.path("next_cursor").isMissingNode() ? null : r.path("next_cursor").asString(null);
      if (cursor == null) break;
    }
    return out;
  }

  private static Instant parseTime(String s) {
    try {
      return s.isBlank() ? null : Instant.parse(s);
    } catch (RuntimeException e) {
      return null;
    }
  }

  // ---------- 回调 ----------

  @Override
  public Optional<Event> parseCallback(Map<String, String> headers, String body) {
    if (!connected()) throw new BadSignature("TopUP 没有配置");
    String id = headers.getOrDefault("x-topup-event-id", "");
    String ts = headers.getOrDefault("x-topup-timestamp", "");
    String sig = headers.getOrDefault("x-topup-signature", "");
    if (id.isBlank() || ts.isBlank() || sig.isBlank() || body == null) throw new BadSignature("缺少签名头");
    if (!ts.matches("\\d{9,11}") || Math.abs(wall.instant().getEpochSecond() - Long.parseLong(ts)) > MAX_SKEW_S) throw new BadSignature("时间戳过期");
    if (!TopUpSigner.same(TopUpSigner.signCallback(props.apiSecret(), ts, body), sig.toLowerCase())) throw new BadSignature("签名不对");
    JsonNode p;
    try {
      p = JSON.readTree(body);
    } catch (RuntimeException e) {
      throw new BadSignature("请求体不是 JSON");
    }
    if (!id.equals(p.path("id").asString(""))) throw new BadSignature("事件 ID 和请求体不一致");
    JsonNode d = p.path("data");
    return switch (p.path("type").asString("")) {
      case "deposit" -> {
        PayNetwork n = network(d.path("chain").asString(""));
        if (n == null) yield Optional.empty();
        yield Optional.of(new DepositEvent(id, d.path("customer_id").asString(""), n, d.path("address").asString(""), d.path("txid").asString(""),
            money(d.get("amount")), money(d.get("fee"))));
      }
      case "withdrawal.updated" -> {
        PayoutView v = view(d);
        yield Optional.of(new PayoutEvent(id, v.ref(), v.merchantRef(), v.state(), v.txHash(), v.reason(), v.amount(), v.network(),
            v.address().isBlank() ? null : v.address()));
      }
      default -> Optional.empty();
    };
  }
}
