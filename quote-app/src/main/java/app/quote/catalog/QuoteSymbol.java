package app.quote.catalog;

import java.math.BigDecimal;

/**
 * 报价品种（quote_symbol 一行）：只有报价要用的字段。交易参数（手数、杠杆、加点等）在 trading-app。
 *
 * @param digits 报价小数位（报价源的价格按它取整：买价向下、卖价向上）
 * @param simStartPrice 模拟起始价（只在模拟报价源下使用）；没填为空
 * @param nameZh 中文名（第 3 批对外品种列表用，trading-app 同步过来）；没有为空
 * @param nameEn 英文名；没有为空
 */
public record QuoteSymbol(String code, String category, int digits, BigDecimal simStartPrice, String nameZh, String nameEn) {

  public QuoteSymbol(String code, String category, int digits, BigDecimal simStartPrice) {
    this(code, category, digits, simStartPrice, null, null);
  }
}
