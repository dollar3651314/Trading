package app.quote.open;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 对外接口的统一错误格式 {@code {code, message, traceId}}（需求 03 页第二节）；每个请求一个追踪号，也放在响应头 X-Trace-Id。 */
final class OpenErrors {

  static final String TRACE_ATTR = "quote.open.traceId";
  static final String TRACE_HEADER = "X-Trace-Id";
  static final ObjectMapper JSON = JsonMapper.builder().build();

  private OpenErrors() {}

  static String trace(HttpServletRequest req) {
    Object t = req.getAttribute(TRACE_ATTR);
    if (t instanceof String s) return s;
    String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    req.setAttribute(TRACE_ATTR, id);
    return id;
  }

  static Map<String, Object> body(String code, String message, String traceId) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("code", code);
    m.put("message", message);
    m.put("traceId", traceId);
    return m;
  }

  static void write(HttpServletRequest req, HttpServletResponse res, int status, Map<String, Object> body) throws IOException {
    res.setStatus(status);
    res.setHeader(TRACE_HEADER, trace(req));
    res.setContentType("application/json;charset=UTF-8");
    res.getWriter().write(JSON.writeValueAsString(body));
  }

  static ResponseEntity<Object> bad(HttpServletRequest req, String code, String message) {
    return ResponseEntity.badRequest().header(TRACE_HEADER, trace(req)).body(body(code, message, trace(req)));
  }
}
