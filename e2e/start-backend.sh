#!/usr/bin/env bash
# 以端到端测试模式（profile e2e）在后台启动报价服务和交易后端，等健康检查通过（S19；第 2 批起是两个进程）。
# 用法：bash e2e/start-backend.sh   （先 mvn package -DskipTests）
# 需要的环境变量：FX_DB_URL、FX_DB_USERNAME、FX_DB_PASSWORD（专用的空库，例如 trading_e2e）、FX_REDIS_HOST；
# FX_ADMIN_INIT_USERNAME、FX_ADMIN_INIT_PASSWORD（第一个超管，一次性测试值）。
# 报价服务的库默认是同一个 MySQL 上的 trading_quote_e2e（不存在就建，FX_QUOTE_DB_URL 可改），账号密码默认同 FX_DB_*。
# 日志写到 e2e/logs/quote.log、e2e/logs/backend.log（失败时 CI 作为附件上传）。
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p e2e/logs
quote_jar=$(ls quote-app/target/quote-app-*-exec.jar | head -1)
jar=$(ls backend/target/trading-*.jar | grep -v plain | head -1)
export SPRING_PROFILES_ACTIVE=e2e
export FX_E2E_ENABLED=true
# 两个进程之间的内部 Key：本机和 CI 的一次性测试值，不是任何环境的密钥
export FX_QUOTE_INTERNAL_KEY="${FX_QUOTE_INTERNAL_KEY:-ci_only_e2e_internal_key}"
export FX_QUOTE_HTTP_PORT="${FX_QUOTE_HTTP_PORT:-8090}"
export FX_QUOTE_APP_URL="http://127.0.0.1:${FX_QUOTE_HTTP_PORT}"

wait_health() { # 名字 端口 pid文件 日志
  for i in $(seq 1 90); do
    if curl -sf "http://127.0.0.1:$2/api/health" > /dev/null; then
      echo "$1已启动（e2e profile），pid $(cat "$3")"
      return 0
    fi
    if ! kill -0 "$(cat "$3")" 2> /dev/null; then
      echo "$1启动失败，日志最后 80 行：" >&2
      tail -80 "$4" >&2
      exit 1
    fi
    sleep 2
  done
  echo "等了 180 秒$1还没有通过健康检查，日志最后 80 行：" >&2
  tail -80 "$4" >&2
  exit 1
}

# 1. 报价服务：模拟模式，50 个品种都有报价；/internal/e2e/move 推动价格
FX_QUOTE_MARKET_SOURCE=simulated \
FX_QUOTE_DB_URL="${FX_QUOTE_DB_URL:-jdbc:mysql://127.0.0.1:3306/trading_quote_e2e?createDatabaseIfNotExist=true}" \
FX_QUOTE_DB_USERNAME="${FX_QUOTE_DB_USERNAME:-$FX_DB_USERNAME}" \
FX_QUOTE_DB_PASSWORD="${FX_QUOTE_DB_PASSWORD:-$FX_DB_PASSWORD}" \
FX_QUOTE_MANAGEMENT_PORT="${FX_QUOTE_MANAGEMENT_PORT:-8092}" \
  nohup java -jar "$quote_jar" > e2e/logs/quote.log 2>&1 &
echo $! > e2e/logs/quote.pid
wait_health "报价服务" "$FX_QUOTE_HTTP_PORT" e2e/logs/quote.pid e2e/logs/quote.log

# 2. 交易后端：连报价服务（application-e2e.yml 里 trading.market.source=remote）
# 本机和 CI 都是 http，登录 Cookie 不能只走 HTTPS
export FX_COOKIE_SECURE=false
export FX_STORAGE_DIR="${FX_STORAGE_DIR:-$PWD/e2e/logs/storage}"
nohup java -jar "$jar" > e2e/logs/backend.log 2>&1 &
echo $! > e2e/logs/backend.pid
wait_health "交易后端" "${FX_HTTP_PORT:-8080}" e2e/logs/backend.pid e2e/logs/backend.log
