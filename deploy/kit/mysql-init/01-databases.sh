#!/bin/bash
# MySQL 第一次初始化时（data/mysql 为空）由官方镜像的入口脚本运行一次：建 trading、quote 两个库和两个账号，
# 每个账号只能用自己的库（部署方案 02 页第 2 节）。密码从容器的环境变量读（来自服务器 .env），不写在这里、不打印。
set -euo pipefail
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot <<SQL
CREATE DATABASE IF NOT EXISTS trading CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS quote CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'trading'@'%' IDENTIFIED BY '${FX_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'quote'@'%' IDENTIFIED BY '${FX_QUOTE_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON trading.* TO 'trading'@'%';
GRANT ALL PRIVILEGES ON quote.* TO 'quote'@'%';
FLUSH PRIVILEGES;
SQL
