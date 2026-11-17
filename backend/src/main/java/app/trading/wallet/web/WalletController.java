package app.trading.wallet.web;

import app.trading.common.error.BizException;
import app.trading.common.web.CurrentUser;
import app.trading.payment.api.PayNetwork;
import app.trading.payment.api.PaymentApi;
import app.trading.wallet.WalletProperties;
import app.trading.wallet.deposit.DepositService;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 充值接口（第 1 批，原型 P1）：先选网络，第一次选某条网络时才向钱包提供方要地址（需求 02 第二节、AC-W1）；充值记录。
 * <ul>
 *   <li>{@code GET /api/wallet/networks}：开着的网络和已经生成的地址（不访问提供方，很快）。后台关掉的网络不出现</li>
 *   <li>{@code POST /api/wallet/addresses/{network}}：要这条网络的地址（已有直接给；没有就向提供方要，通常 5 秒内）；提供方不通返回 503 WALLET_UNAVAILABLE</li>
 *   <li>{@code GET /api/wallet/deposits}：最近 50 条充值记录（只有已入账、已冲正：TopUP 只通知已确认的到账，低于 1 USDT 的不通知）</li>
 * </ul>
 */
@RestController
class WalletController {

  private static final Logger log = LoggerFactory.getLogger(WalletController.class);

  /** 一条网络：address 为空表示还没生成过 */
  record NetworkView(String network, String address, String minDeposit) {}

  record AddressView(String network, String address, String minDeposit) {}

  /** 充值记录。status：credited 已到账 / reversed 已冲正；time 是到账时间 */
  record DepositView(String id, String network, String txHash, String amount, String status, String time) {}

  private final PaymentApi payments;
  private final DepositService deposits;
  private final WalletProperties props;

  WalletController(PaymentApi payments, DepositService deposits, WalletProperties props) {
    this.payments = payments;
    this.deposits = deposits;
    this.props = props;
  }

  private String min() {
    return props.minDeposit().stripTrailingZeros().toPlainString();
  }

  @GetMapping("/api/wallet/deposits")
  List<DepositView> deposits() {
    return deposits.history(CurrentUser.id(), 50).stream().map(d -> new DepositView(String.valueOf(d.no()), d.chain().name(), d.txHash(),
        d.amount().stripTrailingZeros().toPlainString(), DepositService.apiStatus(d.status()), d.createdAt().toInstant(ZoneOffset.UTC).toString())).toList();
  }

  @GetMapping("/api/wallet/networks")
  List<NetworkView> networks() {
    long uid = CurrentUser.id();
    List<NetworkView> out = new ArrayList<>();
    for (PayNetwork n : payments.openNetworks()) out.add(new NetworkView(n.name(), payments.existingAddress(uid, n).orElse(null), min()));
    return out;
  }

  /** 不算"交易和资金写操作"（不动钱），不加按客户限流；按 IP 的通用限流照样有 */
  @PostMapping("/api/wallet/addresses/{network}")
  AddressView address(@PathVariable String network) {
    PayNetwork n = PayNetwork.parse(network);
    if (n == null) throw new BizException("NOT_FOUND", "没有这条网络");
    try {
      return new AddressView(n.name(), payments.address(CurrentUser.id(), n), min());
    } catch (PaymentApi.Unavailable e) {
      log.warn("拿不到充值地址 network={}：{}", n, e.getMessage());
      throw new BizException("WALLET_UNAVAILABLE", "暂时无法获取充值地址，请稍后再试");
    }
  }
}
