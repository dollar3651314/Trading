-- TG 机器人第 4 批 4c"申请冻结提现入口、机器人里自己冻结、后台更换 Telegram 关联"（需求文档 TG机器人-08 页第 2、5 节，决定清单 TB-27、TB-37）。

-- ① 机器人里自己冻结（BOT_SELF）的自助解冻：在网页输对提现密码后排一个解冻时间（24 小时后），到时间只删 BOT_SELF 这一行；
--    这 24 小时里网页入口又冻结了（WEB_REQUEST 另一行），那一行照旧，要人工解冻
ALTER TABLE account_withdraw_freeze
  ADD COLUMN release_at DATETIME(3) NULL COMMENT '自助解冻时间（只对 BOT_SELF）；为空 = 没申请解冻' AFTER created_at;

-- ② 网页"申请冻结提现"的每一次提交（对不对都记）：按来源（IP、浏览器标识）数失败次数换更难的验证码；按目标账户数失败次数发告警；
--    成功的用来算"同一账户 7 天内只能被网页入口冻结 1 次"和"全平台 1 小时成功冻结超过 N 个"
CREATE TABLE wallet_freeze_request (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  target_user_id BIGINT       NULL COMMENT '填的客户号（客户不存在时也记，方便看谁在乱试）',
  ip             VARCHAR(45)  NOT NULL,
  fingerprint    VARCHAR(64)  NULL COMMENT '浏览器标识（网页在本地生成的随机编号，弱标识）',
  result         VARCHAR(16)  NOT NULL COMMENT 'FROZEN 冻结了 / ALREADY 本来就冻结 / LIMITED 7 天内已经冻结过 / WRONG 信息不对 / CAPTCHA 验证码不对',
  created_at     DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_wallet_freeze_request_ip (ip, created_at),
  KEY idx_wallet_freeze_request_fp (fingerprint, created_at),
  KEY idx_wallet_freeze_request_target (target_user_id, created_at),
  KEY idx_wallet_freeze_request_time (result, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='网页申请冻结提现的提交记录';

-- ③ 系统配置：全平台 1 小时内网页入口成功冻结超过这个数就发告警（TB-27，后台可改）
INSERT INTO common_config (config_key, value) VALUES ('withdraw.freeze.platformAlertPerHour', '10');

-- ④ 通知模板：机器人里自己冻结了 / 申请了自助解冻（24 小时后）/ 已自助解冻；后台解除或更换 Telegram 关联（客户）
INSERT INTO notify_template (code, audience, kind, name_zh, name_en, title_zh, title_en, body_zh, body_en, variables, channels) VALUES
('WITHDRAW_SELF_UNFREEZE', 'USER', 'system', '申请了自助解冻（安全提醒）', 'Self-unfreeze requested (security alert)', '提现将在 {at} 自动解冻',
  'Withdrawals unfreeze at {at}',
  '你在网页输对了提现密码，申请解除机器人里自己设的提现冻结，将在 {at} 自动解冻。不是你本人操作的，请马上用网页"申请冻结提现"或联系客服——那样会转人工解冻。',
  'You entered your withdrawal PIN to lift the freeze you set in the bot. Withdrawals unfreeze automatically at {at}. If this was not you, use "Freeze withdrawals" on the web or contact support right away; that turns it into a manual review.',
  'at', 'INBOX,PUSH,EMAIL,TELEGRAM'),
('WITHDRAW_UNFROZEN', 'USER', 'system', '提现已解冻（安全提醒）', 'Withdrawals unfrozen (security alert)', '提现已解冻', 'Withdrawals unfrozen',
  '你的提现冻结（{source}）已解除。不是你本人申请的，请马上联系客服。', 'Your withdrawal freeze ({sourceEn}) was lifted. If you did not ask for this, contact support right away.',
  'source,sourceEn', 'INBOX,PUSH,EMAIL,TELEGRAM'),
('TELEGRAM_LINK_CHANGED', 'USER', 'system', 'Telegram 关联已变更（安全提醒）', 'Telegram link changed (security alert)', '你的账户的 Telegram 关联已由客服变更',
  'The Telegram link of your account was changed by support',
  '你的账户（客户号 #{userId}）的 Telegram 关联已由客服{actionZh}。网页会话已全部退出，提现已冻结，需要人工核对后解冻。不是你本人申请的，请马上联系客服。',
  'The Telegram link of your account (customer #{userId}) was {actionEn} by support. All web sessions were signed out and withdrawals are frozen until a manual check. If you did not ask for this, contact support right away.',
  'userId,actionZh,actionEn', 'INBOX,EMAIL');

-- ⑤ 告警邮件：后台解除 / 更换 Telegram 关联
INSERT INTO notify_template (code, audience, kind, name_zh, name_en, title_zh, title_en, body_zh, body_en, variables, channels) VALUES
('OPS_TELEGRAM_LINK', 'STAFF', NULL, '后台变更了 Telegram 关联', 'Telegram link changed in the admin console',
  '客户 {customerId} 的 Telegram 关联已{actionZh}', 'Telegram link {actionEn} for customer {customerId}',
  '客户 {customerId} 的 Telegram 关联已{actionZh}（原 {oldTg} → 新 {newTg}）。\n操作人：{admin}\n原因：{reason}\n时间（UTC）：{at}\n如果不是你们安排的操作，请立即检查后台账号并联系客户。',
  'The Telegram link of customer {customerId} was {actionEn} (old {oldTg} → new {newTg}).\nBy: {admin}\nReason: {reason}\nAt (UTC): {at}\nIf this was not planned, check the admin account and contact the customer right away.',
  'customerId,actionZh,actionEn,oldTg,newTg,admin,reason,at', 'EMAIL');

-- ⑥ 提现冻结的来源加 ADMIN（后台解除 / 更换 Telegram 关联时冻结，人工核对后解冻）
ALTER TABLE account_withdraw_freeze MODIFY source VARCHAR(16) NOT NULL COMMENT 'PIN_LOCKS / WEB_REQUEST / BOT_SELF / DISPUTE / RECOVERY / ADMIN';

-- ⑦ Telegram 账户的来源加 ADMIN（后台给没有 TG 的客户关联）
ALTER TABLE account_telegram MODIFY source VARCHAR(8) NOT NULL COMMENT 'SIGNUP 机器人里授权开户 / ADMIN 后台关联';
