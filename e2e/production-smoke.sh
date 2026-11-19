#!/usr/bin/env bash
# 生产冒烟检查（生产方案 v1 05 页第 1 节第 6 步、07 页 R-8）：CI 的 production-smoke job 在 Amos 批准 promote-production 之后运行，也可以手动运行：
#   bash e2e/production-smoke.sh <客户端地址> <期望的完整提交号，写 - 不等版本> [后台地址] [官网地址]
# **只读**：只发 GET / HEAD，不登录、不下单、不写任何东西。地址只从参数来（仓库密钥 PRODUCTION_URL、PRODUCTION_ADMIN_URL，可选 PRODUCTION_SITE_URL），
# 脚本里不写、也不输出任何地址；只输出通过 / 失败和 HTTP 状态。后台、官网地址没给时按平台的规律从客户端地址推（https://8082-<编号>… →
# 8083-、8084-，推断），推不出来就跳过那几项并打警告。
# 先等生产换成这个提交（健康检查的 version；最多 SMOKE_WAIT_S 秒，默认 900：服务器每分钟拉一次 production 标签，换版本前还要备份），再逐项检查，
# 每项一行 ✓ / ✗，有 ✗ 退出码 1。
set -uo pipefail
BASE=${1:-}; WANT=${2:-}; ADMIN=${3:-}; SITE=${4:-}
[ -n "$BASE" ] && [ -n "$WANT" ] || { echo "用法：bash e2e/production-smoke.sh <客户端地址> <完整提交号或 -> [后台地址] [官网地址]"; exit 2; }
[ "$WANT" = - ] && WANT=""
BASE=${BASE%/}; ADMIN=${ADMIN%/}; SITE=${SITE%/}
# 平台地址的规律：https://<端口>-<编号>.instabox.tech（生产方案 02 页）
derive() { [[ "$BASE" =~ ^https://8082-(.+)$ ]] && printf 'https://%s-%s' "$1" "${BASH_REMATCH[1]}"; }
[ -n "$ADMIN" ] || ADMIN=$(derive 8083 || true)
[ -n "$SITE" ] || SITE=$(derive 8084 || true)
WAIT_S=${SMOKE_WAIT_S:-900}
fail=0
check() { local d=$1; shift; if "$@"; then echo "  ✓ $d"; else echo "  ✗ $d"; fail=1; fi; }
has() { grep -q -- "$2" <<< "$1"; }
lacks() { ! grep -q -- "$2" <<< "$1"; }
eq() { [ "$1" = "$2" ]; }
req() { curl -s --retry 2 --retry-connrefused --max-time 15 "$@"; }
code() { req -o /dev/null -w '%{http_code}' "$@"; }
headers() { req -o /dev/null -D - "$@" | tr -d '\r'; }
field() { sed -n "s/.*\"$1\":\"\\([^\"]*\\)\".*/\\1/p" <<< "$2" | head -1; }

if [ -n "$WANT" ]; then
  echo "等待生产换成提交 ${WANT:0:12}（最多 ${WAIT_S} 秒）……"
  end=$((SECONDS + WAIT_S)); got=""
  while :; do
    got=$(field version "$(curl -s --max-time 15 "$BASE/api/health")")
    [ "$got" = "$WANT" ] && break
    [ "$SECONDS" -lt "$end" ] || break
    sleep 15
  done
  check "生产已换成提交 ${WANT:0:12}（当前：${got:0:12}）" eq "$got" "$WANT"
fi

echo "客户端（地址不输出）"
health=$(curl -s --max-time 15 "$BASE/api/health")
check "健康检查 status=UP" eq "$(field status "$health")" UP
check "不是测试环境（健康检查没有 env、testAdmin）" lacks "$health" '"env"\|"testAdmin"'
check "客户端的健康检查不带业务告警（alerts 只给后台）" lacks "$health" '"alerts"'
check "首页 200" eq "$(code "$BASE/")" 200
h=$(headers "$BASE/")
check "HSTS 1 年、不加 includeSubDomains" bash -c 'grep -qix "strict-transport-security: max-age=31536000" <<< "$1"' _ "$h"
check "客户端不让搜索引擎收录（X-Robots-Tag: noindex）" grep -qi '^x-robots-tag: noindex' <<< "$h"
check "/robots.txt 全部禁止" has "$(req "$BASE/robots.txt")" 'Disallow: /'
check "客户端上后台接口 404" eq "$(code "$BASE/api/admin/me")" 404
check "端到端测试接口 404" eq "$(code "$BASE/api/e2e/code")" 404
check "应用管理端点 404" eq "$(code "$BASE/actuator/health")" 404
check "路径里有分号 400" eq "$(code --path-as-is "$BASE/api/admin;x/me")" 400

if [ -n "$ADMIN" ]; then
  echo "后台（地址不输出）"
  ah=$(curl -s --max-time 15 "$ADMIN/api/health")
  check "后台健康检查 status=UP" eq "$(field status "$ah")" UP
  [ -z "$WANT" ] || check "后台和客户端是同一个版本" eq "$(field version "$ah")" "$WANT"
  check "后台页面 /admin/ 200" eq "$(code "$ADMIN/admin/")" 200
  check "后台不让搜索引擎收录" grep -qi '^x-robots-tag: noindex' <<< "$(headers "$ADMIN/admin/")"
  check "/docs/ 404（开发者文档只在报价对外端口，生产不给看）" eq "$(code "$ADMIN/docs/")" 404
  check "/internal/** 404（报价服务的内部接口不对外）" eq "$(code "$ADMIN/internal/open/overview")" 404
  check "后台域名上客户端接口 404" eq "$(code "$ADMIN/api/auth/me")" 404
else
  echo "::warning::没有后台地址（仓库密钥 PRODUCTION_ADMIN_URL），也推不出来，后台的几项跳过"
fi

if [ -n "$SITE" ]; then
  echo "官网（地址不输出）"
  check "官网首页 200" eq "$(code "$SITE/")" 200
  sh_=$(headers "$SITE/")
  check "官网可以被收录（没有 X-Robots-Tag）" bash -c '! grep -qi "^x-robots-tag:" <<< "$1"' _ "$sh_"
  check "官网 /robots.txt 允许" has "$(req "$SITE/robots.txt")" 'Allow: /'
  q=$(req "$SITE/api/public/quotes")
  check "官网公开行情不显示价格（pricesHidden）" has "$q" '"pricesHidden":true'
  # 只开加密货币（R-1）：列出来的都是 10 个币安品种
  syms=$(grep -o '"symbol":"[A-Z0-9]*"' <<< "$q" | cut -d'"' -f4 | sort | tr '\n' ' ')
  check "官网公开行情只列加密货币（$(wc -w <<< "$syms") 个）" bash -c '[ -n "$1" ] && for s in $1; do case " BTCUSD ETHUSD SOLUSD XRPUSD BNBUSD DOGEUSD ADAUSD LTCUSD DOTUSD BCHUSD " in *" $s "*) ;; *) exit 1 ;; esac; done' _ "$syms"
  check "官网上后台接口 404" eq "$(code "$SITE/api/admin/me")" 404
else
  echo "::warning::没有官网地址（仓库密钥 PRODUCTION_SITE_URL），也推不出来，官网的几项跳过"
fi
exit $fail
