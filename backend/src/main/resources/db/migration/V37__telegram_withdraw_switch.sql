-- TG 机器人第 2 批审查 🔴B2-1：TG 客户进入网页后不能用旧规则加白名单、提现（TB-1"TG 提现默认关"）。
-- telegram.withdraw.enabled 默认 false：有 TG 账户的客户加白名单、提交提现一律拒绝；第 4 批（提现密码、额度、人工审核）验收后由 Amos 在后台打开
INSERT INTO common_config (config_key, value) VALUES ('telegram.withdraw.enabled', 'false');
