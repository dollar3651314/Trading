package app.trading.payment.provider;

import app.trading.payment.api.PayNetwork;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 还没接入的提供方：每个动作都报"未接入"，不能被切换成当前提供方。 */
public abstract class NotConnectedProvider implements PaymentProvider {

  private final String code;
  private final String name;

  protected NotConnectedProvider(String code, String name) {
    this.code = code;
    this.name = name;
  }

  @Override
  public String code() {
    return code;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public boolean connected() {
    return false;
  }

  private UnsupportedOperationException no() {
    return new UnsupportedOperationException(name + " 未接入");
  }

  @Override
  public void ensureCustomer(String customerId) {
    throw no();
  }

  @Override
  public String address(String customerId, PayNetwork network) {
    throw no();
  }

  @Override
  public Submitted submitPayout(String idemKey, String merchantRef, String customerId, PayNetwork network, String address, BigDecimal amount) {
    throw no();
  }

  @Override
  public Optional<PayoutView> payout(String ref) {
    throw no();
  }

  @Override
  public BalanceView balance() {
    throw no();
  }

  @Override
  public List<DepositView> deposits(String customerId) {
    throw no();
  }

  @Override
  public Optional<Event> parseCallback(Map<String, String> headers, String body) {
    throw new BadSignature(name + " 未接入");
  }
}
