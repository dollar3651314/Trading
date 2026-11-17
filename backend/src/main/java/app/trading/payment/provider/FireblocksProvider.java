package app.trading.payment.provider;

/**
 * Fireblocks：只留类名和配置位置（{@code trading.payment.fireblocks.*}），不写实现（TR4：Amos 开账号后另立一版接入，接前按 C139 实测）。
 * 后台钱包提供方页显示"未接入"。
 */
public final class FireblocksProvider extends NotConnectedProvider {

  public FireblocksProvider() {
    super("FIREBLOCKS", "Fireblocks");
  }
}
