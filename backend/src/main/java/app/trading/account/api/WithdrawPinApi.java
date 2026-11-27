package app.trading.account.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提现密码和冻结提现（TG 机器人第 4 批，需求文档 TG机器人-03 页第 0、4、6 节，08 页第 2、3 节，TB-5、TB-14）。
 * <ul>
 *   <li>6 位数字，<b>随机键盘输入</b>：服务器给一次性的键盘（0～9 的位置每次打乱），网页只把"按了第几个键"交回来，服务器换算成数字——
 *       网络、页面日志里都没有密码本身，同一个键位下次代表别的数字。键盘 5 分钟有效、只能用一次、只给发它的那个客户用。</li>
 *   <li>连续输错 5 次锁 24 小时，期满清零；输对清零。<b>30 天内被锁 2 次 → 冻结提现、转人工</b>。</li>
 *   <li>第一次设、修改、忘记后重设都是安全事件（作废全部网页会话；24 小时不能提现，重设是 48 小时）。</li>
 *   <li>冻结提现只停提现、加白名单、改提现密码，交易照常；只能人工解冻。</li>
 * </ul>
 */
public interface WithdrawPinApi {

  /** 一次输入：键盘编号 + 按了第几个键（0～9，按键盘上的顺序），6 个 */
  record PinInput(String keypadId, List<Integer> positions) {}

  /**
   * @param lockedUntil 锁定到期（没锁时为空）
   * @param frozenSource 冻结提现的来源（没冻结时为空）：PIN_LOCKS / WEB_REQUEST / BOT_SELF / DISPUTE / RECOVERY
   */
  record Status(boolean set, LocalDateTime lockedUntil, String frozenSource, LocalDateTime frozenAt) {

    public boolean withdrawFrozen() {
      return frozenSource != null;
    }
  }

  Status status(long userId);

  /**
   * 核对提现密码（加白名单、提现前调）。不对时计数，抛 {@code PIN_WRONG}（还能试几次在 message 里）；锁着时抛 {@code PIN_LOCKED}；
   * 没设过抛 {@code PIN_NOT_SET}；冻结提现时抛 {@code WITHDRAW_FROZEN}。输错计数在它自己的事务里提交（调用方后面出错回滚也不会把计数回滚掉）
   */
  void verify(long userId, PinInput pin);

  /** 冻结提现（已经冻结的不变） */
  void freezeWithdraw(long userId, String source, String reason);

  /** 人工解冻（后台，写审计由调用方做）；没冻结时什么也不做，返回原来的来源 */
  String unfreezeWithdraw(long userId);
}
