package app.trading.common.web;

import app.trading.common.error.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 当前登录的客户。登录检查由 account 模块的过滤器完成，其他模块只从这里读用户 ID，不直接接触会话。
 */
public final class CurrentUser {

  static final String ATTR = "fx.userId";
  static final String ATTR_BOT = "fx.botSession";

  private CurrentUser() {}

  public static void set(HttpServletRequest request, long userId) {
    request.setAttribute(ATTR, userId);
  }

  /** 这次请求的会话是不是从机器人一键登录的（TG 机器人第 2、4 批：提现单记入口 BOT / WEB） */
  public static void setBotSession(HttpServletRequest request, boolean bot) {
    request.setAttribute(ATTR_BOT, bot);
  }

  /** 当前请求是不是机器人会话；不在请求里时为 false */
  public static boolean botSession() {
    RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
    return attrs != null && Boolean.TRUE.equals(attrs.getAttribute(ATTR_BOT, RequestAttributes.SCOPE_REQUEST));
  }

  /** 指定请求上的用户 ID（WebSocket 握手时用）；没有登录时为空。 */
  public static java.util.Optional<Long> of(HttpServletRequest request) {
    return request.getAttribute(ATTR) instanceof Long id ? java.util.Optional.of(id) : java.util.Optional.empty();
  }

  /** 当前请求的用户 ID；没有登录时抛 UNAUTHORIZED（过滤器已拦截，正常不会走到这里）。 */
  public static long id() {
    RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
    Object v = attrs == null ? null : attrs.getAttribute(ATTR, RequestAttributes.SCOPE_REQUEST);
    if (v instanceof Long id) return id;
    throw new BizException("UNAUTHORIZED", "未登录");
  }
}
