#!/usr/bin/env bash
# 测试环境冒烟检查（第 4 批）：CI 的 staging-smoke job 在推完镜像后运行，也可以手动运行：
#   bash e2e/staging-smoke.sh <客户端地址> <期望的完整提交号，写 - 不等版本>
# 先等服务器换成这个提交（健康检查的 version；最多 SMOKE_WAIT_S 秒，默认 600），再逐项检查，每项一行 ✓ / ✗，有 ✗ 退出码 1。
# 地址只从参数来（仓库密钥 STAGING_URL），脚本里不写、也不输出任何地址；只输出通过 / 失败和 HTTP 状态。
set -uo pipefail
BASE=${1:-}; WANT=${2:-}
[ -n "$BASE" ] && [ -n "$WANT" ] || { echo "用法：bash e2e/staging-smoke.sh <客户端地址> <完整提交号或 ->"; exit 2; }
[ "$WANT" = - ] && WANT=""
BASE=${BASE%/}
WAIT_S=${SMOKE_WAIT_S:-600}
fail=0
check() { local d=$1; shift; if "$@"; then echo "  ✓ $d"; else echo "  ✗ $d"; fail=1; fi; }
has() { grep -q -- "$2" <<< "$1"; }
lacks() { ! grep -q -- "$2" <<< "$1"; }
eq() { [ "$1" = "$2" ]; }
req() { curl -s --retry 2 --retry-connrefused --max-time 15 "$@"; }
code() { req -o /dev/null -w '%{http_code}' "$@"; }
field() { sed -n "s/.*\"$1\":\"\\([^\"]*\\)\".*/\\1/p" <<< "$2" | head -1; }

if [ -n "$WANT" ]; then
  echo "等待测试环境换成提交 ${WANT:0:12}（最多 ${WAIT_S} 秒）……"
  end=$((SECONDS + WAIT_S)); got=""
  while :; do
    got=$(field version "$(curl -s --max-time 15 "$BASE/api/health")")
    [ "$got" = "$WANT" ] && break
    [ "$SECONDS" -lt "$end" ] || break
    sleep 10
  done
  check "测试环境已换成提交 ${WANT:0:12}（当前：${got:0:12}）" eq "$got" "$WANT"
fi

echo "测试环境检查（地址不输出）"
health=$(curl -s --max-time 15 "$BASE/api/health")
check "健康检查 status=UP" eq "$(field status "$health")" UP
check "健康检查 env=test" eq "$(field env "$health")" test
check "客户端域名的健康检查不带业务告警（alerts 只给后台域名）" lacks "$health" '"alerts"'
check "首页 200" eq "$(code "$BASE/")" 200
check "资产页路由 /assets/ledger 返回页面 200" eq "$(code "$BASE/assets/ledger")" 200
check "客户端域名上后台接口 404" eq "$(code "$BASE/api/admin/me")" 404
check "客户端域名上后台接口（大写）404" eq "$(code "$BASE/api/ADMIN/me")" 404
check "端到端测试接口 404" eq "$(code "$BASE/api/e2e/code")" 404
check "应用管理端点 404" eq "$(code "$BASE/actuator/health")" 404
check "路径里有分号 400" eq "$(code --path-as-is "$BASE/api/admin;x/me")" 400
check "跨站写请求 403" eq "$(code -X POST -H 'content-type: application/json' -H 'origin: https://evil.example' -d '{}' "$BASE/api/auth/logout")" 403
check "HTTPS 有 HSTS" grep -qi '^strict-transport-security:' <<< "$(req -I "$BASE/")"
check "http 跳转到 https" eq "$(code "${BASE/https:/http:}/")" 301
exit $fail
