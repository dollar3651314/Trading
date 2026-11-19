#!/usr/bin/env bash
# scripts/ci-mvn.sh 的用例：用假的 mvn（按 MODE 打出不同的日志），看重跑了几次、退出码对不对。不需要网络和 Maven。
#   bash scripts/ci-mvn-test.sh
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
cat > "$tmp/mvn" << 'FAKE'
#!/usr/bin/env bash
n=$(($(cat "$FAKE_COUNT" 2> /dev/null || echo 0) + 1)); echo "$n" > "$FAKE_COUNT"
dl='[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.6:test (default-test) on project backend: Execution default-test of goal org.apache.maven.plugins:maven-surefire-plugin:3.5.6:test failed: Plugin org.apache.maven.plugins:maven-surefire-plugin:3.5.6 or one of its dependencies could not be resolved: Could not transfer artifact org.apache.maven.surefire:surefire-junit-platform:jar:3.5.6 from/to central (https://repo.maven.apache.org/maven2): status code: 429, reason phrase: Too Many Requests (429)'
case "$MODE" in
  ok) echo "[INFO] BUILD SUCCESS"; exit 0 ;;
  download-once) if [ "$n" -eq 1 ]; then echo "$dl"; exit 1; fi; echo "[INFO] BUILD SUCCESS"; exit 0 ;;
  download-always) echo "$dl"; exit 1 ;;
  test-503)
    echo "2026-11-19T10:00:00Z  WARN 1 --- [main] a.t.p.PaymentService : TopUP 返回 status code: 503 Service Unavailable"
    echo "[ERROR] Tests run: 12, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 3.2 s <<< FAILURE! -- in app.X"
    echo "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.6:test (default-test) on project backend: There are test failures."
    exit 1 ;;
  test-error-transfer-text)
    echo "java.lang.IllegalStateException: Could not transfer artifact x (from a test)"
    echo "[ERROR] Tests run: 3, Failures: 0, Errors: 1, Skipped: 0"
    echo "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.6:test (default-test) on project backend: There are test failures."
    exit 1 ;;
  test-and-download) echo "[ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0"; echo "$dl"; exit 1 ;;
  compile) echo "[ERROR] COMPILATION ERROR :"; echo "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.15.0:compile (default-compile) on project backend: Compilation failure"; echo "$dl"; exit 1 ;;
  fork) echo "[ERROR] There was a timeout or other error in the fork"; echo "$dl"; exit 1 ;;
esac
FAKE
chmod +x "$tmp/mvn"
fail=0
case_() { # case_ 说明 MODE 期望退出码 期望 mvn 跑几次
  local d=$1 mode=$2 want_rc=$3 want_runs=$4 rc runs
  rm -f "$tmp/count"
  PATH="$tmp:$PATH" MODE="$mode" FAKE_COUNT="$tmp/count" CI_MVN_RETRY_WAIT=0 bash scripts/ci-mvn.sh -B verify > "$tmp/out" 2>&1
  rc=$?; runs=$(cat "$tmp/count")
  if [ "$rc" = "$want_rc" ] && [ "$runs" = "$want_runs" ]; then echo "  ✓ $d"; else echo "  ✗ $d（退出码 $rc、跑了 $runs 次；期望 $want_rc、$want_runs 次）"; fail=1; fi
}
echo "== scripts/ci-mvn.sh"
case_ "成功：只跑一次" ok 0 1
case_ "下载插件依赖 429：重跑一次后成功" download-once 0 2
case_ "一直下载失败：只重跑一次，照样失败" download-always 1 2
case_ "测试失败、日志里有 status code: 503：不重跑" test-503 1 1
case_ "测试出错、测试自己的日志里有 Could not transfer：不重跑" test-error-transfer-text 1 1
case_ "测试失败、同时有下载失败的 [ERROR]：不重跑" test-and-download 1 1
case_ "编译错误、同时有下载失败的 [ERROR]：不重跑" compile 1 1
case_ "测试进程出错、同时有下载失败的 [ERROR]：不重跑" fork 1 1
exit $fail
