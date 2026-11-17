#!/usr/bin/env bash
# 部署工具包的静态检查（第 4 批）：CI 的 deploy-check job 运行，本机也可以运行：bash deploy/check.sh
#   1. shellcheck（服务器脚本）  2. docker compose config（用 .env.example 的变量名填一次性假值）
#   3. 同版本 nginx 镜像里 nginx -t（四个假域名、临时自签证书）  4. compose 约束：除 mysql 外每个容器 mem_limit = memswap_limit（mysql 正好多 128 MB 交换分区）、只有 web 映射公网端口、
#      trading-app 只绑 127.0.0.1、quote-app 不映射端口、内存合计 964 MB
# 不需要任何密钥；假值只在临时目录里，跑完删掉。
set -euo pipefail
cd "$(dirname "$0")/.."
NGINX_IMAGE=${NGINX_IMAGE:-nginx:1.27-bookworm}
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
fail=0
check() { local d=$1; shift; if "$@"; then echo "  ✓ $d"; else echo "  ✗ $d"; fail=1; fi; }

echo "== shellcheck"
check "服务器脚本没有 shellcheck 警告" shellcheck -S warning deploy/kit/trading deploy/kit/bootstrap.sh deploy/kit/backup.sh deploy/kit/mysql-init/*.sh deploy/check.sh e2e/staging-smoke.sh

echo "== docker compose config"
sed -E 's/^([A-Z0-9_]+)=$/\1=check-only-not-a-secret/' deploy/kit/.env.example > "$tmp/.env"
echo "IMAGE_TAG=0000000000000000000000000000000000000000" > "$tmp/image.env"
cfg() { docker compose --project-name trading --project-directory deploy/kit -f deploy/kit/docker-compose.yml --env-file "$tmp/.env" --env-file "$tmp/image.env" config "$@"; }
check "compose 能解析" cfg -q
cfg --format json > "$tmp/compose.json"
jqc() { jq -e "$1" "$tmp/compose.json" > /dev/null; }
check "除 mysql 外每个容器 mem_limit = memswap_limit（不用交换分区）" jqc '[.services | to_entries[] | select(.key != "mysql") | .value | (.mem_limit == .memswap_limit and .mem_limit != null)] | all'
check "mysql 内存 320 MB、交换分区正好 128 MB（memswap_limit 448）" jqc '(.services.mysql.mem_limit | tonumber) == (320 * 1024 * 1024) and ((.services.mysql.memswap_limit | tonumber) - (.services.mysql.mem_limit | tonumber)) == (128 * 1024 * 1024)'
check "默认内存上限合计 964 MB（+ 容器运行开销约 36 = 启动门槛 1,000；Amos 批准的 C75 例外，见 docker-compose.yml 开头）" jqc '([.services[].mem_limit | tonumber] | add) == (964 * 1024 * 1024)'
check "启动门槛默认 1,000 MB、恢复演练临时库默认 320 MB" bash -c 'grep -q "^MIN_MEM_MB=\${TR_MIN_MEM_MB:-1000}$" deploy/kit/trading && grep -q "^DRILL_MEM_MB=\${TR_DRILL_MEM_MB:-320}$" deploy/kit/trading'
check "JVM 堆 + 元空间 + 代码缓存 + 直接内存 < 容器上限（trading-app 304 < 348、quote-app 196 < 264）" jqc '(.services["trading-app"].mem_limit | tonumber) == (348 * 1024 * 1024) and (.services["quote-app"].mem_limit | tonumber) == (264 * 1024 * 1024)'
check "JVM 各区的默认值没变（和上一条的 304、196 对得上）" bash -c 'grep -q "Xmx\${TR_HEAP_APP:-144m} -XX:MaxMetaspaceSize=\${TR_META_APP:-104m} -XX:ReservedCodeCacheSize=\${TR_CODE_APP:-40m} -XX:MaxDirectMemorySize=\${TR_DIRECT_APP:-16m}" deploy/kit/docker-compose.yml && grep -q "Xmx\${TR_HEAP_QUOTE:-64m} -XX:MaxMetaspaceSize=\${TR_META_QUOTE:-96m} -XX:ReservedCodeCacheSize=\${TR_CODE_QUOTE:-28m} -XX:MaxDirectMemorySize=\${TR_DIRECT_QUOTE:-8m}" deploy/kit/docker-compose.yml'
check "只有 trading-web 映射公网端口（80、443）" jqc '[.services | to_entries[] | select(.value.ports != null) | .value.ports[] | select((.host_ip // "") != "127.0.0.1") | .published] | sort == ["443","80"]'
check "trading-app 只绑 127.0.0.1:8080" jqc '.services["trading-app"].ports == [{"mode":"ingress","host_ip":"127.0.0.1","target":8080,"published":"8080","protocol":"tcp"}]'
check "quote-app、mysql、redis 不映射端口" jqc '[.services["quote-app","mysql","redis"].ports] | all(. == null)'
check "quote-app 只在 quote 网络的别名上监听" jqc '.services["quote-app"].environment.FX_QUOTE_BIND_ADDRESS == "quote-internal" and (.services["quote-app"].networks.quote.aliases | index("quote-internal"))'
check "mysql、redis 只在不能出公网的 internal 网络" jqc '(.services.mysql.networks | keys) == ["internal"] and (.services.redis.networks | keys) == ["internal"] and .networks.internal.internal == true and .networks.quote.internal == true'
check "测试环境标识和报价模式写死（不是 live）" jqc '.services["trading-app"].environment.FX_ENV_LABEL == "test" and .services["quote-app"].environment.FX_QUOTE_MARKET_SOURCE == "simulated" and .services["trading-app"].environment.FX_QUOTE_MARKET_SOURCE == "simulated"'
check "Redis 只在 internal 网络、不映射端口" jqc '(.services.redis.networks | keys) == ["internal"] and .services.redis.ports == null'
check "给 TopUP 的约定：trading-deploy 定时器的服务指向 /opt/trading/、有 backup.sh" bash -c 'grep -q "^ExecStart=/opt/trading/" deploy/kit/systemd/trading-deploy.service && test -x deploy/kit/backup.sh'
check "内存守护定时器在、trading on 会打开它" bash -c 'grep -q "^ExecStart=/opt/trading/trading memguard" deploy/kit/systemd/trading-memguard.service && grep -q "trading-memguard.timer" deploy/kit/trading'
check "trading-web 只挂 Trading 自己的证书目录（不挂整个 /etc/letsencrypt）" jqc '[.services[].volumes[]? | select(.source | startswith("/etc/letsencrypt")) | .source] | sort == ["/etc/letsencrypt/archive/trading","/etc/letsencrypt/live/trading"]'
check "compose 里没有 FX_E2E_ENABLED、e2e profile" bash -c '! grep -Eq "FX_E2E_ENABLED|SPRING_PROFILES_ACTIVE" deploy/kit/docker-compose.yml'

echo "== nginx -t（$NGINX_IMAGE）"
mkdir -p "$tmp/le/live/trading" "$tmp/snippets"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=check.invalid -keyout "$tmp/le/live/trading/privkey.pem" -out "$tmp/le/live/trading/fullchain.pem" 2> /dev/null
cp deploy/nginx/*.inc "$tmp/snippets/"
mkdir -p "$tmp/templates"; cp deploy/nginx/site.conf.template "$tmp/templates/"
envs=()
while read -r line; do envs+=(-e "$line"); done < <(grep -o 'FX_NGINX_[A-Z_]*=[^ ]*' deploy/web.Dockerfile)
check "nginx -t 通过" docker run --rm -e APP_DOMAIN=app.check.invalid -e ADMIN_DOMAIN=ops.check.invalid -e WWW_DOMAIN=www.check.invalid -e QUOTE_DOMAIN=quote.check.invalid "${envs[@]}" \
  -v "$PWD/deploy/nginx/nginx.conf:/etc/nginx/nginx.conf:ro" -v "$tmp/templates:/etc/nginx/templates:ro" -v "$tmp/snippets:/etc/nginx/snippets:ro" \
  -v "$tmp/le:/etc/letsencrypt:ro" "$NGINX_IMAGE" nginx -t
check "web.Dockerfile 给了 9 个限流阈值" test "${#envs[@]}" -eq 18
check "报价子域名不转发 /internal" grep -q 'location ~\* "^/+(%2f)\*internal" { return 404; }' deploy/nginx/site.conf.template
exit $fail
