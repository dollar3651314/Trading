#!/usr/bin/env bash
# 备份入口（给同机的 TopUP 调用）：TopUP 的 topup start / install 在测试环境停同机项目时，先停 trading-deploy、trading-backup 两个定时器和
# Trading 的应用容器，再运行 /opt/trading/backup.sh before-topup（MySQL、Redis 那时还开着），最后停数据库（TopUP topup.sh 的 stop_other）。
# 用法：bash /opt/trading/backup.sh [名字]，等于 sudo trading backup [名字]；失败返回非 0（TopUP 会按原样恢复 Trading）。
set -euo pipefail
exec "$(dirname "$(readlink -f "$0")")/trading" backup "${1:-manual}"
