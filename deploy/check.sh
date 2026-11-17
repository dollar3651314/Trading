#!/usr/bin/env bash
# 部署工具包的静态检查（第 4 批）：CI 的 deploy-check job 运行，本机也可以运行：bash deploy/check.sh
#   1. shellcheck（服务器脚本）  2. docker compose config（用 .env.example 的变量名填一次性假值）
#   3. 同版本 nginx 镜像里 nginx -t（四个假域名、临时自签证书）  4. compose 约束：每个容器 mem_limit = memswap_limit、只有 web 映射公网端口、
#      trading-app 只绑 127.0.0.1、quote-app 不映射端口、内存合计 2,208 MB
# 不需要任何密钥；假值只在临时目录里，跑完删掉。
set -euo pipefail
cd "$(dirname "$0")/.."
NGINX_IMAGE=${NGINX_IMAGE:-nginx:1.27-bookworm}
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
fail=0
check() { local d=$1; shift; if "$@"; then echo "  ✓ $d"; else echo "  ✗ $d"; fail=1; fi; }

echo "== shellcheck"
check "服务器脚本没有 shellcheck 警告" shellcheck -S warning deploy/kit/trading deploy/kit/bootstrap.sh deploy/kit/mysql-init/*.sh deploy/check.sh e2e/staging-smoke.sh

echo "== docker compose config"
sed -E 's/^([A-Z0-9_]+)=$/\1=check-only-not-a-secret/' deploy/kit/.env.example > "$tmp/.env"
echo "IMAGE_TAG=0000000000000000000000000000000000000000" > "$tmp/image.env"
cfg() { docker compose --project-name trading --project-directory deploy/kit -f deploy/kit/docker-compose.yml --env-file "$tmp/.env" --env-file "$tmp/image.env" config "$@"; }
check "compose 能解析" cfg -q
cfg --format json > "$tmp/compose.json"
jqc() { jq -e "$1" "$tmp/compose.json" > /dev/null; }
check "每个容器 mem_limit = memswap_limit" jqc '[.services[] | (.mem_limit == .memswap_limit and .mem_limit != null)] | all'
echo "  · 默认内存上限合计 $(jq "[.services[].mem_limit | tonumber] | add / 1048576" "$tmp/compose.json") MB（不含 Redis；可在 .env 用 TR_MEM_* 调）"
check "只有 trading-web 映射公网端口（80、443）" jqc '[.services | to_entries[] | select(.value.ports != null) | .value.ports[] | select((.host_ip // "") != "127.0.0.1") | .published] | sort == ["443","80"]'
check "trading-app 只绑 127.0.0.1:8080" jqc '.services["trading-app"].ports == [{"mode":"ingress","host_ip":"127.0.0.1","target":8080,"published":"8080","protocol":"tcp"}]'
check "quote-app、mysql 不映射端口" jqc '[.services["quote-app","mysql"].ports] | all(. == null)'
check "quote-app 只在 quote 网络的别名上监听" jqc '.services["quote-app"].environment.FX_QUOTE_BIND_ADDRESS == "quote-internal" and (.services["quote-app"].networks.quote.aliases | index("quote-internal"))'
check "mysql 只在不能出公网的 internal 网络" jqc '(.services.mysql.networks | keys) == ["internal"] and .networks.internal.internal == true and .networks.quote.internal == true'
check "测试环境标识和报价模式写死（不是 live）" jqc '.services["trading-app"].environment.FX_ENV_LABEL == "test" and .services["quote-app"].environment.FX_QUOTE_MARKET_SOURCE == "simulated" and .services["trading-app"].environment.FX_QUOTE_MARKET_SOURCE == "simulated"'
# 单独模式（打开自己的 Redis）
jqf() { jq -e "$1" "$2" > /dev/null; }
docker compose --profile own-redis --project-name trading --project-directory deploy/kit -f deploy/kit/docker-compose.yml --env-file "$tmp/.env" --env-file "$tmp/image.env" config --format json > "$tmp/own.json"
check "单独模式：自己的 Redis 只在 internal 网络、不映射端口" jqf '(.services.redis.networks | keys) == ["internal"] and .services.redis.ports == null and .services.redis.mem_limit == .services.redis.memswap_limit' "$tmp/own.json"
# 共机模式（叠加 docker-compose.shared.yml）
docker compose --project-name trading --project-directory deploy/kit -f deploy/kit/docker-compose.yml -f deploy/kit/docker-compose.shared.yml --env-file "$tmp/.env" --env-file "$tmp/image.env" config --format json > "$tmp/shared.json"
check "共机模式：不起自己的 Redis、不占 80/443（只剩 trading-app 的 127.0.0.1:8080）" jqf '(.services.redis == null) and ([.services[] | select(.ports != null) | .ports[] | .host_ip] == ["127.0.0.1"])' "$tmp/shared.json"
check "共机模式：只有 trading-web、trading-app、quote-app 接到 TopUP 网络，mysql 不接" jqf '[.services | to_entries[] | select(.value.networks.shared != null) | .key] | sort == ["quote-app","trading-app","trading-web"]' "$tmp/shared.json"
check "共机模式：TopUP 网络是外部网络（不由 Trading 建、删）" jqf '.networks.shared.external == true' "$tmp/shared.json"
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
