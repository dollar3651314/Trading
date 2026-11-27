-- TG 机器人第 4 批 A"资金安全：提现密码和 TG 提现规则"（需求文档 TG机器人-03 页第 0、3、4 节，08 页第 3 节，决定清单 TB-5、TB-14、TB-20、TB-21、TB-22）。

-- ① 提现密码：只存 BCrypt 哈希；连续输错计数、锁定到期（错 5 次锁 24 小时，期满清零）
CREATE TABLE account_withdraw_pin (
  user_id       BIGINT       NOT NULL,
  pin_hash      VARCHAR(100) NOT NULL,
  failed_count  INT          NOT NULL DEFAULT 0 COMMENT '连续输错次数，输对或锁定后清零',
  locked_until  DATETIME(3)  NULL COMMENT '锁定到期（UTC）',
  created_at    DATETIME(3)  NOT NULL,
  updated_at    DATETIME(3)  NOT NULL,
  PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提现密码（TG 客户加白名单、提现要输）';

-- 每次锁定记一行：30 天内锁 2 次 → 冻结提现、转人工
CREATE TABLE account_withdraw_pin_lock (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  user_id    BIGINT      NOT NULL,
  locked_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_account_withdraw_pin_lock_user (user_id, locked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提现密码锁定记录';

-- ② 冻结提现（和"冻结账户"不同：只停提现、加白名单、改提现密码；交易、查询照常）。只能人工解冻（第 4 批 C 再加网页入口、机器人自助冻结）
CREATE TABLE account_withdraw_freeze (
  user_id     BIGINT       NOT NULL,
  source      VARCHAR(16)  NOT NULL COMMENT 'PIN_LOCKS 30 天锁 2 次 / WEB_REQUEST 网页申请 / BOT_SELF 机器人里自己冻结 / DISPUTE 换绑争议 / RECOVERY 换绑证明阶段',
  reason      VARCHAR(500) NULL,
  created_at  DATETIME(3)  NOT NULL,
  PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='冻结提现（只能人工解冻）';

-- ③ 提现单：按哪套规则（WEB 原规则 / TG 规则：一律人工、每天额度）、从哪种会话提交（WEB / BOT）、要不要首笔人工核实
ALTER TABLE wallet_withdrawal
  ADD COLUMN rule_type VARCHAR(8) NOT NULL DEFAULT 'WEB' COMMENT 'WEB 原规则 / TG TG 规则（一律人工审核）' AFTER status,
  ADD COLUMN source VARCHAR(8) NOT NULL DEFAULT 'WEB' COMMENT '提交入口：WEB 网页登录的会话 / BOT 从机器人进入的会话' AFTER rule_type,
  ADD COLUMN first_check TINYINT(1) NOT NULL DEFAULT 0 COMMENT '没邮箱的 TG 客户的首笔：审核前要人工核实（08 页第 0 节）' AFTER source,
  ADD COLUMN first_checked_by BIGINT NULL COMMENT '确认已核实的管理员' AFTER first_check;

-- ④ 白名单：TG 客户加地址、生效前 1 小时、生效时各通知一次
ALTER TABLE wallet_whitelist
  ADD COLUMN notified_soon_at DATETIME(3) NULL COMMENT '已发"1 小时后生效"',
  ADD COLUMN notified_active_at DATETIME(3) NULL COMMENT '已发"已生效"';

-- ⑤ TG 客户每天提现额度（USDT，网页和机器人合并算）：没邮箱 500、有邮箱 2,000（后台系统配置可改，上限 2,000）
INSERT INTO common_config (config_key, value) VALUES ('telegram.withdraw.dailyLimit', '500'), ('telegram.withdraw.dailyLimitEmail', '2000');

-- ⑥ 通知（安全提醒，Telegram 里不能关）
INSERT INTO notify_template (code, audience, kind, name_zh, name_en, title_zh, title_en, body_zh, body_en, variables, channels) VALUES
('WITHDRAW_PIN_SET', 'USER', 'system', '设置了提现密码（安全提醒）', 'Withdrawal PIN set (security alert)', '已设置提现密码', 'Withdrawal PIN set',
  '你的账户刚设置了提现密码。为了安全，24 小时内不能提现。如果不是你本人操作，请立即申请冻结提现并联系客服。',
  'A withdrawal PIN was just set on your account. Withdrawals are paused for 24 hours. If this was not you, freeze withdrawals now and contact support.', '', 'INBOX,PUSH,EMAIL,TELEGRAM'),
('WITHDRAW_PIN_CHANGED', 'USER', 'system', '改了提现密码（安全提醒）', 'Withdrawal PIN changed (security alert)', '提现密码已修改', 'Withdrawal PIN changed',
  '你的提现密码刚被修改。为了安全，{hours} 小时内不能提现。如果不是你本人操作，请立即申请冻结提现并联系客服。',
  'Your withdrawal PIN was just changed. Withdrawals are paused for {hours} hours. If this was not you, freeze withdrawals now and contact support.', 'hours', 'INBOX,PUSH,EMAIL,TELEGRAM'),
('WITHDRAW_PIN_LOCKED', 'USER', 'system', '提现密码被锁（安全提醒）', 'Withdrawal PIN locked (security alert)', '提现密码输错太多次，已锁定', 'Withdrawal PIN locked',
  '提现密码连续输错 5 次，已锁定 24 小时（到 {until}）。如果不是你本人在输，请立即申请冻结提现。',
  'Wrong withdrawal PIN 5 times in a row. It is locked for 24 hours (until {until}). If this was not you, freeze withdrawals now.', 'until', 'INBOX,PUSH,EMAIL,TELEGRAM'),
('WITHDRAW_FROZEN', 'USER', 'system', '提现已冻结（安全提醒）', 'Withdrawals frozen (security alert)', '提现已冻结', 'Withdrawals frozen',
  '你的账户提现已冻结：{reason}。交易、查询不受影响；解冻要人工核对本人，请联系客服。',
  'Withdrawals on your account are frozen: {reason}. Trading is not affected. Contact support to verify your identity and unfreeze.', 'reason', 'INBOX,PUSH,EMAIL,TELEGRAM'),
('WHITELIST_ADDED', 'USER', 'system', '加了白名单地址（安全提醒）', 'Whitelist address added (security alert)', '加了新的提现地址', 'New withdrawal address added',
  '你的账户加了新的提现地址 {network} {address}，{activeAt} 生效。如果不是你本人操作，请立即申请冻结提现。',
  'A new withdrawal address {network} {address} was added and becomes active at {activeAt}. If this was not you, freeze withdrawals now.', 'network,address,activeAt', 'INBOX,PUSH,EMAIL,TELEGRAM'),
('WHITELIST_SOON', 'USER', 'system', '白名单地址 1 小时后生效（安全提醒）', 'Whitelist address active soon (security alert)', '新的提现地址 1 小时后生效', 'New address active in 1 hour',
  '新的提现地址 {network} {address} 1 小时后（{activeAt}）生效。如果不是你本人加的，请立即申请冻结提现。',
  'The new withdrawal address {network} {address} becomes active in 1 hour ({activeAt}). If you did not add it, freeze withdrawals now.', 'network,address,activeAt', 'INBOX,PUSH,EMAIL,TELEGRAM'),
('WHITELIST_ACTIVE', 'USER', 'system', '白名单地址已生效（安全提醒）', 'Whitelist address active (security alert)', '新的提现地址已生效', 'New address is active',
  '提现地址 {network} {address} 已生效，现在可以提到这个地址。如果不是你本人加的，请立即申请冻结提现。',
  'The withdrawal address {network} {address} is now active. If you did not add it, freeze withdrawals now.', 'network,address', 'INBOX,PUSH,EMAIL,TELEGRAM');

-- ⑧ 后台人工解除提现冻结时发给告警邮箱（FX_ALERT_EMAILS）的邮件（第 4 批审查 🟡：权限 withdraw:unfreeze、管理员密码、告警邮件），防止有人悄悄解冻
INSERT INTO notify_template (code, audience, kind, name_zh, name_en, title_zh, title_en, body_zh, body_en, variables, channels) VALUES
('OPS_WITHDRAW_UNFREEZE', 'STAFF', NULL, '人工解除提现冻结', 'Withdrawal freeze lifted manually',
  '客户 {customerId} 的提现冻结已被人工解除', 'Withdrawal freeze lifted for customer {customerId}',
  '客户 {customerId} 的提现冻结（来源 {source}）已被人工解除。\n操作人：{admin}\n原因：{reason}\n时间（UTC）：{at}\n如果不是你们安排的操作，请立即在后台重新冻结这个客户并查看审计日志。',
  'The withdrawal freeze on customer {customerId} (source {source}) was lifted manually.\nBy: {admin}\nReason: {reason}\nAt (UTC): {at}\nIf this was not planned, freeze the customer again in the admin console and check the audit log.',
  'customerId,source,admin,reason,at', 'EMAIL');

-- ⑦ 新的安全事件类型（只增不改，类型最多 16 个字符）：PIN_SET 第一次设提现密码 / PIN_CHANGE 改提现密码 / PIN_RESET 忘记后重设 / KYC_FIRST 第一次提交 KYC（TG 客户）
ALTER TABLE account_security_event MODIFY type VARCHAR(16) NOT NULL COMMENT 'PASSWORD_CHANGE / PASSWORD_RESET / EMAIL_CHANGE / FREEZE / UNFREEZE / PIN_SET / PIN_CHANGE / PIN_RESET / KYC_FIRST';
