package app.trading.wallet.deposit;

import app.trading.common.e2e.E2eGuard;
import app.trading.wallet.Chain;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 第 0 批过渡：除端到端测试外没有充值地址来源（自带钱包的地址派生已去掉，第 1 批接 TopUP）。
 * 充值地址接口因此返回"充值地址暂不可用"（WALLET_NOT_READY），客户端显示"充值暂未开放"。
 */
@Component
@Profile("!" + E2eGuard.PROFILE)
class NoDepositAddresses implements DepositAddressBook {

  @Override
  public Optional<String> addressOf(long userId, Chain chain) {
    return Optional.empty();
  }

  @Override
  public Optional<Long> userOf(Chain chain, String address) {
    return Optional.empty();
  }
}
