package app.trading.admin.security;

import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * 测试管理员（{@link TestAdmin}）拿到的返回打码（做法 07 第 7 条）：客户姓名、邮箱等字段（{@link #KEYS}）改成"首字 + ***"；
 * 其余字符串里邮箱形式的内容（例如原因、备注里写了邮箱）也改成"首字***"。编号、地址、金额照常。只对测试管理员的请求生效。
 * 这只是兜底：客户列表、客户详情、KYC 本来就不在白名单里。
 */
@ControllerAdvice
class TestAdminMasking implements ResponseBodyAdvice<Object> {

  static final Set<String> KEYS = Set.of("customerName", "clientName", "email", "name", "realName", "displayName", "adminName", "actorName");
  private static final Pattern AT_LIKE = Pattern.compile("@|＠|%40", Pattern.CASE_INSENSITIVE);
  private static final Pattern EMAIL_LIKE = Pattern.compile("[^\\s@＠%\"'<>(),;:]{1,64}(?:@|＠|%40)[^\\s@＠%\"'<>(),;:]{1,255}", Pattern.CASE_INSENSITIVE);

  private final ObjectMapper json;

  TestAdminMasking(ObjectMapper json) {
    this.json = json;
  }

  @Override
  public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
    return true;
  }

  @Override
  public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType, Class<? extends HttpMessageConverter<?>> converterType,
      ServerHttpRequest request, ServerHttpResponse response) {
    if (body == null || !TestAdmin.current() || body instanceof byte[] || body instanceof CharSequence) return body;
    if (contentType == null || !contentType.isCompatibleWith(MediaType.APPLICATION_JSON)) return body;
    return mask(json.valueToTree(body));
  }

  static JsonNode mask(JsonNode n) {
    if (n instanceof ObjectNode o) {
      for (String k : new java.util.ArrayList<>(o.propertyNames())) {
        JsonNode v = o.get(k);
        o.set(k, KEYS.contains(k) && v.isString() ? StringNode.valueOf(first(v.asString())) : mask(v));
      }
      return o;
    }
    if (n instanceof ArrayNode a) {
      for (int i = 0; i < a.size(); i++) a.set(i, mask(a.get(i)));
      return a;
    }
    if (n != null && n.isString()) {
      String s = n.asString();
      if (AT_LIKE.matcher(s).find()) return StringNode.valueOf(EMAIL_LIKE.matcher(s).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(first(m.group()))));
    }
    return n;
  }

  private static String first(String s) {
    if (s == null || s.isEmpty()) return s;
    return new String(Character.toChars(s.codePointAt(0))) + "***";
  }
}
