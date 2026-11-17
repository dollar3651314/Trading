package app.trading.wallet;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 钱包参数（application.yml 的 trading.wallet），值由 负责人 填进服务器环境变量，仓库里只有变量名（底线）。
 *
 * @param minDeposit 最小充值金额，低于它的不入账（负责人 确认 1 USDT，充值方案 D4）
 * @param chains 每条链的 USDT 合约、精度、确认数（第 0 批过渡：自带钱包的 xpub、节点地址已去掉，第 1 批换成支付接口层）
 */
@ConfigurationProperties("trading.wallet")
public record WalletProperties(@DefaultValue("1") BigDecimal minDeposit, Map<Chain, ChainConfig> chains) {

  /**
   * 一条链的配置。
   *
   * @param usdtContract 只认这个 USDT 合约（修复 F3）；没有配置时这条链不扫块
   * @param usdtDecimals USDT 精度：ERC20、TRC20 是 6；BSC 是 18，要上链实测确认
   * @param confirmations 入账需要的确认数（ETH 12、BSC 15、TRON 19，沿用现有系统）
   * @param blockSeconds 出块间隔（秒，可以是小数：BSC 2026-10 实测约 0.45 秒），用于给客户估算到账时间、判断扫块落后多久
   * @param scanBatch 每次最多扫多少个区块
   * @param startBlock 第一次扫块从哪个区块开始；不填就从当前区块开始
   */
  public record ChainConfig(String usdtContract, @DefaultValue("6") int usdtDecimals, int confirmations, @DefaultValue("3") double blockSeconds,
      @DefaultValue("200") int scanBatch, Long startBlock) {

    public boolean scanReady() {
      return usdtContract != null && !usdtContract.isBlank();
    }
  }

  public ChainConfig chain(Chain c) {
    return chains == null ? null : chains.get(c);
  }
}
