-- TG 机器人第 6 批"后台加固"（技术方案 TG机器人-11 页第 6 节，决定清单 TB-37、TB-38；后台 TOTP 推迟）。

-- ① 告警邮件里的撤销链接（TB-37 ②）：每封告警邮件现做一个随机令牌（库里只存 SHA-256），24 小时有效、只能用一次、只能撤销这一份找回申请
CREATE TABLE account_recovery_revoke_token (
  token_hash   CHAR(64)    NOT NULL,
  recovery_id  BIGINT      NOT NULL,
  expires_at   DATETIME(3) NOT NULL,
  used_at      DATETIME(3) NULL,
  created_at   DATETIME(3) NOT NULL,
  PRIMARY KEY (token_hash),
  KEY idx_account_recovery_revoke_token (recovery_id),
  KEY idx_account_recovery_revoke_token_exp (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='告警邮件里的找回申请撤销链接（一次性）';

-- ② 服务器命令 sudo trading cancel-recovery <编号>（TB-37 ②）：命令只往这里写一行，应用每分钟处理（执行前也先看这里），按后台撤销同样的规则结束申请
CREATE TABLE account_recovery_cancel_request (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  recovery_id   BIGINT       NOT NULL,
  requested_by  VARCHAR(64)  NOT NULL COMMENT 'server:<sudo 的用户>',
  reason        VARCHAR(500) NOT NULL,
  created_at    DATETIME(3)  NOT NULL,
  processed_at  DATETIME(3)  NULL,
  result        VARCHAR(16)  NULL COMMENT 'CANCELLED 已撤销 / NOT_OPEN 已经结束 / NOT_FOUND 没有这份申请',
  PRIMARY KEY (id),
  KEY idx_account_recovery_cancel_request (processed_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='服务器终端撤销找回申请的请求';

-- ③ "暂停受理人工申请"开关（TB-38）：最新一行就是现在的状态，没有记录 = 正常受理
CREATE TABLE account_recovery_intake_log (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  paused      TINYINT(1)   NOT NULL COMMENT '1 暂停受理 / 0 恢复受理',
  actor       VARCHAR(32)  NOT NULL COMMENT 'admin:<后台账号编号>',
  actor_name  VARCHAR(64)  NOT NULL,
  reason      VARCHAR(500) NOT NULL,
  created_at  DATETIME(3)  NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='暂停受理人工申请（换绑、没邮箱重设提现密码）的开关记录（TB-38）';

-- ④ 告警邮件带撤销入口：批准找回申请、换绑收到撤销请求、后台更换 Telegram 关联三封加 {revokeLine}（撤销链接 + 服务器命令）
UPDATE notify_template SET
  body_zh = '找回申请 #{id}（{kindZh}，客户 {customerId}）已批准。\n操作人：{admin}\n核对记录：{note}\n执行时间（UTC）：{executeAt}\n如果不是你批准的，请立即撤销并检查后台账号：\n{revokeLine}',
  body_en = 'Recovery #{id} ({kindEn}, customer {customerId}) was approved.\nBy: {admin}\nVerification note: {note}\nRuns at (UTC): {executeAt}\nIf you did not approve it, cancel it right away and check the admin account:\n{revokeLineEn}',
  variables = 'id,kindZh,kindEn,customerId,admin,note,executeAt,revokeLine,revokeLineEn'
WHERE code = 'OPS_RECOVERY_APPROVED';
UPDATE notify_template SET
  body_zh = '找回申请 #{id}（客户 {customerId}），{who}请求撤销。按规则换绑申请的撤销不直接生效（防止占着原 TG 的人挡住真原主），换绑暂停执行，等你视频核对后在后台"找回申请"里选择撤销或维持批准。\n核对后要撤销、又进不了后台时：\n{revokeLine}',
  body_en = 'Recovery #{id} (customer {customerId}): {whoEn} asked to cancel it. Cancellations of rebinds do not take effect by themselves (so whoever holds the old Telegram cannot block the real owner); the rebind is on hold until you verify by video and choose Cancel or Keep in the admin console.\nIf you decide to cancel and cannot reach the admin console:\n{revokeLineEn}',
  variables = 'id,customerId,who,whoEn,revokeLine,revokeLineEn'
WHERE code = 'OPS_RECOVERY_REVOKE_REQ';
UPDATE notify_template SET
  body_zh = '客户 {customerId} 的 Telegram 关联已{actionZh}（原 {oldTg} → 新 {newTg}）。\n操作人：{admin}\n原因：{reason}\n时间（UTC）：{at}\n如果不是你们安排的操作，请立即检查后台账号并联系客户。\n{revokeLine}',
  body_en = 'The Telegram link of customer {customerId} was {actionEn} (old {oldTg} → new {newTg}).\nBy: {admin}\nReason: {reason}\nAt (UTC): {at}\nIf this was not planned, check the admin account and contact the customer right away.\n{revokeLineEn}',
  variables = 'customerId,actionZh,actionEn,oldTg,newTg,admin,reason,at,revokeLine,revokeLineEn'
WHERE code = 'OPS_TELEGRAM_LINK';

-- ⑤ 告警邮件：找回申请被撤销链接或服务器命令撤销了；暂停 / 恢复受理人工申请
INSERT INTO notify_template (code, audience, kind, name_zh, name_en, title_zh, title_en, body_zh, body_en, variables, channels) VALUES
('OPS_RECOVERY_CANCELLED', 'STAFF', NULL, '找回申请已在后台以外撤销', 'Recovery cancelled outside the admin console',
  '找回申请 #{id} 已撤销（{viaZh}）', 'Recovery #{id} cancelled ({viaEn})',
  '找回申请 #{id}（客户 {customerId}）已通过{viaZh}撤销。\n原因：{reason}\n时间（UTC）：{at}\n如果不是你本人操作的，请检查告警邮箱和服务器的登录记录。',
  'Recovery #{id} (customer {customerId}) was cancelled via {viaEn}.\nReason: {reason}\nAt (UTC): {at}\nIf you did not do this, check the alert mailbox and the server login records.',
  'id,customerId,viaZh,viaEn,reason,at', 'EMAIL'),
('OPS_RECOVERY_INTAKE', 'STAFF', NULL, '人工申请受理状态变了', 'Manual application intake changed',
  '人工申请已{actionZh}', 'Manual applications {actionEn}',
  '换绑、没邮箱重设提现密码的人工申请已{actionZh}。\n操作人：{admin}\n原因：{reason}\n时间（UTC）：{at}\n暂停时新的申请会提示"暂停中，请先申请冻结提现"；已提交的申请留在队列里，已批准的照常执行；冻结入口照常可用。如果不是你本人操作的，请立即检查后台账号。',
  'Manual applications (rebind, PIN reset without email) are now {actionEn}.\nBy: {admin}\nReason: {reason}\nAt (UTC): {at}\nWhile paused, new applications are told to freeze withdrawals first; submitted ones stay in the queue and approved ones run as scheduled; the freeze entry keeps working. If you did not do this, check the admin accounts right away.',
  'actionZh,actionEn,admin,reason,at', 'EMAIL'),
('OPS_SECURITY_TEMPLATE', 'STAFF', NULL, '安全提醒模板被改了', 'Security notice template changed',
  '安全提醒模板 {code} 被改了', 'Security notice template {code} changed',
  '客户安全提醒模板 {code}（{nameZh}）的文案在后台被改了。\n操作人：{admin}\n改了：{fields}\n时间（UTC）：{at}\n现在的中文正文：\n{bodyZh}\n如果不是你们安排的修改，请立即检查后台账号，并在后台"通知模板"里改回来（审计日志里有修改前的文案）。',
  'The text of the customer security notice template {code} ({nameEn}) was changed in the admin console.\nBy: {admin}\nFields: {fields}\nAt (UTC): {at}\nCurrent English body:\n{bodyEn}\nIf this was not planned, check the admin accounts right away and restore the text under Notification templates (the audit log has the previous text).',
  'code,nameZh,nameEn,admin,fields,at,bodyZh,bodyEn', 'EMAIL');
