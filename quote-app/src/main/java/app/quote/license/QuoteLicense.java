package app.quote.license;

import java.time.LocalDate;

/**
 * 一行报价源授权登记（quote_source_license）："报价商 × 品种"的三项授权（技术方案 08 页，需求 03 页第四节）。
 *
 * @param feed 报价商：alltick / itick / binance
 * @param symbol 平台品种代码；{@code *} 表示这家的全部品种（具体品种的那一行优先）
 * @param trading 交易用途（拿来做成交价）
 * @param display 登录后展示
 * @param resale 转售（给外部 Key）
 * @param note 备注（不写合同原文和价格）
 */
public record QuoteLicense(String feed, String symbol, Grant trading, Grant display, Grant resale, String note) {

  public static final String ALL = "*";

  /**
   * 一项授权。
   *
   * @param granted 有没有
   * @param from 起始日（含，UTC）；为空表示不限
   * @param until 到期日（含，UTC）；为空表示不限
   */
  public record Grant(boolean granted, LocalDate from, LocalDate until) {
    public static final Grant NONE = new Grant(false, null, null);

    /** 这一天是否有效 */
    public boolean validOn(LocalDate day) {
      return granted && (from == null || !day.isBefore(from)) && (until == null || !day.isAfter(until));
    }
  }

  /** 三项里的哪一项 */
  public enum Use {
    TRADING,
    DISPLAY,
    RESALE
  }

  public Grant grant(Use use) {
    return switch (use) {
      case TRADING -> trading;
      case DISPLAY -> display;
      case RESALE -> resale;
    };
  }
}
