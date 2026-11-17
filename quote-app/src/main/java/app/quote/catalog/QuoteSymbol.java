package app.quote.catalog;

import java.math.BigDecimal;

/**
 * 报价品种（quote_symbol 一行）：只有报价要用的字段。交易参数（手数、杠杆、加点等）在 trading-app。
 *
 * @param digits 报价小数位（报价源的价格按它取整：买价向下、卖价向上）
 * @param simStartPrice 模拟起始价（只在模拟报价源下使用）；没填为空
 */
public record QuoteSymbol(String code, String category, int digits, BigDecimal simStartPrice) {}
