package app.trading.payment.provider;

/**
 * Cobo：只留类名和配置位置（{@code trading.payment.cobo.*}），不写实现（TR4：Amos 开账号后另立一版接入，接前按 C139 实测）。
 * 后台钱包提供方页显示"未接入"。
 */
public final class CoboProvider extends NotConnectedProvider {

  public CoboProvider() {
    super("COBO", "Cobo");
  }
}
