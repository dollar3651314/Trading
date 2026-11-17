package app.quote.open;

import app.quote.catalog.QuoteSymbol;
import app.quote.catalog.SymbolCatalog;
import app.quote.feed.QuoteRouter;
import app.quote.feed.QuoteSource;
import app.quote.license.LicenseRegistry;
import app.quote.license.QuoteLicense.Use;
import app.quote.stream.QuoteStream;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 两道闸（需求 03 页第四节，TR3）：
 * <ol>
 *   <li>对外开放总开关（{@link OpenSettings#gateOpen}，默认关）：关的时候外部 Key 一律 403、WebSocket 握手被拒；内部测试 Key 不受限制；</li>
 *   <li>转售授权：外部 Key 只能拿到"现在在用的报价源已登记转售授权"的品种（列表、报价、订阅、K 线都一样）；切到没授权的备源时这个品种对外
 *       "暂无报价"，不输出未授权的价格。和交易用途一样，授权检查在测试环境（报价模式不是 live，且没强制打开）不生效，页面照旧标"示例行情"。</li>
 * </ol>
 * 每个 Key 还可以限定可用品种（空 = 全部）。
 */
@Component
public class Entitlements {

  private final OpenSettings settings;
  private final LicenseRegistry licenses;
  private final SymbolCatalog catalog;
  private final ObjectProvider<QuoteRouter> router;
  private final QuoteStream stream;

  Entitlements(OpenSettings settings, LicenseRegistry licenses, SymbolCatalog catalog, ObjectProvider<QuoteRouter> router, QuoteStream stream) {
    this.settings = settings;
    this.licenses = licenses;
    this.catalog = catalog;
    this.router = router;
    this.stream = stream;
  }

  /** 闸 1：这个 Key 现在能不能用（停用的另算 401） */
  public boolean gatePasses(ApiKeys.Key k) {
    return !k.external() || settings.gateOpen();
  }

  /** Key 自己的可用品种范围（不看授权） */
  public boolean inScope(ApiKeys.Key k, String symbol) {
    return catalog.get(symbol).isPresent() && (k.symbols().isEmpty() || k.symbols().contains(symbol));
  }

  /** 闸 2：这一路报价（报价源 feed）能不能给这个 Key。内部测试 Key 不看转售授权 */
  public boolean resaleAllowed(ApiKeys.Key k, String feed, String symbol) {
    if (!k.external()) return true;
    if (!licenses.enforced()) return true;
    return feed != null && licenses.allowed(feed, symbol, Use.RESALE);
  }

  /** 这个品种现在在用的报价源；没有为空 */
  public Optional<String> currentFeed(String symbol) {
    QuoteRouter r = router.getIfAvailable();
    Optional<String> f = r == null ? Optional.empty() : r.current(symbol).map(QuoteSource::feed);
    return f.isPresent() ? f : stream.latest(symbol).map(app.quote.protocol.QuoteFrame::feed);
  }

  /** 这个 Key 现在能拿到的品种（列表接口、订阅用）：在范围内，并且（外部 Key）现在在用的源有转售授权 */
  public boolean visible(ApiKeys.Key k, String symbol) {
    return inScope(k, symbol) && resaleAllowed(k, currentFeed(symbol).orElse(null), symbol);
  }

  public List<QuoteSymbol> visibleSymbols(ApiKeys.Key k) {
    return catalog.all().stream().filter(s -> visible(k, s.code())).toList();
  }
}
