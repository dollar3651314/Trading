package app.trading.trading.ledger;

import java.math.BigDecimal;

/** trading_account 的一行。 */
record AccountRow(long id, long userId, BigDecimal balance, BigDecimal withdrawFrozen, BigDecimal orderFrozen) {

  BigDecimal get(app.trading.trading.api.Bucket b) {
    return switch (b) {
      case BALANCE -> balance;
      case WITHDRAW_FROZEN -> withdrawFrozen;
      case ORDER_FROZEN -> orderFrozen;
    };
  }
}
