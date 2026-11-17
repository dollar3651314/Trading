-- V25（2026-10-17 热钱包余额告警）：六个告警下限，在后台"系统配置"页改（权限 config:edit，高危、写审计）。
-- 负责人 2026-10-17："告警的默认下限让我可以设置，现在设置成零"：默认全部 0 = 不告警；余额照常查询，后台对账页显示。
-- 单位：minUsdt.* 是 USDT；minGas.ERC20 是 ETH、minGas.BSC 是 BNB、minGas.TRC20 是 TRX。说明见 内部文档 第 6 节、内部文档
INSERT INTO common_config (config_key, value) VALUES
('hotWallet.minUsdt.ERC20', '0'),
('hotWallet.minUsdt.BSC', '0'),
('hotWallet.minUsdt.TRC20', '0'),
('hotWallet.minGas.ERC20', '0'),
('hotWallet.minGas.BSC', '0'),
('hotWallet.minGas.TRC20', '0');
