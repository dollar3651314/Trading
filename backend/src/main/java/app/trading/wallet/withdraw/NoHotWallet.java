package app.trading.wallet.withdraw;

import app.trading.wallet.api.HotWalletApi;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 第 0 批过渡：自带热钱包已经去掉（付款由 TopUP 做），这里没有热钱包余额。
 * 后台对账页的热钱包卡片为空；提现自动审批的"热钱包余额够"一项（R12）因为查不到余额判"不通过"，提现转人工审核（保守）。
 * 第 1 批换成支付接口层提供的付款余额。
 */
@Component
class NoHotWallet implements HotWalletApi {

  @Override
  public List<Balance> hotBalances() {
    return List.of();
  }
}
