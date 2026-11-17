package app.quote.feed;

/**
 * 报价源的"交易用途"授权（技术方案 08 页，TQ-11 A），给 {@link QuoteRouter} 选源和给报价标 {@code tradable} 用。实现是 {@code license.LicenseRegistry}。
 */
public interface TradingLicenses {

  /** 没有启用授权检查（测试环境）：所有源都当作已授权 */
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

  /** 是否启用授权检查（默认只在报价模式 live，即生产启用） */
  boolean enforced();

  /** 这家报价商这个品种今天有没有"交易用途"授权；没启用检查时一律 true */
  boolean tradingUse(String feed, String symbol);
}
