package app.trading.trading.order;

import app.trading.trading.api.FundsApi;
import app.trading.trading.api.LedgerApi;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/** {@link FundsApi} 的实现，口径同 {@link Funds#available}。 */
@Component
class FundsQuery implements FundsApi {

  private final Funds funds;
  private final LedgerApi ledger;
  private final OrderMapper mapper;

  FundsQuery(Funds funds, LedgerApi ledger, OrderMapper mapper) {
    this.funds = funds;
    this.ledger = ledger;
    this.mapper = mapper;
  }

  @Override
  public BigDecimal withdrawable(long userId) {
    return funds.available(ledger.snapshot(userId), mapper.openPositions(userId));
  }
}
