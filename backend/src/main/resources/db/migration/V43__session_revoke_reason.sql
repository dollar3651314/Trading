-- TG 机器人验收反馈 1b：会话作废的原因。安全事件（设置 / 修改 / 重设提现密码、第一次提交 KYC、换绑、应急登录等）作废的记 SECURITY，
-- 网页再拿这个会话来时返回 401 SECURITY_LOGOUT，去"回到 Telegram 机器人点「重新授权」，再点「打开交易」"的提示页
ALTER TABLE account_session ADD COLUMN revoke_reason VARCHAR(16) NULL COMMENT 'SECURITY 安全事件作废；其他为空' AFTER revoked_at;
