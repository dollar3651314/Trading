#!/usr/bin/env bash
# 部署工具包的静态检查（第 4 批）：CI 的 deploy-check job 运行，本机也可以运行：bash deploy/check.sh
#   1. shellcheck（服务器脚本）  2. docker compose config（用 .env.example 的变量名填一次性假值）
#   3. 同版本 nginx 镜像里 nginx -t（四个假域名、临时自签证书）  4. compose 约束：除 mysql 外每个容器 mem_limit = memswap_limit（mysql 正好多 128 MB 交换分区）、只有 web 映射公网端口、
#      trading-app 只绑 127.0.0.1、quote-app 不映射端口、内存合计 964 MB、测试环境开发者文档能看、不让搜索引擎收录
#   5. 真的起一个 Nginx（报价服务用桩代替）按域名发请求：/docs/ 转到报价服务、/internal 不转、/robots.txt 和 X-Robots-Tag（测试环境验收 T-1、T-2）
# 不需要任何密钥；假值只在临时目录里，跑完删掉。
set -euo pipefail
cd "$(dirname "$0")/.."
NGINX_IMAGE=${NGINX_IMAGE:-nginx:1.27-bookworm}
tmp=$(mktemp -d)
net=trading-check-$$
cleanup() { docker rm -f "$net-web" "$net-quote" > /dev/null 2>&1 || true; docker network rm "$net" > /dev/null 2>&1 || true; rm -rf "$tmp"; }
trap cleanup EXIT
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
check "备份导出超时（默认 300 秒）明显小于自动更新服务的 15 分钟" bash -c 'grep -q "^DUMP_TIMEOUT=\${TR_DUMP_TIMEOUT:-300}$" deploy/kit/trading && grep -q "^TimeoutStartSec=15min$" deploy/kit/systemd/trading-deploy.service'
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
check "报价源授权检查关（FX_QUOTE_LICENSE_ENFORCE 写死 \"false\"，Amos 2026-11-17）" jqc '.services["quote-app"].environment.FX_QUOTE_LICENSE_ENFORCE == "false"'
# 测试环境验收 T-1：FX_QUOTE_OPEN_DOCS=auto 在"配了任何真实报价源"时也藏文档，测试环境币安默认打开（TD4-11），所以必须写死 true
check "测试环境开发者文档 /docs/ 能看（FX_QUOTE_OPEN_DOCS 写死 true，不能是 auto）" jqc '.services["quote-app"].environment.FX_QUOTE_OPEN_DOCS == "true"'
check "测试环境官网不让搜索引擎收录（trading-web 的 FX_NGINX_NOINDEX 写死 true）" jqc '.services["trading-web"].environment.FX_NGINX_NOINDEX == "true"'
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
check "web.Dockerfile 给了 9 个限流阈值和不让收录开关（默认 true）" bash -c 'test "$1" -eq 20 && grep -q "^ENV FX_NGINX_NOINDEX=true$" deploy/web.Dockerfile' _ "${#envs[@]}"
check "报价子域名不转发 /internal" grep -q 'location ~\* "^/+(%2f)\*internal" { return 404; }' deploy/nginx/site.conf.template

