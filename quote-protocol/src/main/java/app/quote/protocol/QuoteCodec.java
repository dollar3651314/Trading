package app.quote.protocol;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 协议消息和 JSON 文本互转。价格写成字符串，原样保留小数位。 */
public final class QuoteCodec {

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private QuoteCodec() {}

  public static String encode(Message m) {
    ObjectNode n = JSON.createObjectNode();
    switch (m) {
      case QuoteFrame f -> {
        n.put("type", "quote");
        n.put("symbol", f.symbol());
        n.put("seq", f.seq());
        n.put("bid", f.bid().toPlainString());
        n.put("ask", f.ask().toPlainString());
        n.put("time", f.time());
        n.put("status", f.status());
        if (f.state() != null) n.put("state", f.state());
        if (f.feed() != null) n.put("feed", f.feed());
        n.put("tradable", f.tradable());
        if (f.snapshot()) n.put("snapshot", true);
      }
      case Message.Hello h -> {
        n.put("type", "hello");
        n.put("epoch", h.epoch());
        n.put("mode", h.mode());
        n.put("serverTime", h.serverTime());
        ArrayNode a = n.putArray("sources");
        for (Message.Source x : h.sources()) {
          ObjectNode o = a.addObject();
          o.put("symbol", x.symbol());
          o.put("feed", x.feed());
          if (x.state() != null) o.put("state", x.state());
        }
      }
      case Message.Sample s -> {
        n.put("type", "sample");
        n.put("symbol", s.symbol());
        n.put("bid", s.bid().toPlainString());
        n.put("ask", s.ask().toPlainString());
        n.put("time", s.time());
      }
      case Message.Heartbeat h -> {
        n.put("type", "heartbeat");
        n.put("serverTime", h.serverTime());
        ArrayNode a = n.putArray("alerts");
        for (Message.Alert x : h.alerts()) {
          ObjectNode o = a.addObject();
          o.put("name", x.name());
          o.put("titleZh", x.titleZh());
          o.put("titleEn", x.titleEn());
          o.put("ok", x.ok());
          o.put("detail", x.detail());
        }
      }
      case Message.Error e -> {
        n.put("type", "error");
        n.put("code", e.code());
        n.put("message", e.message());
      }
      case Message.Subscribe s -> {
        n.put("op", "subscribe");
        if (s.epoch() != null) n.put("epoch", s.epoch());
        ObjectNode since = n.putObject("since");
        if (s.since() != null) s.since().forEach(since::put);
      }
      case Message.Bar b -> {
        n.put("type", "bar");
        n.put("symbol", b.symbol());
        n.put("tf", b.tf());
        n.put("time", b.time());
        n.put("open", b.open().toPlainString());
        n.put("high", b.high().toPlainString());
        n.put("low", b.low().toPlainString());
        n.put("close", b.close().toPlainString());
        n.put("ticks", b.ticks());
      }
      case Message.WatchBars w -> {
        n.put("op", "watchBars");
        ArrayNode a = n.putArray("keys");
        w.keys().stream().sorted().forEach(a::add);
      }
      case Message.Resync r -> {
        n.put("op", "resync");
        ObjectNode syms = n.putObject("symbols");
        r.symbols().forEach(syms::put);
      }
    }
    return JSON.writeValueAsString(n);
  }

  /** 解析一条消息；格式不对抛 {@link IllegalArgumentException} */
  public static Message decode(String text) {
    JsonNode n;
    try {
      n = JSON.readTree(text);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("不是 JSON");
    }
    if (n == null || !n.isObject()) throw new IllegalArgumentException("不是 JSON 对象");
    String op = n.path("op").asString("");
    if (!op.isEmpty()) {
      return switch (op) {
        case "subscribe" -> new Message.Subscribe(text(n, "epoch"), longs(n.path("since")));
        case "resync" -> new Message.Resync(longs(n.path("symbols")));
        case "watchBars" -> {
          java.util.Set<String> keys = new java.util.LinkedHashSet<>();
          for (JsonNode k : n.path("keys")) keys.add(k.asString());
          yield new Message.WatchBars(keys);
        }
        default -> throw new IllegalArgumentException("不认识的 op " + op);
      };
    }
    String type = n.path("type").asString("");
    return switch (type) {
      case "quote" -> new QuoteFrame(required(n, "symbol"), n.path("seq").asLong(), decimal(n, "bid"), decimal(n, "ask"), n.path("time").asLong(),
          required(n, "status"), text(n, "state"), text(n, "feed"), n.path("tradable").asBoolean(false), n.path("snapshot").asBoolean(false));
      case "hello" -> {
        List<Message.Source> sources = new ArrayList<>();
        for (JsonNode a : n.path("sources")) sources.add(new Message.Source(text(a, "symbol"), text(a, "feed"), text(a, "state")));
        yield new Message.Hello(required(n, "epoch"), text(n, "mode"), n.path("serverTime").asLong(), sources);
      }
      case "sample" -> new Message.Sample(required(n, "symbol"), decimal(n, "bid"), decimal(n, "ask"), n.path("time").asLong());
      case "heartbeat" -> {
        List<Message.Alert> alerts = new ArrayList<>();
        for (JsonNode a : n.path("alerts")) {
          alerts.add(new Message.Alert(text(a, "name"), text(a, "titleZh"), text(a, "titleEn"), a.path("ok").asBoolean(false), text(a, "detail")));
        }
        yield new Message.Heartbeat(n.path("serverTime").asLong(), List.copyOf(alerts));
      }
      case "error" -> new Message.Error(text(n, "code"), text(n, "message"));
      case "bar" -> new Message.Bar(required(n, "symbol"), required(n, "tf"), n.path("time").asLong(), decimal(n, "open"), decimal(n, "high"), decimal(n, "low"),
          decimal(n, "close"), n.path("ticks").asInt());
      default -> throw new IllegalArgumentException("不认识的 type " + type);
    };
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? null : v.asString();
  }

  private static String required(JsonNode n, String field) {
    String v = text(n, field);
    if (v == null || v.isEmpty()) throw new IllegalArgumentException("缺少 " + field);
    return v;
  }

  private static BigDecimal decimal(JsonNode n, String field) {
    try {
      return new BigDecimal(required(n, field));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(field + " 不是数字");
    }
  }

  private static Map<String, Long> longs(JsonNode n) {
    Map<String, Long> out = new LinkedHashMap<>();
    if (n != null && n.isObject()) n.properties().forEach(e -> out.put(e.getKey(), e.getValue().asLong()));
    return out;
  }
}
