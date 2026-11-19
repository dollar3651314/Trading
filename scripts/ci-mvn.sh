#!/usr/bin/env bash
# CI 里运行 Maven（参数原样传给 mvn）。Maven Central 偶尔回 429（限流）或断连：
#   1. .mvn/maven.config 让 Maven 自己对 429、503 和断连重试（最多 8 次，间隔 10 秒起、最长 2 分钟）；
#   2. 还是因为"下载依赖 / 插件失败"而失败时，等 60 秒（CI_MVN_RETRY_WAIT）整条命令再跑一次（只此一次）。
#      只认下载失败的报错（Could not transfer / resolve、Failed to read artifact descriptor 等），测试失败、编译失败不重跑，测试不放宽。
set -uo pipefail
log=$(mktemp)
trap 'rm -f "$log"' EXIT
transfer_failed() { grep -Eq 'Could not transfer artifact|Could not resolve dependencies|Could not resolve plugin|Failed to read artifact descriptor|Plugin .* or one of its dependencies could not be resolved|status code: (429|5[0-9][0-9])' "$log"; }
mvn "$@" 2>&1 | tee "$log"
rc=${PIPESTATUS[0]}
if [ "$rc" -ne 0 ] && transfer_failed; then
  echo "::warning::Maven 下载依赖失败（多半是 Maven Central 限流 429），60 秒后整条命令重跑一次"
  sleep "${CI_MVN_RETRY_WAIT:-60}"
  mvn "$@" 2>&1 | tee "$log"
  rc=${PIPESTATUS[0]}
fi
exit "$rc"
