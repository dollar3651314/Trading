package app.quote.feed;

/**
 * 报价源的"交易用途"授权（技术方案 08 页，TQ-11 A），给 {@link QuoteRouter} 选源和给报价标 {@code tradable} 用。实现是 {@code license.LicenseRegistry}。
 */
public interface TradingLicenses {

  /** 没有启用授权检查：所有源都当作已授权（所有环境默认就是这样，Amos 2026-11-17） */
  TradingLicenses OFF = new TradingLicenses() {
    @Override
    public boolean enforced() {
      return false;
    }

    @Override
    public boolean tradingUse(String feed, String symbol) {
      return true;
    }
  };

  /** 是否启用授权检查：只有 FX_QUOTE_LICENSE_ENFORCE=true 才启用，所有环境默认关（Amos 2026-11-17） */
  boolean enforced();

  /** 这家报价商这个品种今天有没有"交易用途"授权；没启用检查时一律 true */
  boolean tradingUse(String feed, String symbol);
}
