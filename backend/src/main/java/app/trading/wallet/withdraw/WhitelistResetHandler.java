package app.trading.wallet.withdraw;

import app.trading.account.api.AccountRecovered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 找回申请执行时清空白名单（第 4 批 4b，08 页第 1、3 节：换绑、没邮箱人工重设提现密码之后白名单清空）。
 * 同步监听：和账户模块的执行在同一个事务里，要么都成、要么都回滚。在途的提现不在这里处理（执行时有安全事件，48 小时不能提现；
 * 证明阶段已经冻结过提现，在途的那时已取消或暂停）。
 */
@Component
class WhitelistResetHandler {

  private static final Logger log = LoggerFactory.getLogger(WhitelistResetHandler.class);
  private final WithdrawMapper mapper;

  WhitelistResetHandler(WithdrawMapper mapper) {
    this.mapper = mapper;
  }

  @EventListener
  void on(AccountRecovered e) {
    int n = mapper.deleteAllWhitelist(e.userId());
    log.warn("客户 {} 找回申请 #{}（{}）执行：清空白名单 {} 个地址", e.userId(), e.recoveryId(), e.kind(), n);
  }
}
