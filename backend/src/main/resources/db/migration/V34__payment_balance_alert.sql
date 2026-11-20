-- 钱包提供方可用余额告警下限（生产方案 v1 07 页 R-5）：Trading 在当前钱包提供方（TopUP）的 USDT 可用余额低于它就发告警邮件，恢复时再发一封。
-- 0 = 不告警（测试环境沿用）；生产第一次启动时由 FX_INIT_BALANCE_MIN_USDT 设成 5,000（Amos 11/19，admin.ops.ProductionInit，只做一次），之后以后台为准。
-- 旧的 hotWallet.minUsdt.* 已在 V33 删掉（自带钱包不要了）
INSERT INTO common_config (config_key, value) VALUES ('payment.balance.minUsdt', '0');
