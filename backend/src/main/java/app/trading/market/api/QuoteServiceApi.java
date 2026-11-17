package app.trading.market.api;

import java.util.Optional;

/**
 * 后台"报价服务"菜单（第 3 批，原型 P8～P11）调报价服务（quote-app）的内部接口 {@code /internal/open/**}、{@code /internal/licenses}（带内部 Key）。
 * 只是转发：外部客户、Key、限额、用量、授权登记、总开关都存在报价服务自己的库里；审计、原因必填、只有超管能开总开关在后台（admin 模块）做。
 */
public interface QuoteServiceApi {

  /** 报价服务的原样返回 */
  record Response(int status, String contentType, String body) {}

  /** 没连报价服务（{@code trading.market.source} 不是 remote）时为空；连不上抛 {@link IllegalStateException} */
  Optional<Response> call(String method, String pathWithQuery, String jsonBody);

  QuoteServiceApi NONE = (m, p, b) -> Optional.empty();
}
