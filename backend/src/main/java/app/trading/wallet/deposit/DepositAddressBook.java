package app.trading.wallet.deposit;

import app.trading.wallet.Chain;
import java.util.Optional;

/**
 * 充值地址簿（第 0 批过渡）：用户的充值地址从哪里来、收款地址属于哪个用户。
 * <p>
 * 自带钱包的地址派生（xpub）已经去掉；第 1 批由支付接口层（TopUP 开放 API 的"每用户每链一个永久地址"）实现。
 * 第 0 批只有端到端测试的替身实现（{@link E2eAddressBook}，profile e2e）；没有实现时充值地址接口返回"暂不可用"，客户端显示"充值暂未开放"。
 */
public interface DepositAddressBook {

  /** 用户在这条链上的充值地址；还没有时为空 */
  Optional<String> addressOf(long userId, Chain chain);

  /** 收款地址属于哪个用户；不是我们的地址时为空 */
  Optional<Long> userOf(Chain chain, String address);
}
