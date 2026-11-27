-- TG 机器人第 5 批"应急开关"（技术方案 TG机器人-11 页第 3、6 节，决定清单 TB-34）。

-- ① 应急开关的开关记录（也是当前状态：最新一行的 turned_on；没有记录 = 关）。
--    后台开关（只有 Amos 的后台账号）和服务器备用办法（sudo trading set TR_TG_EMERGENCY_LOGIN on|off）都写这里
CREATE TABLE account_tg_emergency_log (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  turned_on   TINYINT(1)   NOT NULL COMMENT '1 打开 / 0 关闭',
  actor       VARCHAR(32)  NOT NULL COMMENT 'admin:<后台账号编号> / server（服务器终端）',
  actor_name  VARCHAR(64)  NOT NULL COMMENT '后台用户名，或 server:<sudo 的用户>',
  reason      VARCHAR(500) NOT NULL,
  created_at  DATETIME(3)  NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Telegram 应急登录开关的记录（TB-34）';

-- ② 会话种类加 EMERGENCY（应急开关下"用 Telegram 登录"的网页会话；开关关上后全部作废）
ALTER TABLE account_session MODIFY kind VARCHAR(16) NOT NULL DEFAULT 'WEB' COMMENT 'WEB 网页登录 / BOT 从机器人一键登录（TG 规则）/ EMERGENCY 应急开关下用 Telegram 登录网页';

-- ③ 安全事件类型加 TG_WEB_LOGIN（应急开关下用 Telegram 登录网页：24 小时不能提现）
ALTER TABLE account_security_event MODIFY type VARCHAR(16) NOT NULL COMMENT 'PASSWORD_CHANGE / PASSWORD_RESET / EMAIL_CHANGE / FREEZE / UNFREEZE / PIN_SET / PIN_CHANGE / PIN_RESET / KYC_FIRST / REBIND / TG_WEB_LOGIN';

-- ④ 通知模板：客户用 Telegram 登录了网页（安全提醒）；告警邮件：应急开关打开 / 关闭
INSERT INTO notify_template (code, audience, kind, name_zh, name_en, title_zh, title_en, body_zh, body_en, variables, channels) VALUES
('TG_EMERGENCY_LOGIN', 'USER', 'system', '用 Telegram 登录了网页（安全提醒）', 'Signed in on the web with Telegram (security alert)',
  '刚用 Telegram 登录了网页', 'Signed in on the web with Telegram',
  '你的账户刚用 Telegram 登录了网页：{device}，IP {ip}。为了安全，{withdrawAt}（UTC）之前不能提现；查询、交易不受影响。如果不是你本人，请马上联系客服冻结提现。',
  'Your account just signed in on the web with Telegram: {device}, IP {ip}. For your safety, withdrawals are paused until {withdrawAt} (UTC); viewing and trading are not affected. If this was not you, contact support right away to freeze withdrawals.',
  'device,ip,withdrawAt', 'INBOX,PUSH,EMAIL,TELEGRAM'),
('OPS_TG_EMERGENCY', 'STAFF', NULL, 'Telegram 应急开关变了', 'Telegram emergency switch changed',
  'Telegram 应急开关已{actionZh}', 'Telegram emergency switch turned {actionEn}',
  'Telegram 应急登录开关已{actionZh}（{effectZh}）。\n操作人：{admin}\n原因：{reason}\n时间（UTC）：{at}\n如果不是你们安排的操作，请立即检查后台账号，并在服务器上运行 sudo trading set TR_TG_EMERGENCY_LOGIN off。',
  'The Telegram emergency sign-in switch was turned {actionEn} ({effectEn}).\nBy: {admin}\nReason: {reason}\nAt (UTC): {at}\nIf this was not planned, check the admin accounts right away and run sudo trading set TR_TG_EMERGENCY_LOGIN off on the server.',
  'actionZh,actionEn,effectZh,effectEn,admin,reason,at', 'EMAIL');
