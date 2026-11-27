package app.trading.account.api;

/**
 * 事件：这个客户的提现被挡住了（第 4 批审查 🔴4a-1）——提现冻结（提现密码 30 天锁 2 次、后台 / 网页 / 机器人申请冻结）或者整个账户被冻结。
 * 账户模块在冻结的事务里发出；钱包模块在事务提交以后处理在途的提现（{@code wallet.withdraw.WithdrawFreezeHandler}：冷静期、待审核、
 * 大额延迟中的取消并退回余额；已审核待放款的暂停、不交钱包提供方）。用事件是因为钱包依赖账户，账户不能反过来调钱包。
 *
 * @param reason 给提现事件记录看的原因（"账户已冻结" / "提现已冻结：…"）
 */
public record WithdrawBlocked(long userId, String reason) {}
