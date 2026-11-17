package app.trading.payment.api;

/** 三条链（名字和钱包模块、客户端一致）。只收 USDT（TQ-9）。 */
public enum PayNetwork {
  ERC20, BSC, TRC20;

  /** 名字不对返回 null */
  public static PayNetwork parse(String s) {
    if (s == null) return null;
    for (PayNetwork n : values()) if (n.name().equals(s)) return n;
    return null;
  }
}
