-- TG 机器人第 1 批：机器人底座（技术方案 TG机器人-07 页第 4 节 V3x-1、11 页第 6 节）。
-- ⚠ 只能前滚：机器人开户的客户没有邮箱（email 为空），之后不能再把 email 改回 NOT NULL。

-- ① 邮箱改可空：纯 Telegram 客户没有邮箱。唯一约束保留（MySQL 的唯一索引允许多个 NULL，PaymentDepositFlowTest 等在真实 MySQL 8.4 上测）
ALTER TABLE account_user MODIFY email VARCHAR(254) NULL COMMENT '登录邮箱；机器人开户的客户（纯 Telegram）为空';

-- ② TG 账户：一个 Telegram 用户编号对应一个客户号（客户号仍是主键，账本、订单等都不动）
CREATE TABLE account_telegram (
  user_id       BIGINT       NOT NULL COMMENT '客户号',
  telegram_id   BIGINT       NOT NULL COMMENT 'Telegram 用户编号（from.id），只认它',
  username      VARCHAR(64)  NULL COMMENT 'Telegram 用户名（只用来显示，客户可以改）',
  source        VARCHAR(8)   NOT NULL COMMENT 'SIGNUP 机器人里授权开户',
  authorized_at DATETIME(3)  NOT NULL COMMENT '最近一次授权（开户或安全事件后重新授权）；之后出过安全事件就要重新授权',
  created_at    DATETIME(3)  NOT NULL,
  PRIMARY KEY (user_id),
  UNIQUE KEY uk_account_telegram_tg (telegram_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Telegram 账户（一个 TG 用户编号 = 一个客户号）';

-- ③ 通知：Telegram 渠道。客户的 Telegram 提醒按类开关（没有记录 = 开）；强平预警、强平、安全提醒不能关（代码里写死）
CREATE TABLE notify_tg_pref (
  user_id    BIGINT       NOT NULL,
  category   VARCHAR(16)  NOT NULL COMMENT 'TRADE / TPSL / FUNDS / KYC / SYSTEM（RISK、SECURITY 不能关，不存）',
  enabled    TINYINT(1)   NOT NULL,
  updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户的 Telegram 提醒按类开关';

ALTER TABLE notify_template MODIFY channels VARCHAR(40) NOT NULL COMMENT 'INBOX、PUSH、EMAIL、TELEGRAM，逗号分隔';
ALTER TABLE notify_record MODIFY channel VARCHAR(8) NOT NULL COMMENT 'INBOX / PUSH / EMAIL / TELEGRAM';

-- 发给客户的提醒都加 Telegram 渠道（只有开过 TG 账户的客户才会发；不改别的渠道）
UPDATE notify_template SET channels = CONCAT(channels, ',TELEGRAM')
WHERE code IN ('ORDER_FILLED', 'POSITION_OPENED', 'POSITION_CLOSED', 'POSITION_PARTIAL_CLOSED', 'TP_SL_TRIGGERED', 'MARGIN_CALL', 'LIQUIDATED',
  'DEPOSIT_CREDITED', 'WITHDRAW_STATUS', 'KYC_APPROVED', 'KYC_REJECTED')
  AND channels NOT LIKE '%TELEGRAM%';

-- 新的安全提醒：账户被冻结（只发 Telegram；网页客户冻结后本来就登录不了）
INSERT INTO notify_template (code, audience, kind, name_zh, name_en, title_zh, title_en, body_zh, body_en, variables, channels) VALUES
('ACCOUNT_FROZEN', 'USER', 'system', '账户已冻结（安全提醒）', 'Account frozen (security alert)',
  '账户已冻结', 'Account frozen',
  '你的账户已被冻结，暂时不能交易和提现。如果不是你本人申请的，请尽快联系客服。', 'Your account has been frozen. Trading and withdrawals are paused. If you did not request this, please contact support.',
  '', 'TELEGRAM');

-- ④ 后台开关（系统配置，权限 config:edit、高危、写审计）：
--   telegram.enabled           机器人总开关（true / false）；关的时候机器人只回"暂停服务"，Telegram 提醒也不发
--   telegram.signup.allowIds   允许授权开户的 Telegram 用户编号，逗号分隔；空 = 谁都不能开户；* = 不限（TB-13：先只给 Amos 用）
INSERT INTO common_config (config_key, value) VALUES ('telegram.enabled', 'true'), ('telegram.signup.allowIds', '');
