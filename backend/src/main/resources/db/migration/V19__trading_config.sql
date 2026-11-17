-- V19（S16c）：风控页和交易参数页改的两项配置（不在系统配置页显示，见 common/config/ConfigKey.java）。说明见 内部文档 2.39
INSERT INTO common_config (config_key, value) VALUES
('risk.perUserLimitUsd', '3000000'),
('trading.marginCallLevel', '100');
