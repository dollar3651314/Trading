#!/usr/bin/env bash
# 生产模式装机演练（生产方案 v1 07 页"研发交付时要附的"：R-3、R-4 在开发机或测试机上演练一遍，日志给运维安全复核；照 TopUP 的 deploy-drill.sh）。
# 在开发机上用本地构建的三个镜像（不连镜像仓库、不碰 systemd）走一遍：
#   trading install --production → 网关模式 Nginx 按端口 → 只开加密货币、余额告警 5,000 → 备份（数据库压缩再加密、照片不进包、每周增量、--with-photos）→
#   恢复演练 → backup-key → 真恢复 --confirm --production（暂停交付款）→ start-payout → restore-photos → 真实 IP 开关 → off / 停着时 set 只保存 / on → watch、update
# 用法（仓库根目录，先 mvn package、三个前端 npm run build）：
#   SHA=$(printf 'd1%.0s' {1..20}); for p in app:trading-app quote:trading-quote web:trading-web; do
#     docker build -f deploy/${p%%:*}.Dockerfile --build-arg FX_VERSION=$SHA --label org.opencontainers.image.revision=$SHA -t ghcr.io/dollar3651314/${p#*:}:prod-$SHA .; done
#   docker tag ghcr.io/dollar3651314/trading-app:prod-$SHA ghcr.io/dollar3651314/trading-app:production
#   sudo bash deploy/drill-production.sh <空目录>
# 会占用本机 8082～8085、127.0.0.1:18082，起名为 trading 的 compose 项目（跑完 docker compose down）。
# 磁盘紧的开发机可以设 TR_INSTALL_MIN_DISK_MB、TR_UPDATE_MIN_DISK_MB、TR_DRILL_MIN_DISK_MB 调低门槛（演练日志里写明）。不需要任何密钥：邮件没配置时只记日志。
set -uo pipefail
D=${1:?用法：sudo bash deploy/drill-production.sh <空目录>}
mkdir -p "$D"
[ -z "$(ls -A "$D")" ] || { echo "目录要是空的：$D"; exit 2; }
D=$(cd "$D" && pwd)
cp -r "$(dirname "$0")/kit/." "$D/"
echo drill > "$D/VERSION"
T() { env TR_DIR="$D" TR_OFFLINE_TEST=1 TR_HEALTH_WAIT_S=400 bash "$D/trading" "$@" 2>&1; }
q() { docker exec trading-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "$1"' _ "$1"; }
step() { echo; echo "######## $*"; }

step "0. 安装（--production，三个主机名；报价主机名按 8082-… 推出来）"
T install --production 8082-drill.check.invalid 8083-drill.check.invalid 8084-drill.check.invalid || { echo "安装失败"; exit 1; }

step "1. Nginx 平台网关模式（按端口）"
for p in 8082 8083 8084; do
  echo "-- $p /robots.txt"
  curl -s -D - -o "$D/robots.out" "http://127.0.0.1:$p/robots.txt" | grep -iE "^HTTP|strict-transport|x-robots"
  cat "$D/robots.out"; echo
done
echo "-- 8083 /api/health（经 Nginx，带告警）"; curl -s http://127.0.0.1:8083/api/health; echo
echo "-- 8082 /api/health（客户端，不带告警）"; curl -s http://127.0.0.1:8082/api/health; echo
echo "-- 8083 / 跳转"; curl -s -D - -o /dev/null http://127.0.0.1:8083/ | grep -i "^location"
echo "-- 端口绑定"; docker port trading-trading-web-1; docker port trading-trading-app-1
echo "-- 8085（只绑 127.0.0.1）/docs/：live 下应 404"; curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8085/docs/
echo "-- 官网公开行情（只列 10 个加密货币、不显示价格）"; curl -s http://127.0.0.1:8084/api/public/quotes | head -c 400; echo

step "2. 数据库：只开加密货币、余额告警 5,000、只做一次的标记、审计；MySQL 参数；容器上限"
q "SELECT category, enabled, COUNT(*) FROM trading.market_symbol GROUP BY category, enabled ORDER BY category"
q "SELECT config_key, value FROM trading.common_config WHERE config_key LIKE 'init.%' OR config_key LIKE 'payment.balance%'"
q "SELECT action, COUNT(*) FROM trading.admin_audit_log WHERE admin_name = 'system' GROUP BY action"
q "SHOW VARIABLES WHERE Variable_name IN ('log_bin', 'max_connections', 'innodb_buffer_pool_size')"
for c in trading-app quote-app mysql redis trading-web; do
  echo "$c 内存 $(($(docker inspect -f '{{.HostConfig.Memory}}' "trading-$c-1") / 1048576)) MiB、CPU $(docker inspect -f '{{.HostConfig.NanoCpus}}' "trading-$c-1")、日志 $(docker inspect -f '{{.HostConfig.LogConfig.Config}}' "trading-$c-1")"
done

step "3. 备份：数据库压缩再加密、照片不进包、每周增量、--with-photos"
mkdir -p "$D/data/storage/kyc/1"; echo fake-photo-1 > "$D/data/storage/kyc/1/a.jpg"; chown -R 10001 "$D/data/storage"
T backup daily
ls -la "$D/backup" "$D/backup/photos"
f=$(ls -1t "$D"/backup/trading-*.tar.age | head -1)
echo "-- 包里有什么（解密后列目录）"; age -d -i "$D/state/backup.key" "$f" | tar -tvf -
echo "-- 临时目录里没有明文（应为空）"; ls -A "$D/tmp"
echo "-- 解密、解压后的导出完整（最后一行）"; age -d -i "$D/state/backup.key" "$f" | tar -xOf - mysql.sql.gz.age | age -d -i "$D/state/backup.key" | gzip -dc | tail -1
echo fake-photo-2 > "$D/data/storage/kyc/1/b.jpg"; touch -d '+1 sec' "$D/data/storage/kyc/1/b.jpg"
echo 0 > "$D/state/last-photos"; T backup daily | tail -2; ls "$D/backup/photos"
T backup manual --with-photos | tail -2; ls "$D/backup/photos"

step "4. 恢复演练（生产不停 Trading）"
T restore-drill; cat "$D/state/last-drill"; docker ps --format '{{.Names}} {{.Status}}' | grep trading-trading-app

step "5. backup-key：Amos 的公钥也能解开"
age-keygen -o "$D/amos.key" 2> /dev/null
T backup-key "$(age-keygen -y "$D/amos.key")"; T backup amos | tail -1
f=$(ls -1t "$D"/backup/trading-*-amos.tar.age | head -1)
age -d -i "$D/amos.key" "$f" | tar -tf - && echo "Amos 的私钥能解开"

step "6. 真恢复（--confirm --production）+ 暂停交付款 + start-payout"
q "INSERT INTO trading.common_config (config_key, value) VALUES ('drill.after.backup', 'x')"
daily=$(ls -1t "$D"/backup/trading-*-daily.tar.age | head -1)
T restore "$(basename "$daily")" --confirm
T restore "$(basename "$daily")" --confirm --production
echo "-- 备份之后写的 drill.after.backup 应该没了，payment.payout.paused 应该是 true"
q "SELECT config_key, value FROM trading.common_config WHERE config_key IN ('payment.payout.paused', 'drill.after.backup')"
curl -s http://127.0.0.1:18082/api/health; echo
T status | grep -E "暂停|版本"
T start-payout --confirm
q "SELECT config_key, value FROM trading.common_config WHERE config_key = 'payment.payout.paused'"
ls "$D"/backup/*before-restore*

step "7. restore-photos：误删的照片补回来"
rm "$D/data/storage/kyc/1/a.jpg"; T restore-photos; ls "$D/data/storage/kyc/1"

step "8. 真实 IP 开关（运行中：重建 web、经 8083 查健康）"
T set TR_REAL_IP_FROM 10.1.0.0/16; T set TR_REAL_IP_HEADER X-Forwarded-For; docker exec trading-trading-web-1 cat /etc/nginx/trading/realip.conf
T set TR_REAL_IP_HEADER off; docker exec trading-trading-web-1 cat /etc/nginx/trading/realip.conf

step "9. off / 停着时 set 只保存 / on"
T off | tail -5
echo "Trading 容器数：$(docker ps --format '{{.Names}}' | grep -c '^trading-')"
T set FX_ALERT_EMAILS ops@example.com
echo "Trading 容器数（应仍为 0）：$(docker ps --format '{{.Names}}' | grep -c '^trading-')"
T on | tail -3

step "10. watch / update（同一个版本不动）"
T watch; cat "$D/state/watch"; echo
T update; echo "update 退出码 $?"

step "11. 收尾"
docker compose --project-name trading --project-directory "$D" -f "$D/docker-compose.yml" -f "$D/docker-compose.production.yml" --env-file "$D/.env" --env-file "$D/state/image.env" down
