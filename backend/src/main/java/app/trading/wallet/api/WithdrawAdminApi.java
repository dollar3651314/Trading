package app.trading.wallet.api;

/** 出金审核的后台操作（页面、权限、二次确认和审计日志在 S16）。每次操作都记提现事件。 */
public interface WithdrawAdminApi {

  /** 审核通过：< 3,000 直接进入待上链；≥ 3,000 延迟 6 小时。只能审核"待审核"的提现 */
  void approve(long withdrawalId, long adminId);

  /** 同上；firstChecked：首笔人工核实已做（第 4 批：没邮箱的 TG 客户的首笔，不确认就不能通过，抛 FIRST_CHECK_REQUIRED） */
  void approve(long withdrawalId, long adminId, boolean firstChecked);

  /** 驳回：必须填原因，冻结金额退回 */
  void reject(long withdrawalId, long adminId, String reason);

  /** 大额延迟期内紧急取消：资金退回（AC-21） */
  void emergencyCancel(long withdrawalId, long adminId, String reason);

  /**
   * 冻结时暂停的已审核待放款提现（第 4 批 4b 审查 🟡1：解除冻结后不自动继续）。sentBefore：交过钱包提供方（可能已在付款），不能直接取消退回
   */
  record Held(long id, long publicNo, java.math.BigDecimal amount, String network, String address, java.time.LocalDateTime heldAt, String reason,
      boolean sentBefore) {}

  /** 这个客户暂停中的提现 */
  java.util.List<Held> held(long userId);

  /** 继续放款：客户还冻结着时抛 WITHDRAW_FROZEN；不在暂停中抛 WITHDRAW_NOT_HELD */
  void releaseHeld(long withdrawalId, long adminId, String reason);

  /** 取消并退回：交过钱包提供方的抛 WITHDRAW_SENT_BEFORE；不在暂停中抛 WITHDRAW_NOT_HELD */
  void cancelHeld(long withdrawalId, long adminId, String reason);
}
