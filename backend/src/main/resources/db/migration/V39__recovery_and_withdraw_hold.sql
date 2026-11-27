-- TG 机器人第 4 批 4b"找回和换绑、没邮箱忘记提现密码"（需求文档 TG机器人-08 页第 1、3 节，决定清单 TB-19、TB-14 v14：Amos 一人审核 + 执行等待期），
-- 以及 4a 复审的 🟡1（冻结时暂停的已审核待放款提现，解除冻结后不自动继续）。

-- ① 找回申请：REBIND 人工换绑（新 TG 在机器人里申请）/ PIN_RESET 没邮箱忘记提现密码（本人在网页申请）
CREATE TABLE account_recovery (
  id                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '申请编号（cancel-recovery 等用这个）',
  kind                  VARCHAR(16)  NOT NULL COMMENT 'REBIND 人工换绑 / PIN_RESET 没邮箱忘记提现密码',
  user_id               BIGINT       NOT NULL COMMENT '要找回的客户号',
  applicant_tg_id       BIGINT       NULL COMMENT 'REBIND：申请人的新 Telegram 用户编号；PIN_RESET 为空（本人）',
  applicant_tg_username VARCHAR(64)  NULL,
  status                VARCHAR(16)  NOT NULL COMMENT 'OBJECTION 反对期 / PROOF 证明和审核 / APPROVED 已批准等执行 / EXECUTED 已执行 / REJECTED 驳回 / CANCELLED 撤销',
  disputed              TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '原 TG 点过"不是我"（争议路径：任何撤销都转 Amos）',
  disputed_at           DATETIME(3)  NULL,
  dispute_pin_ok        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '点"不是我"后输对了提现密码（不冻结）',
  dispute_frozen        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '争议冻结过提现（没输、输错或选了直接冻结）',
  objection_ends_at     DATETIME(3)  NULL COMMENT 'REBIND 的 48 小时反对期到期',
  proof_note            VARCHAR(500) NULL COMMENT '批准时写的证明核对记录（第 0 节：来源地址 / 视频共享 App / P2P 等）',
  approved_by           BIGINT       NULL,
  approved_at           DATETIME(3)  NULL,
  execute_at            DATETIME(3)  NULL COMMENT '执行时间：REBIND 批准后 24 小时，PIN_RESET 48 小时',
  revoke_requested_by   VARCHAR(16)  NULL COMMENT '争议路径上原 TG 提出的撤销（转 Amos 决定，没决定前不执行）：ORIGINAL_TG',
  revoke_requested_at   DATETIME(3)  NULL,
  closed_by             VARCHAR(32)  NULL COMMENT '结束人：admin:<编号> / ORIGINAL_TG / system',
  close_reason          VARCHAR(500) NULL,
  closed_at             DATETIME(3)  NULL,
  created_at            DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_account_recovery_user (user_id, created_at),
  KEY idx_account_recovery_status (status),
  KEY idx_account_recovery_applicant (applicant_tg_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='找回申请（人工换绑、没邮箱忘记提现密码）';

CREATE TABLE account_recovery_event (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  recovery_id BIGINT       NOT NULL,
  action      VARCHAR(24)  NOT NULL COMMENT 'APPLY / OBJECT / PIN_OK / PIN_WRONG / DISPUTE_FREEZE / PROOF / APPROVE / REVOKE_REQUEST / KEEP / CANCEL / REJECT / EXECUTE / EXECUTE_FAILED',
  actor       VARCHAR(32)  NOT NULL COMMENT 'tg:<编号> / user:<客户号> / admin:<编号> / system',
  note        VARCHAR(500) NULL,
  created_at  DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_account_recovery_event (recovery_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='找回申请的经过';

-- 申请人在机器人里提交的次数（信息不对的也算）：同一个新 TG 每天最多 3 次
CREATE TABLE account_recovery_attempt (
  id              BIGINT      NOT NULL AUTO_INCREMENT,
  applicant_tg_id BIGINT      NOT NULL,
  created_at      DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_account_recovery_attempt (applicant_tg_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='找回申请提交次数';

-- 驳回后封申请人用过的 TG（长期）；IP 7 天要等网页入口（4c）
CREATE TABLE account_recovery_ban (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  kind        VARCHAR(8)  NOT NULL COMMENT 'TG / IP',
  value       VARCHAR(64) NOT NULL,
  until_at    DATETIME(3) NOT NULL,
  recovery_id BIGINT      NULL,
  created_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_account_recovery_ban (kind, value, until_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='找回入口封禁';

-- ② 4a 复审 🟡1：冻结时暂停的已审核待放款提现（解除冻结后不自动继续，后台逐笔选"继续放款"或"取消并退回"）；
--    交钱包提供方之前先认领（send_attempts + 1），交过的不能直接取消退回（防重复出金）
ALTER TABLE wallet_withdrawal
  ADD COLUMN held_at       DATETIME(3) NULL COMMENT '冻结时暂停的时间（只对已审核待放款）' AFTER first_checked_by,
  ADD COLUMN held_reason   VARCHAR(64) NULL AFTER held_at,
  ADD COLUMN send_attempts INT         NOT NULL DEFAULT 0 COMMENT '交钱包提供方认领过几次' AFTER held_reason;

-- ③ 4b 审查 🔴R2：提现冻结改成一个来源一行（主键 user_id + source）。原来一个客户一行、INSERT IGNORE，第二个来源的冻结被悄悄吞掉，
--    之后按来源解除（例如找回申请结束解除 RECOVERY）会把客户整个解冻。现在：有任何一行就是冻结；按来源解除只删那个来源；后台可以解除全部或选一个来源。
--    V38 已经合并进 staging（测试环境有没有跑过 V38 不能确定），所以不改 V38，在这里改主键（已有数据每个客户最多一行，不会冲突）
ALTER TABLE account_withdraw_freeze DROP PRIMARY KEY, ADD PRIMARY KEY (user_id, source),
  COMMENT = '冻结提现（一个来源一行，有任何一行就是冻结；只能人工解冻，找回申请结束时只解除它自己的 RECOVERY / DISPUTE）';

-- ④ 新的安全事件类型 REBIND（换绑 Telegram）
ALTER TABLE account_security_event MODIFY type VARCHAR(16) NOT NULL COMMENT 'PASSWORD_CHANGE / PASSWORD_RESET / EMAIL_CHANGE / FREEZE / UNFREEZE / PIN_SET / PIN_CHANGE / PIN_RESET / KYC_FIRST / REBIND';

-- ⑤ 通知模板（客户）：有人申请换绑（带「不是我」）、已批准等执行（带「撤销」）、已执行、已撤销 / 驳回；没邮箱忘记提现密码的申请、批准、执行
INSERT INTO notify_template (code, audience, kind, name_zh, name_en, title_zh, title_en, body_zh, body_en, variables, channels) VALUES
('RECOVERY_REQUESTED', 'USER', 'system', '有人申请换绑 Telegram（安全提醒）', 'Telegram rebind requested (security alert)', '有人申请把你的账户换绑到另一个 Telegram',
  'Someone asked to move your account to another Telegram',
  '有人申请把你的账户（找回申请 #{id}）换绑到另一个 Telegram 账号。48 小时内没人反对就进入人工核对。不是你本人申请的，请在机器人里点「不是我」。',
  'Someone asked to move your account (recovery #{id}) to another Telegram account. If nobody objects within 48 hours it goes to manual verification. If this was not you, tap "Not me" in the bot.',
  'id', 'INBOX,PUSH,EMAIL,TELEGRAM'),
('RECOVERY_APPROVED', 'USER', 'system', '换绑已批准、等待执行（安全提醒）', 'Rebind approved, waiting (security alert)', '换绑申请已批准，{executeAt} 执行',
  'Rebind approved; it takes effect at {executeAt}',
  '找回申请 #{id} 已经人工批准，将在 {executeAt} 执行（换绑后提现密码和白名单清空，48 小时内不能提现）。不是你本人申请的，请在机器人里点「撤销」：撤销请求交审核员核对后决定，决定之前不会执行。',
  'Recovery #{id} was approved and takes effect at {executeAt} (the withdrawal PIN and whitelist are cleared, and withdrawals pause for 48 hours). If this was not you, tap "Cancel" in the bot: the reviewer verifies and decides, and nothing runs before that.',
  'id,executeAt', 'INBOX,PUSH,EMAIL,TELEGRAM'),
('RECOVERY_EXECUTED', 'USER', 'system', '换绑已完成（安全提醒）', 'Rebind completed (security alert)', '账户已换绑到新的 Telegram',
  'Your account is now linked to a new Telegram',
  '找回申请 #{id} 已执行：账户已换绑到新的 Telegram 账号，原来的网页会话全部退出；提现密码和白名单已清空，48 小时内不能提现。',
  'Recovery #{id} has been carried out: the account is now linked to the new Telegram account and all web sessions were signed out. The withdrawal PIN and whitelist were cleared, and withdrawals pause for 48 hours.',
  'id', 'INBOX,PUSH,EMAIL,TELEGRAM'),
('RECOVERY_CLOSED', 'USER', 'system', '找回申请已结束（安全提醒）', 'Recovery request closed (security alert)', '找回申请 #{id} 已结束',
  'Recovery request #{id} closed',
  '找回申请 #{id} 已结束：{result}。', 'Recovery request #{id} is closed: {resultEn}.', 'id,result,resultEn', 'INBOX,PUSH,EMAIL,TELEGRAM'),
('PIN_RESET_REQUESTED', 'USER', 'system', '申请人工重设提现密码（安全提醒）', 'Manual PIN reset requested (security alert)', '已提交人工重设提现密码的申请',
  'Manual withdrawal PIN reset requested',
  '你的账户提交了人工重设提现密码的申请（#{id}），审核员会联系你做本人核对。不是你本人申请的，请在机器人里点「撤销」。',
  'A manual withdrawal PIN reset (#{id}) was requested on your account. A reviewer will contact you to verify it is you. If this was not you, tap "Cancel" in the bot.',
  'id', 'INBOX,PUSH,EMAIL,TELEGRAM'),
('PIN_RESET_APPROVED', 'USER', 'system', '人工重设提现密码已批准（安全提醒）', 'Manual PIN reset approved (security alert)', '人工重设提现密码已批准，{executeAt} 生效',
  'Manual PIN reset approved; takes effect at {executeAt}',
  '申请 #{id} 已批准，将在 {executeAt} 生效：原提现密码作废、白名单清空，之后 48 小时内不能提现。不是你本人申请的，请在机器人里点「撤销」。',
  'Request #{id} was approved and takes effect at {executeAt}: the current PIN stops working and the whitelist is cleared; withdrawals then pause for 48 hours. If this was not you, tap "Cancel" in the bot.',
  'id,executeAt', 'INBOX,PUSH,EMAIL,TELEGRAM'),
('PIN_RESET_EXECUTED', 'USER', 'system', '提现密码已人工重设（安全提醒）', 'Withdrawal PIN reset (security alert)', '提现密码已作废，请重新设置',
  'Your withdrawal PIN was reset; please set a new one',
  '申请 #{id} 已生效：原提现密码作废、白名单已清空，网页会话全部退出，48 小时内不能提现。请回到机器人重新授权，在网页"我的 → 提现密码"设置新密码。',
  'Request #{id} has taken effect: the old PIN no longer works, the whitelist was cleared and all web sessions were signed out. Withdrawals pause for 48 hours. Re-authorize in the bot and set a new PIN under Me → Withdrawal PIN.',
  'id', 'INBOX,PUSH,EMAIL,TELEGRAM');

-- ⑥ 告警邮件（发给 FX_ALERT_EMAILS，即 Amos）：批准换绑 / 人工重设提现密码；争议路径上原 TG 请求撤销（转 Amos 决定）
INSERT INTO notify_template (code, audience, kind, name_zh, name_en, title_zh, title_en, body_zh, body_en, variables, channels) VALUES
('OPS_RECOVERY_APPROVED', 'STAFF', NULL, '找回申请已批准', 'Recovery approved',
  '找回申请 #{id}（{kindZh}）已批准，{executeAt} 执行', 'Recovery #{id} ({kindEn}) approved; runs at {executeAt}',
  '找回申请 #{id}（{kindZh}，客户 {customerId}）已批准。\n操作人：{admin}\n核对记录：{note}\n执行时间（UTC）：{executeAt}\n如果不是你批准的，请立即在后台"找回申请"里撤销，并检查后台账号。',
  'Recovery #{id} ({kindEn}, customer {customerId}) was approved.\nBy: {admin}\nVerification note: {note}\nRuns at (UTC): {executeAt}\nIf you did not approve it, cancel it in the admin console (Recovery requests) right away and check the admin account.',
  'id,kindZh,kindEn,customerId,admin,note,executeAt', 'EMAIL'),
('OPS_RECOVERY_REVOKE_REQ', 'STAFF', NULL, '换绑收到撤销请求', 'Revoke requested on a rebind',
  '找回申请 #{id} 收到撤销请求，待你决定', 'Recovery #{id}: cancellation requested, your decision needed',
  '找回申请 #{id}（客户 {customerId}），{who}请求撤销。按规则换绑申请的撤销不直接生效（防止占着原 TG 的人挡住真原主），换绑暂停执行，等你视频核对后在后台"找回申请"里选择撤销或维持批准。',
  'Recovery #{id} (customer {customerId}): {whoEn} asked to cancel it. Cancellations of rebinds do not take effect by themselves (so whoever holds the old Telegram cannot block the real owner); the rebind is on hold until you verify by video and choose Cancel or Keep in the admin console.',
  'id,customerId,who,whoEn', 'EMAIL');
