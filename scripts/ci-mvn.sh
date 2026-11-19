#!/usr/bin/env bash
# CI 里运行 Maven（参数原样传给 mvn）。Maven Central 偶尔回 429（限流）或断连：
#   1. .mvn/maven.config 让 Maven 自己对 429、503 和断连重试（最多 4 次，间隔 10 秒起、最长 1 分钟，最坏几分钟，远小于 job 的 20 分钟上限）；
#   2. 还是因为"下载依赖 / 插件失败"而失败时，等 60 秒（CI_MVN_RETRY_WAIT）整条命令再跑一次（只此一次）。
# 什么时候重跑（#25 审查 🟡：不能变相重跑不稳定的测试）：
#   - 只看 Maven 自己的 [ERROR] 行里的下载失败（Could not transfer artifact、Could not resolve dependencies / plugin、
#     Failed to read artifact descriptor、… or one of its dependencies could not be resolved），不看测试和应用自己打的日志；
#   - 日志里只要有测试失败（Tests run: … Failures / Errors 非 0、There are test failures、测试进程出错：in the fork、forked VM terminated、forked process）或编译错误，一律不重跑。
# 测试用例：scripts/ci-mvn-test.sh（CI 的 backend job 先跑它）。
set -uo pipefail
log=$(mktemp)
trap 'rm -f "$log"' EXIT
tests_or_compile_failed() {
  grep -Eq 'Tests run: [0-9]+, Failures: [1-9]|Tests run: [0-9]+, Failures: [0-9]+, Errors: [1-9]|There are test failures|There was a timeout or other error in the fork|in the fork|forked VM terminated|forked process|COMPILATION ERROR|Compilation failure' "$log"
}
transfer_failed() {
  grep -Eq '^\[ERROR\] .*(Could not transfer artifact|Could not resolve dependencies|Could not resolve plugin|Failed to read artifact descriptor|or one of its dependencies could not be resolved)' "$log"
}
should_retry() { transfer_failed && ! tests_or_compile_failed; }
mvn "$@" 2>&1 | tee "$log"
rc=${PIPESTATUS[0]}
if [ "$rc" -ne 0 ] && should_retry; then
  echo "::warning::Maven 下载依赖失败（多半是 Maven Central 限流 429），${CI_MVN_RETRY_WAIT:-60} 秒后整条命令重跑一次"
  sleep "${CI_MVN_RETRY_WAIT:-60}"
  mvn "$@" 2>&1 | tee "$log"
  rc=${PIPESTATUS[0]}
fi
exit "$rc"
