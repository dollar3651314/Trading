package app.trading.wallet.withdraw;

import app.trading.wallet.Chain;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** 每条链用哪个付款器。启动时三条链都注册支付接口层（PaymentWithdrawSender）；测试注册替身。没有付款器的链，提现停在"已审核待放款"（不退款）。 */
@Component
public class WithdrawSenders {

  private final Map<Chain, WithdrawSender> senders = new ConcurrentHashMap<>();

  public void register(Chain chain, WithdrawSender sender) {
    senders.put(chain, sender);
  }

  public void remove(Chain chain) {
    senders.remove(chain);
  }

  Optional<WithdrawSender> get(Chain chain) {
    return Optional.ofNullable(senders.get(chain));
  }
}
