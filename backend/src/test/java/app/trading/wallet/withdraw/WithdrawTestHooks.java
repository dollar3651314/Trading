package app.trading.wallet.withdraw;

/** 其他包的测试（支付接口层）调用提现服务里包内可见的方法。只在测试里用。 */
public final class WithdrawTestHooks {

  private WithdrawTestHooks() {}

  /** 加一个已经生效的白名单地址，返回白名单 ID */
  public static long activeAddress(org.springframework.context.ApplicationContext ctx, org.springframework.jdbc.core.JdbcTemplate jdbc, long uid, String network,
      String address) {
    WhitelistRow r = ctx.getBean(WhitelistService.class).add(uid, network, address, "测试");
    jdbc.update("UPDATE wallet_whitelist SET active_at = created_at - INTERVAL 1 HOUR WHERE id = ?", r.id);
    return r.id;
  }

  /** 提交提现，返回内部 id */
  public static long submit(WithdrawService s, long uid, long whitelistId, String amount) {
    return s.submit(uid, String.valueOf(whitelistId), amount).id;
  }

  public static void advance(WithdrawService s) {
    s.advance();
  }

  public static void track(WithdrawService s) {
    s.track();
  }
}