echo "== Nginx 路由（真的起 Nginx，报价服务用桩代替，按域名发请求）"
# 桩：在 quote 网络别名 quote-internal:8090 上，回"quote-app:<路径>"，用来看请求到没到报价服务
cat > "$tmp/stub.conf" << 'STUB'
server { listen 8090; default_type text/plain; location / { return 200 "quote-app:$uri"; } }
STUB
mkdir -p "$tmp/www/client" "$tmp/www/site" "$tmp/www/admin"
for d in client site admin; do echo "<!doctype html><title>$d</title>" > "$tmp/www/$d/index.html"; done
docker network create "$net" > /dev/null
docker run -d --name "$net-quote" --network "$net" --network-alias quote-internal -v "$tmp/stub.conf:/etc/nginx/conf.d/default.conf:ro" "$NGINX_IMAGE" > /dev/null
web() {
  docker rm -f "$net-web" > /dev/null 2>&1 || true
  docker run -d --name "$net-web" --network "$net" -e APP_DOMAIN=app.check.invalid -e ADMIN_DOMAIN=ops.check.invalid -e WWW_DOMAIN=www.check.invalid -e QUOTE_DOMAIN=quote.check.invalid "${envs[@]}" "$@" \
    -v "$PWD/deploy/nginx/nginx.conf:/etc/nginx/nginx.conf:ro" -v "$tmp/templates:/etc/nginx/templates:ro" -v "$tmp/snippets:/etc/nginx/snippets:ro" -v "$tmp/le:/etc/letsencrypt:ro" \
    -v "$tmp/www/client:/usr/share/nginx/client:ro" -v "$tmp/www/site:/usr/share/nginx/site:ro" -v "$tmp/www/admin:/usr/share/nginx/admin:ro" "$NGINX_IMAGE" > /dev/null
  for _ in $(seq 1 30); do docker exec "$net-web" curl -sk -o /dev/null --resolve app.check.invalid:443:127.0.0.1 https://app.check.invalid/ 2> /dev/null && return 0; sleep 0.5; done
  docker logs "$net-web" 2>&1 | tail -20; return 1
}
# req 域名前缀 路径 → 状态码、X-Robots-Tag、正文（一行一个）；secure 域名前缀 路径 → 响应里还有 security.inc 的 HSTS、X-Frame-Options
# （防止以后有人在 location 里加 add_header，Nginx 就不再继承 server 级的 add_header）
req() { docker exec "$net-web" curl -sk -D "/tmp/h" -o "/tmp/b" --resolve "$1.check.invalid:443:127.0.0.1" "https://$1.check.invalid$2" -w '%{http_code}\n' && docker exec "$net-web" sh -c 'grep -i "^x-robots-tag:" /tmp/h | cut -d" " -f2- | tr -d "\r"; echo; cat /tmp/b'; }
is() { local want=$1; shift; [ "$(req "$@")" = "$want" ]; }
secure() { req "$@" > /dev/null && docker exec "$net-web" sh -c 'grep -qi "^strict-transport-security: max-age=" /tmp/h && grep -qi "^x-frame-options: DENY" /tmp/h'; }
has() { local want=$1 out; shift; out=$(req "$@") && grep -qF -- "$want" <<< "$out"; }
nl=$'\n'
if web; then
  check "报价子域名 /docs/ 转到报价服务" is "200${nl}noindex, nofollow${nl}${nl}quote-app:/docs/" quote /docs/
  check "报价子域名 /docs 跳到 /docs/" has "301" quote /docs
  check "报价子域名 /v1/** 转到报价服务" has "quote-app:/v1/symbols" quote /v1/symbols
  check "报价子域名 /internal/** 不转发（404、没到报价服务）" bash -c '! docker exec "$1" curl -sk --resolve quote.check.invalid:443:127.0.0.1 https://quote.check.invalid/internal/open/overview -w "%{http_code}" | grep -q "quote-app" && [ "$(docker exec "$1" curl -sk -o /dev/null --resolve quote.check.invalid:443:127.0.0.1 https://quote.check.invalid/internal/open/overview -w "%{http_code}")" = 404 ]' _ "$net-web"
  for h in app ops www quote; do
    check "测试环境 $h /robots.txt 全部禁止、带 X-Robots-Tag: noindex" is "200${nl}noindex, nofollow${nl}${nl}User-agent: *${nl}Disallow: /" "$h" /robots.txt
  done
  check "客户端首页带 X-Robots-Tag: noindex" has "noindex, nofollow" app /
  check "官网子页（单页应用回 index.html）带 X-Robots-Tag: noindex" has "noindex, nofollow" www /terms
  check "后台页面带 X-Robots-Tag: noindex" has "noindex, nofollow" ops /admin/
  for h in app ops www quote; do
    check "$h /robots.txt 仍带 HSTS、X-Frame-Options（location 里没有覆盖 add_header）" secure "$h" /robots.txt
  done
  check "报价子域名 /docs/ 仍带 HSTS、X-Frame-Options" secure quote /docs/
  check "报价子域名 /v1/** 仍带 HSTS、X-Frame-Options" secure quote /v1/symbols
else
  check "Nginx 能起来（FX_NGINX_NOINDEX=true）" false
fi
if web -e FX_NGINX_NOINDEX=false; then
  check "FX_NGINX_NOINDEX=false：官网 /robots.txt 全部允许、不发 X-Robots-Tag" is "200${nl}${nl}User-agent: *${nl}Allow: /" www /robots.txt
  check "FX_NGINX_NOINDEX=false：官网首页不发 X-Robots-Tag" is "200${nl}${nl}<!doctype html><title>site</title>" www /
  check "FX_NGINX_NOINDEX=false：官网 /robots.txt 仍带 HSTS、X-Frame-Options" secure www /robots.txt
  for h in app ops quote; do
    check "FX_NGINX_NOINDEX=false：$h 照样不收录（/robots.txt 全部禁止、带 X-Robots-Tag）" is "200${nl}noindex, nofollow${nl}${nl}User-agent: *${nl}Disallow: /" "$h" /robots.txt
  done
  check "FX_NGINX_NOINDEX=false：客户端首页照样带 X-Robots-Tag" has "noindex, nofollow" app /
  check "FX_NGINX_NOINDEX=false：报价子域名 /docs/ 照样带 X-Robots-Tag" has "noindex, nofollow" quote /docs/
else
  check "Nginx 能起来（FX_NGINX_NOINDEX=false）" false
fi
exit $fail
