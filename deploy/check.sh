#!/usr/bin/env bash
# 部署工具包的静态检查（第 4 批）：CI 的 deploy-check job 运行，本机也可以运行：bash deploy/check.sh
#   1. shellcheck（服务器脚本）  2. docker compose config（用 .env.example 的变量名填一次性假值）
#   3. 同版本 nginx 镜像里 nginx -t（四个假域名、临时自签证书）  4. compose 约束：每个容器 mem_limit = memswap_limit（都不用交换分区）、MySQL 内存参数、只有 web 映射公网端口、
#      trading-app 只绑 127.0.0.1、quote-app 不映射端口、内存合计 964 MB、测试环境开发者文档能看、不让搜索引擎收录
#   6. 用假的 docker（events 失败）跑 trading watch、mem：Docker 没回应时也要走完（不被 set -e 中途打断）、watch 照样走到发邮件
#   5. 真的起一个 Nginx（报价服务用桩代替）按域名发请求：/docs/ 转到报价服务、/internal 不转、/robots.txt 和 X-Robots-Tag（测试环境验收 T-1、T-2）
#   7. 生产（生产方案 v1 07 页 R-2、R-3、R-4）：叠加 docker-compose.production.yml 后的约束（端口、内存合计 2,432、live、不收录只放开官网……）；
#      平台网关模式的 Nginx（gateway.conf.template）真的起来按端口发请求（Host、X-Forwarded-Proto 固定、HSTS 1 年、真实 IP 开关）；
#      trading 命令生产模式的规矩（从不调 topup、拒绝测试管理员、环境不能改、停着时 set 只保存……）
# 不需要任何密钥；假值只在临时目录里，跑完删掉。
set -euo pipefail
cd "$(dirname "$0")/.."
NGINX_IMAGE=${NGINX_IMAGE:-nginx:1.27-bookworm}
tmp=$(mktemp -d)
net=trading-check-$$
cleanup() { docker rm -f "$net-web" "$net-quote" "$net-gw" "$net-app" > /dev/null 2>&1 || true; docker network rm "$net" > /dev/null 2>&1 || true; rm -rf "$tmp"; }
trap cleanup EXIT
fail=0
check() { local d=$1; shift; if "$@"; then echo "  ✓ $d"; else echo "  ✗ $d"; fail=1; fi; }

echo "== shellcheck"
check "服务器脚本没有 shellcheck 警告" shellcheck -S warning deploy/kit/trading deploy/kit/bootstrap.sh deploy/kit/backup.sh deploy/kit/mysql-init/*.sh deploy/check.sh deploy/drill-production.sh e2e/staging-smoke.sh e2e/production-smoke.sh
check "网关模式的 Nginx 启动脚本（sh）没有 shellcheck 警告" shellcheck -S warning -s sh deploy/nginx/trading-gateway.envsh

echo "== docker compose config"
sed -E 's/^([A-Z0-9_]+)=$/\1=check-only-not-a-secret/' deploy/kit/.env.example > "$tmp/.env"
# 不是密钥、有格式要求的几项留空（和服务器上没填时一样）
sed -i -E 's/^(TR_ENV|TR_QUOTE_BIND|TR_REAL_IP_FROM|TR_REAL_IP_HEADER|TR_SHARE_WITH_TOPUP)=.*/\1=/' "$tmp/.env"
echo "IMAGE_TAG=0000000000000000000000000000000000000000" > "$tmp/image.env"
cfg() { docker compose --project-name trading --project-directory deploy/kit -f deploy/kit/docker-compose.yml --env-file "$tmp/.env" --env-file "$tmp/image.env" config "$@"; }
check "compose 能解析" cfg -q
cfg --format json > "$tmp/compose.json"
jqc() { jq -e "$1" "$tmp/compose.json" > /dev/null; }
check "每个容器 mem_limit = memswap_limit（都不用交换分区；11/18 服务器 vm.swappiness=0，mysql 的 128 MB 交换额度用不上）" jqc '[.services[] | (.mem_limit == .memswap_limit and .mem_limit != null)] | all'
check "mysql 内存 272 MB、memswap_limit 正好 272 MB（11/18 从 320 降，给 quote-app）" jqc '(.services.mysql.mem_limit | tonumber) == (272 * 1024 * 1024) and (.services.mysql.memswap_limit | tonumber) == (272 * 1024 * 1024)'
check "mysql 内存参数（连接数 18、临时表 32M、表缓存 256×1、表定义 400、线程缓存 4、日志缓冲 4M、键缓冲 1M、不反查主机名）" jqc '.services.mysql.command as $c | ["--max-connections=18","--innodb-log-buffer-size=4M","--key-buffer-size=1M","--table-open-cache=256","--table-open-cache-instances=1","--table-definition-cache=400","--thread-cache-size=4","--temptable-max-ram=32M","--skip-name-resolve","--host-cache-size=0","--performance-schema=OFF"] | all(. as $f | $c | index($f))'
check "MySQL 连接数够两个连接池 + 备份 + 健康检查（max-connections ≥ TR_DB_POOL_APP + TR_DB_POOL_QUOTE + 2）" jqc '((.services.mysql.command[] | select(startswith("--max-connections=")) | ltrimstr("--max-connections=") | tonumber) >= ((.services["trading-app"].environment.FX_DB_POOL_SIZE | tonumber) + (.services["quote-app"].environment.FX_QUOTE_DB_POOL_SIZE | tonumber) + 2))'
check "trading 脚本里每处 docker compose exec -T 都把标准输入接到 /dev/null 或管道（T-7：套了 timeout 时在终端里会被 SIGTTIN 停住）" bash -c 'awk '\''/exec -T/ && !/^[[:space:]]*#/ && !/\| *timeout/ { s = ""; for (i = 0; i < 9; i++) { s = s $0; if ($0 ~ /< \/dev\/null/) break; if ((getline) <= 0) break } if (s !~ /< \/dev\/null/) { print "  没有 < /dev/null：" NR; bad = 1 } } END { exit bad }'\'' deploy/kit/trading'

check "默认内存上限合计 964 MB（+ 容器运行开销约 36 = 启动门槛 1,000；Amos 批准的 C75 例外，见 docker-compose.yml 开头）" jqc '([.services[].mem_limit | tonumber] | add) == (964 * 1024 * 1024)'
check "启动门槛默认 1,000 MB、恢复演练临时库默认 320 MB" bash -c 'grep -q "^MIN_MEM_MB=\${TR_MIN_MEM_MB:-1000}$" deploy/kit/trading && grep -q "^DRILL_MEM_MB=\${TR_DRILL_MEM_MB:-320}$" deploy/kit/trading'
check "备份导出超时（默认 300 秒）明显小于自动更新服务的 15 分钟" bash -c 'grep -q "^DUMP_TIMEOUT=\${TR_DUMP_TIMEOUT:-300}$" deploy/kit/trading && grep -q "^TimeoutStartSec=15min$" deploy/kit/systemd/trading-deploy.service'
check "JVM 堆 + 元空间 + 代码缓存 + 直接内存再留约 45 以上给 JVM 其他部分（trading-app 304 + 44 = 348、quote-app 196 + 116 = 312）" jqc '(.services["trading-app"].mem_limit | tonumber) == (348 * 1024 * 1024) and (.services["quote-app"].mem_limit | tonumber) == (312 * 1024 * 1024)'
check "两个 JVM 都每 5 秒把 glibc 空着的内存还给系统（-XX:TrimNativeHeapInterval=5000，11/18：quote-app anon 232 → 206）" bash -c '[ "$(grep -c -- "-XX:+ExitOnOutOfMemoryError -XX:TrimNativeHeapInterval=5000$" deploy/kit/docker-compose.yml)" = 2 ]'
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
check "web.Dockerfile 给了 9 个限流阈值、不让收录开关（默认 true）、官网去机器人的两个保底值（默认不去）" bash -c 'test "$1" -eq 24 && grep -q "^ENV FX_NGINX_NOINDEX=true$" deploy/web.Dockerfile && grep -q "^ENV FX_NGINX_BOT_URL=none FX_NGINX_TG_ONLY=false$" deploy/web.Dockerfile' _ "${#envs[@]}"
check "报价子域名不转发 /internal" grep -q 'location ~\* "^/+(%2f)\*internal" { return 404; }' deploy/nginx/site.conf.template
check "申请冻结提现：提交（每秒 20、突发 40、连接 40）和验证码（每秒 10、突发 20、连接 20）各自一档，两个模板都有（4c 复审 🔴D1、#44 复审：网关地址共用时不能低到一个人就挡住所有人）" bash -c 'for f in deploy/nginx/site.conf.template deploy/nginx/gateway.conf.template; do grep -q "zone=tr_freeze:1m rate=20r/s;" "$f" && grep -q "zone=tr_captcha:1m rate=10r/s;" "$f" && grep -A2 -F "location = /api/public/withdraw-freeze {" "$f" | grep -q "limit_req zone=tr_freeze burst=40 nodelay;" && grep -A3 -F "location = /api/public/withdraw-freeze {" "$f" | grep -q "limit_conn tr_freeze_conn 40;" && grep -A2 -F "location = /api/public/withdraw-freeze/captcha {" "$f" | grep -q "limit_req zone=tr_captcha burst=20 nodelay;" && grep -A3 -F "location = /api/public/withdraw-freeze/captcha {" "$f" | grep -q "limit_conn tr_captcha_conn 20;" || exit 1; done'

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

echo "== trading watch / mem：Docker 没回应时不中途退出（#19 审查 🔴）"
# 假的 docker：events 按 FAKE_EVENTS_RC 失败（1 = 守护进程停了，124 = 超时）；compose ps 说 mysql 在跑、compose exec 回两行计数；其他一律失败
mkdir -p "$tmp/fakebin" "$tmp/tr/state" "$tmp/tr/logs"
cat > "$tmp/fakebin/docker" << 'FAKE'
#!/usr/bin/env bash
case "$1" in
  events) exit "${FAKE_EVENTS_RC:-1}" ;;
  # compose exec：FAKE_SQL_LOG 时把参数记下来（应急开关的 SQL）；查应急开关状态时回 FAKE_EM_STATE（默认 0 = 关）
  compose) case " $* " in *" ps "*) echo fake-mysql-id ;; *" exec "*) [ -z "${FAKE_SQL_LOG:-}" ] || printf '%s\n' "$*" >> "$FAKE_SQL_LOG"
      # 应急开关的写事务回 FAKE_EM_CHANGED（默认 1 = 真的改了）；cancel-recovery 的写事务回请求编号 7，查结果回 FAKE_CANCEL_RESULT（默认 CANCELLED）
      case "$*" in *"SELECT COALESCE((SELECT turned_on"*) echo "${FAKE_EM_STATE:-0}" ;; *"START TRANSACTION; SET @cur"*) echo "${FAKE_EM_CHANGED:-1}" ;;
        *"account_recovery_cancel_request (recovery_id"*) echo 7 ;; *"SELECT COALESCE(result"*) echo "${FAKE_CANCEL_RESULT-CANCELLED}" ;;
        *START\ TRANSACTION*) ;; *) printf 'Uptime\t5\nmax_connections\t18\n' ;; esac ;; esac; exit 0 ;;
  # 轮流 / 独立模式的演练用：FAKE_TOPUP_RUNNING=1 时说 TopUP 的容器在跑；Trading 的容器一律"没在跑"
  ps) case " $* " in *project=topup*) [ -z "${FAKE_TOPUP_RUNNING:-}" ] || echo fake-topup-id ;; *project=trading*) [ -z "${FAKE_TRADING_RUNNING:-}" ] || echo fake-trading-id ;; esac; exit 0 ;;
  # 报价服务容器的健康状态（wait_healthy 用）：FAKE_QUOTE_HEALTHY=1 时 healthy
  inspect) [ -n "${FAKE_QUOTE_HEALTHY:-}" ] && echo healthy; exit 0 ;;
  rm) exit 0 ;;
  # Trading 的 Docker 网络的子网（真实 IP 重叠检查用）：FAKE_SUBNETS
  network) echo "${FAKE_SUBNETS:-}"; exit 0 ;;
  # 镜像的版本号（trading update 用）：FAKE_REVISION
  image) [ -n "${FAKE_REVISION:-}" ] && echo "$FAKE_REVISION"; exit 0 ;;
  *) exit 1 ;;
esac
FAKE
chmod +x "$tmp/fakebin/docker"; : > "$tmp/tr/.env"; : > "$tmp/tr/state/image.env"
for rc in 1 124; do
  rm -f "$tmp/tr/state/watch" "$tmp/tr/logs/trading.log"
  check "watch（docker events 退出码 $rc）：照样走完、记下问题、走到发邮件" bash -c 'tr_out=$("$@" 2>&1) || { echo "$tr_out"; exit 1; }; grep -q "Docker 没回应" "$0/state/watch" && grep -q "trading-app 没响应或不健康" "$0/state/watch" && grep -q "Trading 告警" "$0/logs/trading.log"' "$tmp/tr" env PATH="$tmp/fakebin:$PATH" TR_DIR="$tmp/tr" TR_OFFLINE_TEST=1 FAKE_EVENTS_RC="$rc" bash deploy/kit/trading watch
  check "mem（docker events 退出码 $rc）：照样打出被杀记录\"未知\"和后面的 MySQL 计数" bash -c 'out=$("$@" 2>&1) || exit 1; grep -q "按内存超限杀掉：未知" <<< "$out" && grep -q "Uptime" <<< "$out"' _ env PATH="$tmp/fakebin:$PATH" TR_DIR="$tmp/tr" TR_OFFLINE_TEST=1 FAKE_EVENTS_RC="$rc" bash deploy/kit/trading mem
done

echo "== trading on / off：独立模式（默认）不碰 TopUP，轮流模式才调 topup stop / start（11/18 TopUP 测试环境搬走）"
# 假的 topup 命令：把收到的子命令记下来
cat > "$tmp/fakebin/topup" << 'FAKE'
#!/usr/bin/env bash
echo "$1" >> "${FAKE_TOPUP_LOG:?}"
exit 0
FAKE
chmod +x "$tmp/fakebin/topup"; echo IMAGE_TAG=0000000000000000000000000000000000000000 > "$tmp/tr/state/image.env"
tr_mode() { # tr_mode <独立|轮流> <TopUP 容器在跑：空或 1> <on|off> → 输出 + 最后一行"topup 调用：…"
  if [ "$1" = 轮流 ]; then echo TR_SHARE_WITH_TOPUP=true > "$tmp/tr/.env"; else : > "$tmp/tr/.env"; fi
  : > "$tmp/topup-calls"
  env PATH="$tmp/fakebin:$PATH" TR_DIR="$tmp/tr" TR_OFFLINE_TEST=1 TR_TOPUP_BIN="$tmp/fakebin/topup" FAKE_TOPUP_LOG="$tmp/topup-calls" FAKE_TOPUP_RUNNING="$2" \
    bash deploy/kit/trading "$3" 2>&1
  echo "退出码：$?"
  echo "topup 调用：$(tr '\n' ' ' < "$tmp/topup-calls")"
}
export -f tr_mode; export tmp
check "独立模式 off：不启动 TopUP（旧的 topup 命令还在也不调），正常结束" bash -c 'o=$(tr_mode 独立 "" off); grep -q "退出码：0" <<< "$o" && grep -q "topup 调用： *$" <<< "$o" && grep -q "独立模式" <<< "$o"'
check "轮流模式 off：最后调 topup start" bash -c 'o=$(tr_mode 轮流 "" off); grep -q "退出码：0" <<< "$o" && grep -q "topup 调用：start" <<< "$o"'
check "独立模式 on、TopUP 的容器还在跑：不调 topup stop、不启动 Trading" bash -c 'o=$(tr_mode 独立 1 on); ! grep -q "退出码：0" <<< "$o" && grep -q "topup 调用： *$" <<< "$o" && grep -q "TopUP 的容器还在跑" <<< "$o"'
check "轮流模式 on、TopUP 在跑：先调 topup stop" bash -c 'o=$(tr_mode 轮流 1 on); grep -q "topup 调用：stop" <<< "$o"'
check "模式开关只认 true / false" bash -c '! env PATH="$tmp/fakebin:$PATH" TR_DIR="$tmp/tr" TR_OFFLINE_TEST=1 bash deploy/kit/trading set TR_SHARE_WITH_TOPUP yes > /dev/null 2>&1 && env PATH="$tmp/fakebin:$PATH" TR_DIR="$tmp/tr" TR_OFFLINE_TEST=1 bash deploy/kit/trading set TR_SHARE_WITH_TOPUP false > /dev/null 2>&1 && grep -q "^TR_SHARE_WITH_TOPUP=false$" "$tmp/tr/.env"'

echo "== 生产：叠加 docker-compose.production.yml 后的约束（生产方案 v1 03、04 页，07 页 R-2、R-3、R-4）"
cfgp() { docker compose --project-name trading --project-directory deploy/kit -f deploy/kit/docker-compose.yml -f deploy/kit/docker-compose.production.yml --env-file "$tmp/.env" --env-file "$tmp/image.env" config "$@"; }
check "生产 compose 能解析（!override、!reset 要 docker compose ≥ 2.24）" cfgp -q
cfgp --format json > "$tmp/prod.json"
# 邮件端口没写时的默认值（.env 里 FX_MAIL_PORT 空）
grep -v "^FX_MAIL_PORT=" "$tmp/.env" > "$tmp/.env-noport"
docker compose --project-name trading --project-directory deploy/kit -f deploy/kit/docker-compose.yml -f deploy/kit/docker-compose.production.yml --env-file "$tmp/.env-noport" --env-file "$tmp/image.env" config --format json > "$tmp/prod-noport.json"
docker compose --project-name trading --project-directory deploy/kit -f deploy/kit/docker-compose.yml --env-file "$tmp/.env-noport" --env-file "$tmp/image.env" config --format json > "$tmp/test-noport.json"
jqp() { jq -e "$1" "$tmp/prod.json" > /dev/null; }
check "生产：只有 trading-web 映射 8082、8083、8084（对外）和 127.0.0.1:8085（报价对外默认不发布），不占 80、443" jqp '[.services["trading-web"].ports[] | "\(.host_ip // "")|\(.published)"] | sort == ["127.0.0.1|8085","|8082","|8083","|8084"]'
check "生产：trading-app 健康检查口 127.0.0.1:18082（8080、18080 是同机 TopUP 生产的）" jqp '.services["trading-app"].ports == [{"mode":"ingress","host_ip":"127.0.0.1","target":8080,"published":"18082","protocol":"tcp"}]'
# 同机 TopUP 生产已知占用的端口（11/20 虚拟机实测：nginx 0.0.0.0:8080、topup-web-1 127.0.0.1:18080）：Trading 生产映射的端口、
# trading 命令生产模式检查的端口、健康检查口都不能是它们
check "生产：端口不和 TopUP 生产已知占用的 8080、18080 冲突（compose 映射、trading 命令的端口检查和健康检查口）" bash -c '! jq -e "[.services[].ports[]?.published] | any(. == \"8080\" or . == \"18080\")" "$1" > /dev/null && p=$(grep -E "^  prod && ports=\(" deploy/kit/trading) && [ -n "$p" ] && ! grep -qwE "8080|18080" <<< "$p" && grep -q "HEALTH_PORT=18082;" deploy/kit/trading' _ "$tmp/prod.json"
check "生产：quote-app、mysql、redis 不映射端口" jqp '[.services["quote-app","mysql","redis"].ports] | all(. == null)'
check "生产：不挂证书（平台网关做 HTTPS）" jqp '[.services[].volumes[]? | select(.source | startswith("/etc/letsencrypt") or startswith("/var/www/certbot"))] | length == 0'
check "生产：每个容器 mem_limit = memswap_limit，合计 2,432 MB（1,024 + 512 + 768 + 64 + 64，生产方案 03 页）" jqp '([.services[] | (.mem_limit == .memswap_limit)] | all) and (([.services[].mem_limit | tonumber] | add) == (2432 * 1024 * 1024)) and ((.services["trading-app"].mem_limit | tonumber) == 1024 * 1048576) and ((.services["quote-app"].mem_limit | tonumber) == 512 * 1048576) and ((.services.mysql.mem_limit | tonumber) == 768 * 1048576)'
check "生产：JVM 堆 + 元空间 + 代码缓存 + 直接内存（800、328）没超过容器上限" bash -c 'grep -q "Xmx\${TR_HEAP_APP:-512m} -XX:MaxMetaspaceSize=\${TR_META_APP:-160m} -XX:ReservedCodeCacheSize=\${TR_CODE_APP:-64m} -XX:MaxDirectMemorySize=\${TR_DIRECT_APP:-64m}" deploy/kit/docker-compose.production.yml && grep -q "Xmx\${TR_HEAP_QUOTE:-160m} -XX:MaxMetaspaceSize=\${TR_META_QUOTE:-112m} -XX:ReservedCodeCacheSize=\${TR_CODE_QUOTE:-40m} -XX:MaxDirectMemorySize=\${TR_DIRECT_QUOTE:-16m}" deploy/kit/docker-compose.production.yml'
check "生产：CPU 上限 trading-app 2、quote-app 1、mysql 1、redis / web 各 0.5" jqp '.services["trading-app"].cpus == 2 and .services["quote-app"].cpus == 1 and .services.mysql.cpus == 1 and .services.redis.cpus == 0.5 and .services["trading-web"].cpus == 0.5'
check "生产：不是测试环境（FX_ENV_LABEL 空、测试管理员哈希空），报价 live、开发者文档 auto、授权检查关、只开币安现货" jqp '.services["trading-app"].environment.FX_ENV_LABEL == "" and .services["trading-app"].environment.TEST_ADMIN_TOKEN_SHA256 == "" and .services["trading-app"].environment.FX_QUOTE_MARKET_SOURCE == "live" and .services["quote-app"].environment.FX_QUOTE_MARKET_SOURCE == "live" and .services["quote-app"].environment.FX_QUOTE_OPEN_DOCS == "auto" and .services["quote-app"].environment.FX_QUOTE_LICENSE_ENFORCE == "false" and .services["quote-app"].environment.FX_QUOTE_BINANCE_ENABLED == "true" and .services["quote-app"].environment.FX_QUOTE_BINANCE_FUTURES_ENABLED == "false"'
check "生产：第一次启动只开加密货币、余额告警 5,000，官网只列 10 个加密货币、不显示价格（R-1、R-5）" jqp '.services["trading-app"].environment.FX_INIT_ENABLED_CATEGORIES == "crypto" and .services["trading-app"].environment.FX_INIT_BALANCE_MIN_USDT == "5000" and .services["trading-app"].environment.FX_PUBLIC_QUOTES_SOURCE == "simulated" and (.services["trading-app"].environment.FX_PUBLIC_QUOTES_SYMBOLS | split(",") | length == 10)'
check "生产：Nginx 用平台网关模板、官网可以收录（只管 8084）、真实 IP 默认关" jqp '.services["trading-web"].environment.NGINX_ENVSUBST_TEMPLATE_DIR == "/etc/nginx/templates-gateway" and .services["trading-web"].environment.TR_NGINX_MODE == "gateway" and .services["trading-web"].environment.FX_NGINX_NOINDEX == "false" and .services["trading-web"].environment.TR_REAL_IP_HEADER == "off"'
check "生产：MySQL 不写二进制日志、连接数 40 够两个连接池 + 备份 + 健康检查、缓冲池 384M" jqp '(.services.mysql.command | index("--disable-log-bin")) and (.services.mysql.command | index("--innodb-buffer-pool-size=384M")) and ((.services.mysql.command[] | select(startswith("--max-connections=")) | ltrimstr("--max-connections=") | tonumber) >= ((.services["trading-app"].environment.FX_DB_POOL_SIZE | tonumber) + (.services["quote-app"].environment.FX_QUOTE_DB_POOL_SIZE | tonumber) + 2))'
check "生产：Redis maxmemory 32mb、写满不淘汰" jqp '(.services.redis.command | join(" ")) | test("--maxmemory 32mb --maxmemory-policy noeviction")'
check "生产：Docker 日志每个容器 20 MB × 3" jqp '[.services[].logging | (.options["max-size"] == "20m" and .options["max-file"] == "3")] | all'
check "生产：网络和测试环境一样（mysql、redis 只在 internal，quote-app 只在 quote 别名上监听）" jqp '(.services.mysql.networks | keys) == ["internal"] and (.services.redis.networks | keys) == ["internal"] and .services["quote-app"].environment.FX_QUOTE_BIND_ADDRESS == "quote-internal"'
check "测试环境的 compose 没被生产的设置带歪（不叠加时仍是 80/443、FX_ENV_LABEL=test）" jqc '.services["trading-app"].environment.FX_ENV_LABEL == "test" and ([.services["trading-web"].ports[].published] | sort) == ["443","80"]'

echo "== 生产：平台网关模式的 Nginx（gateway.conf.template）真的起来，按端口发请求"
mkdir -p "$tmp/gw-templates"; cp deploy/nginx/gateway.conf.template "$tmp/gw-templates/"
# 应用的桩：在 trading-app:8080 上，把收到的 Host、X-Forwarded-Proto、X-Real-IP、X-Forwarded-For 原样回显
cat > "$tmp/app-stub.conf" << 'STUB'
server { listen 8080; default_type text/plain; location / { return 200 "app host=$http_host proto=$http_x_forwarded_proto xri=$http_x_real_ip xff=$http_x_forwarded_for uri=$uri"; } }
STUB
docker rm -f "$net-app" > /dev/null 2>&1 || true
docker run -d --name "$net-app" --network "$net" --network-alias trading-app -v "$tmp/app-stub.conf:/etc/nginx/conf.d/default.conf:ro" "$NGINX_IMAGE" > /dev/null
gw() {
  docker rm -f "$net-gw" > /dev/null 2>&1 || true
  # 镜像里的默认值（web.Dockerfile 的 ENV）在前，生产 compose 设的在后（后面的覆盖前面的）
  docker run -d --name "$net-gw" --network "$net" "${envs[@]}" -e APP_DOMAIN=8082-x.check.invalid -e ADMIN_DOMAIN=8083-x.check.invalid -e WWW_DOMAIN=8084-x.check.invalid -e QUOTE_DOMAIN=8085-x.check.invalid \
    -e NGINX_ENVSUBST_TEMPLATE_DIR=/etc/nginx/templates-gateway -e TR_NGINX_MODE=gateway -e FX_NGINX_NOINDEX=false "$@" \
    -v "$PWD/deploy/nginx/nginx.conf:/etc/nginx/nginx.conf:ro" -v "$tmp/gw-templates:/etc/nginx/templates-gateway:ro" -v "$tmp/snippets:/etc/nginx/snippets:ro" \
    -v "$PWD/deploy/nginx/trading-gateway.envsh:/docker-entrypoint.d/19-trading-gateway.envsh:ro" \
    -v "$tmp/www/client:/usr/share/nginx/client:ro" -v "$tmp/www/site:/usr/share/nginx/site:ro" -v "$tmp/www/admin:/usr/share/nginx/admin:ro" "$NGINX_IMAGE" > /dev/null
  for _ in $(seq 1 30); do docker exec "$net-gw" curl -s -o /dev/null http://127.0.0.1:8082/ 2> /dev/null && return 0; sleep 0.5; done
  docker logs "$net-gw" 2>&1 | tail -20; return 1
}
# greq 端口 路径 [curl 参数…] → 状态码、X-Robots-Tag、HSTS、正文（一行一个）
greq() { local p=$1 path=$2; shift 2; docker exec "$net-gw" curl -s -D /tmp/h -o /tmp/b "$@" "http://127.0.0.1:$p$path" -w '%{http_code}\n' && docker exec "$net-gw" sh -c 'grep -i "^x-robots-tag:" /tmp/h | cut -d" " -f2- | tr -d "\r"; echo; grep -i "^strict-transport-security:" /tmp/h | cut -d" " -f2- | tr -d "\r"; echo; cat /tmp/b'; }
ghas() { local want=$1 out; shift; out=$(greq "$@") && grep -qF -- "$want" <<< "$out"; }
glacks() { local nope=$1 out; shift; out=$(greq "$@") && ! grep -qF -- "$nope" <<< "$out"; }
export net; export -f greq
if gw; then
  docker exec "$net-gw" nginx -t > /dev/null 2>&1 && echo "  ✓ 网关模式 nginx -t 通过" || { echo "  ✗ 网关模式 nginx -t 没通过"; fail=1; }
  check "8082 客户端 /api/** 转给应用：Host 固定成平台主机名、X-Forwarded-Proto 固定 https" ghas "app host=8082-x.check.invalid proto=https" 8082 /api/auth/me -H 'Host: whatever.example'
  check "8083 后台 /api/admin/** 转给应用：Host 是后台的平台主机名" ghas "app host=8083-x.check.invalid proto=https" 8083 /api/admin/me
  check "8084 官网 /api/public/quotes 转给应用：Host 是官网的平台主机名" ghas "app host=8084-x.check.invalid proto=https" 8084 /api/public/quotes
  check "HSTS 1 年、不加 includeSubDomains" bash -c 'out=$(greq 8082 /) && grep -qx "max-age=31536000" <<< "$out"' 
  check "8082 客户端上后台接口、端到端接口、actuator 404" bash -c '[ "$(greq 8082 /api/admin/me | head -1)" = 404 ] && [ "$(greq 8082 /api/e2e/code | head -1)" = 404 ] && [ "$(greq 8082 /actuator/health | head -1)" = 404 ]'
  check "8083 后台的健康检查带业务告警头、其他 /api/** 404、/ 跳到 /admin/（只写路径）" bash -c '[ "$(greq 8083 /api/wallet/x | head -1)" = 404 ] && docker exec "$1" curl -s -D - -o /dev/null http://127.0.0.1:8083/ | grep -qi "^location: /admin/"' _ "$net-gw"
  check "路径里有分号 400" bash -c '[ "$(docker exec "$1" curl -s -o /dev/null --path-as-is -w "%{http_code}" "http://127.0.0.1:8082/api/admin;x/me")" = 400 ]' _ "$net-gw"
  check "一键登录落地页：参数里有分号也转给应用（不在 server 层 400），访问日志只记路径不记参数；别的路径参数里有分号照样 400" bash -c 'docker exec "$1" curl -s -o /dev/null "http://127.0.0.1:8082/api/tg/login?id=4242;x&hash=zz9zz" > /dev/null; c=$(docker exec "$1" curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:8082/api/tg/login?id=4242;x&hash=zz9zz") && [ "$c" != 400 ] && sleep 0.3 && logs=$(docker logs "$1" 2>&1) && grep -q "GET /api/tg/login HTTP" <<< "$logs" && ! grep -q "zz9zz" <<< "$logs" && ! grep -q "id=4242" <<< "$logs" && [ "$(docker exec "$1" curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:8082/api/x?a=1;b")" = 400 ] && [ "$(docker exec "$1" curl -s -o /dev/null --path-as-is -w "%{http_code}" "http://127.0.0.1:8082/api/tg/login;x")" = 400 ]' _ "$net-gw"
  check "8084 官网（FX_NGINX_NOINDEX=false）可以收录：/robots.txt 全部允许、不发 X-Robots-Tag" bash -c 'out=$(greq 8084 /robots.txt) && [ "$(sed -n 2p <<< "$out")" = "" ] && grep -q "Allow: /" <<< "$out"'
  for p in 8082 8083 8085; do
    check "$p 不收录（/robots.txt 全部禁止、带 X-Robots-Tag）" ghas "noindex, nofollow" "$p" /robots.txt
  done
  check "8085 报价对外：/docs/ 转到报价服务、/internal 404" bash -c 'out=$(greq 8085 /docs/) && grep -q "quote-app:/docs/" <<< "$out" && [ "$(greq 8085 /internal/open/overview | head -1)" = 404 ]'
  check "8084 官网页面里的客户端地址换成 8082 的平台地址" bash -c 'printf "<a href=\"__TR_CLIENT_URL__/register\">x</a>" > "$1/www/site/sub.html" && greq 8084 /sub.html | grep -q "https://8082-x.check.invalid/register"' _ "$tmp"
  check "真实 IP 关：不信任访客带的 X-Forwarded-For（应用看到的是网关的地址）" glacks "xri=1.2.3.4" 8082 /api/x -H 'X-Forwarded-For: 1.2.3.4'
  check "真实 IP 关：限流放宽成全站额度（启动脚本改了阈值）" bash -c 'docker logs "$1" 2>&1 | grep -q "真实 IP 关，限流按全站额度"' _ "$net-gw"
else
  check "网关模式 Nginx 能起来" false
fi
gwip=$(docker network inspect "$net" --format '{{range .IPAM.Config}}{{.Subnet}}{{end}}')
if gw -e TR_REAL_IP_HEADER=X-Forwarded-For -e TR_REAL_IP_FROM=10.99.0.0/16; then
  check "真实 IP 开、但网关不在 TR_REAL_IP_FROM 里：照样不信任" glacks "xri=1.2.3.4" 8082 /api/x -H 'X-Forwarded-For: 1.2.3.4'
else
  check "网关模式 Nginx 能起来（真实 IP 开）" false
fi
if gw -e TR_REAL_IP_HEADER=X-Forwarded-For -e "TR_REAL_IP_FROM=127.0.0.1"; then
  check "真实 IP 开、网关在 TR_REAL_IP_FROM 里：用它带的 X-Forwarded-For（转给应用时覆盖成这一个）" ghas "xri=1.2.3.4 xff=1.2.3.4" 8082 /api/x -H 'X-Forwarded-For: 1.2.3.4'
else
  check "网关模式 Nginx 能起来（真实 IP 开、本机）" false
fi
if gw -e TR_REAL_IP_HEADER=X-Forwarded-For -e "TR_REAL_IP_FROM=172.18.0.0/16"; then
  check "TR_REAL_IP_FROM 和 Docker 的 172.17～172.31 重叠：启动脚本按关处理" bash -c 'docker logs "$1" 2>&1 | grep -q "按关处理"' _ "$net-gw"
else
  check "网关模式 Nginx 能起来（地址段不合法）" false
fi
: "$gwip"
docker rm -f "$net-gw" "$net-app" > /dev/null 2>&1 || true

echo "== 生产：trading 命令的规矩（假的 docker，不需要 root、不起容器）"
prod_env() { printf 'TR_ENV=production\n%s' "${1:-}" > "$tmp/tr/.env"; echo IMAGE_TAG=prod-0000000000000000000000000000000000000000 > "$tmp/tr/state/image.env"; }
tr_run() { env PATH="$tmp/fakebin:$PATH" TR_DIR="$tmp/tr" TR_OFFLINE_TEST=1 TR_HEALTH_WAIT_S=2 TR_TOPUP_BIN="$tmp/fakebin/topup" FAKE_TOPUP_LOG="$tmp/topup-calls" "$@" 2>&1; }
export -f prod_env tr_run
check "生产 on、TopUP 的容器在跑：照常启动，不调 topup stop / start" bash -c 'prod_env; : > "$tmp/topup-calls"; o=$(tr_run FAKE_TOPUP_RUNNING=1 TR_MIN_MEM_MB=1 bash deploy/kit/trading on); grep -q "启动 Trading（生产，和 TopUP 生产共存" <<< "$o" && ! grep -q "TopUP 的容器还在跑" <<< "$o" && [ ! -s "$tmp/topup-calls" ]'
check "生产 off：不调 topup start" bash -c 'prod_env; : > "$tmp/topup-calls"; o=$(tr_run bash deploy/kit/trading off); grep -q "Trading 已全停（生产，和 TopUP 生产共存" <<< "$o" && [ ! -s "$tmp/topup-calls" ]'
check "生产：没有轮流模式（set TR_SHARE_WITH_TOPUP 拒绝）" bash -c 'prod_env; ! tr_run bash deploy/kit/trading set TR_SHARE_WITH_TOPUP true > /dev/null && ! grep -q TR_SHARE_WITH_TOPUP "$tmp/tr/.env"'
check "生产：gen-test-admin 直接拒绝" bash -c 'prod_env; o=$(tr_run bash deploy/kit/trading gen-test-admin) && exit 1; grep -q "生产一律不设测试管理员" <<< "$o"'
check "生产：.env 里有 TEST_ADMIN_TOKEN_SHA256 就拒绝启动" bash -c 'prod_env "TEST_ADMIN_TOKEN_SHA256=abc"; o=$(tr_run TR_MIN_MEM_MB=1 bash deploy/kit/trading on) && exit 1; grep -q "拒绝启动" <<< "$o"'
check "环境装好后不能改：命令里写的 TR_ENV 和 .env 不一致就停" bash -c 'prod_env; o=$(tr_run TR_ENV=staging bash deploy/kit/trading status) && exit 1; grep -q "环境装好后不能改" <<< "$o"'
check "生产：测试环境的 install 拒绝（要写 --production）" bash -c 'prod_env; o=$(tr_run bash deploy/kit/trading install a.example b.example c.example d.example x@example.com) && exit 1; grep -q "环境装好后不能改" <<< "$o"'
check "生产：restore 不写 --production 拒绝、什么都没动" bash -c 'prod_env; touch "$tmp/tr/x.tar.age"; o=$(tr_run bash deploy/kit/trading restore "$tmp/tr/x.tar.age" --confirm) && exit 1; grep -q "加 --confirm --production" <<< "$o"'
check "测试环境：restore 写 --production 拒绝" bash -c ': > "$tmp/tr/.env"; o=$(tr_run bash deploy/kit/trading restore "$tmp/tr/x.tar.age" --confirm --production) && exit 1; grep -q "不能写 --production" <<< "$o"'
check "生产：start-payout 不写 --confirm 拒绝" bash -c 'prod_env; o=$(tr_run bash deploy/kit/trading start-payout) && exit 1; grep -q "start-payout --confirm" <<< "$o"'
check "生产：TR_REAL_IP_FROM 和 Docker 的 172.17～172.31 重叠、前缀太短都拒绝，什么都不改" bash -c 'prod_env; for v in 172.20.0.0/16 10.0.0.0/8 abc; do tr_run bash deploy/kit/trading set TR_REAL_IP_FROM "$v" > /dev/null && exit 1; done; ! grep -q "^TR_REAL_IP_FROM=" "$tmp/tr/.env"'
check "生产：TR_REAL_IP_HEADER 只认 off、X-Forwarded-For、X-Real-IP" bash -c 'prod_env; ! tr_run bash deploy/kit/trading set TR_REAL_IP_HEADER Forwarded > /dev/null'
check "生产：Trading 停着时 set 真实 IP 只保存、不启动" bash -c 'prod_env; o=$(tr_run bash deploy/kit/trading set TR_REAL_IP_FROM 10.1.0.0/16) && grep -q "Trading 现在停着，不启动它" <<< "$o" && grep -q "^TR_REAL_IP_FROM=10.1.0.0/16$" "$tmp/tr/.env"'
check "轮流模式、Trading 停着：set 只保存、不 compose up（不和 TopUP 抢 80/443）" bash -c 'echo TR_SHARE_WITH_TOPUP=true > "$tmp/tr/.env"; echo IMAGE_TAG=0000000000000000000000000000000000000000 > "$tmp/tr/state/image.env"; o=$(tr_run bash deploy/kit/trading set FX_ALERT_EMAILS a@example.com) && grep -q "Trading 现在停着，不启动它" <<< "$o" && grep -q "^FX_ALERT_EMAILS=a@example.com$" "$tmp/tr/.env"'
check "backup-key 只认 age 公钥，off 去掉" bash -c 'prod_env; ! tr_run bash deploy/kit/trading backup-key nope > /dev/null && tr_run bash deploy/kit/trading backup-key "age1$(printf "q%.0s" $(seq 1 58))" > /dev/null && [ -s "$tmp/tr/state/backup-amos.pub" ] && tr_run bash deploy/kit/trading backup-key off > /dev/null && [ ! -e "$tmp/tr/state/backup-amos.pub" ]'
check "trading.log 超过上限就轮转（.1～.5），最多 6 个文件" bash -c 'prod_env; head -c 2000 /dev/zero > "$tmp/tr/logs/trading.log"; for i in 1 2 3 4 5 6 7; do tr_run TR_LOG_MAX_BYTES=1000 bash deploy/kit/trading set TR_QUOTE_BIND 127.0.0.1 > /dev/null; head -c 2000 /dev/zero >> "$tmp/tr/logs/trading.log"; done; [ -f "$tmp/tr/logs/trading.log.5" ] && [ ! -e "$tmp/tr/logs/trading.log.6" ]'
check "生产的每天备份时间 UTC 18:10 写在 trading 命令里（systemd 覆盖文件）" grep -q 'OnCalendar=\*-\*-\* 18:10:00 UTC' deploy/kit/trading
check "重跑安装（TR_FIRST）、这台已经部署成功过：不清 paused、不当作没部署过（照样先做更新前备份）" bash -c 'prod_env; rm -f "$tmp/tr/state/first-pending" "$tmp/tr/state/skip"; echo IMAGE_TAG=prod-$(printf "a%.0s" {1..40}) > "$tmp/tr/state/image.env"; echo "$(printf "c%.0s" {1..40})" > "$tmp/tr/state/paused"; o=$(tr_run TR_FIRST=1 FAKE_REVISION=$(printf "c%.0s" {1..40}) bash deploy/kit/trading update); [ -s "$tmp/tr/state/paused" ] && ! grep -q "发现新版本" <<< "$o" && rm -f "$tmp/tr/state/paused" && o=$(tr_run TR_FIRST=1 TR_UPDATE_MIN_DISK_MB=0 FAKE_REVISION=$(printf "c%.0s" {1..40}) bash deploy/kit/trading update); grep -q "当前 $(printf "a%.0s" {1..40})" <<< "$o" && grep -q "没有备份加密密钥" <<< "$o"'
check "重跑安装（TR_FIRST）、第一次部署没起来（first-pending）：不跳过上次失败的版本、不做更新前备份" bash -c 'prod_env; echo IMAGE_TAG=prod-$(printf "c%.0s" {1..40}) > "$tmp/tr/state/image.env"; : > "$tmp/tr/state/first-pending"; printf "c%.0s" {1..40} > "$tmp/tr/state/skip"; o=$(tr_run TR_FIRST=1 TR_UPDATE_MIN_DISK_MB=0 FAKE_REVISION=$(printf "c%.0s" {1..40}) bash deploy/kit/trading update); grep -q "当前 无" <<< "$o" && ! grep -q "没有备份加密密钥" <<< "$o" && [ ! -e "$tmp/tr/state/skip" ]'
check "生产：TR_REAL_IP_FROM 和 Trading 实际的 Docker 子网（docker network inspect）重叠也拒绝，不重叠照常" bash -c 'prod_env; ! tr_run FAKE_SUBNETS="10.99.0.0/16 192.168.5.0/24" bash deploy/kit/trading set TR_REAL_IP_FROM 10.99.3.0/24 > /dev/null && ! grep -q "^TR_REAL_IP_FROM=" "$tmp/tr/.env" && tr_run FAKE_SUBNETS="10.99.0.0/16" bash deploy/kit/trading set TR_REAL_IP_FROM 10.98.0.0/16 > /dev/null && grep -q "^TR_REAL_IP_FROM=10.98.0.0/16$" "$tmp/tr/.env"'
check "生产：两次检查隔了 15 分钟以上报\"可能休眠\"；trading on（停过再启动）之后第一次检查不误报" bash -c 'prod_env; echo $(( $(date +%s) - 3600 )) > "$tmp/tr/state/watch-last-run"; rm -f "$tmp/tr/state/watch"; tr_run bash deploy/kit/trading watch > /dev/null; grep -q "休眠" "$tmp/tr/state/watch" || exit 1; echo $(( $(date +%s) - 3600 )) > "$tmp/tr/state/watch-last-run"; tr_run TR_MIN_MEM_MB=1 bash deploy/kit/trading on > /dev/null; rm -f "$tmp/tr/state/watch"; tr_run bash deploy/kit/trading watch > /dev/null; ! grep -q "休眠" "$tmp/tr/state/watch"'
# 首次部署标记（state/first-pending）：版本健康运行后由 watch、on、rollback 删掉。健康检查用一个本机小服务假扮（127.0.0.1:18082 回 status、version）
fp_health() { # fp_health <version>：起假的健康检查，打印进程号
  python3 -c 'import http.server,sys
v=sys.argv[1]
class H(http.server.BaseHTTPRequestHandler):
  def do_GET(s):
    b=("{\"status\":\"UP\",\"version\":\"%s\"}" % v).encode(); s.send_response(200); s.end_headers(); s.wfile.write(b)
  def log_message(s,*a): pass
http.server.HTTPServer(("127.0.0.1",18082),H).serve_forever()' "$1" > /dev/null 2>&1 &
  echo $!
}
export -f fp_health
fp_case() { # fp_case <watch|on|rollback> <健康检查报的版本> → 标记还在输出"在"，删了输出"无"
  local a b pid
  a=$(printf "a%.0s" {1..40}); b=$(printf "b%.0s" {1..40})
  prod_env ""; echo "IMAGE_TAG=prod-$a" > "$tmp/tr/state/image.env"; : > "$tmp/tr/state/first-pending"; echo "$b" > "$tmp/tr/state/previous"
  pid=$(fp_health "$2"); sleep 0.5
  case "$1" in
    watch) tr_run FAKE_QUOTE_HEALTHY=1 bash deploy/kit/trading watch > /dev/null ;;
    watch-noquote) tr_run bash deploy/kit/trading watch > /dev/null ;;
    on) tr_run TR_MIN_MEM_MB=1 FAKE_TRADING_RUNNING=1 FAKE_QUOTE_HEALTHY=1 bash deploy/kit/trading on > /dev/null ;;
    rollback) tr_run FAKE_QUOTE_HEALTHY=1 bash deploy/kit/trading rollback > /dev/null ;;
  esac
  kill "$pid" 2> /dev/null; wait "$pid" 2> /dev/null
  if [ -f "$tmp/tr/state/first-pending" ]; then echo 在; else echo 无; fi
}
export -f fp_case
check "首次部署标记：watch 看到当前版本健康运行就删" bash -c '[ "$(fp_case watch "$(printf "a%.0s" {1..40})")" = 无 ]'
check "首次部署标记：watch 看到的版本不是 image.env 当前版本，不删" bash -c '[ "$(fp_case watch "$(printf "c%.0s" {1..40})")" = 在 ]'
check "首次部署标记：trading-app 健康、版本对，但报价服务不健康，watch 不删" bash -c '[ "$(fp_case watch-noquote "$(printf "a%.0s" {1..40})")" = 在 ]'
check "首次部署标记：on 启动后当前版本健康就删" bash -c '[ "$(fp_case on "$(printf "a%.0s" {1..40})")" = 无 ]'
check "首次部署标记：rollback 回到上一个版本、健康就删" bash -c '[ "$(fp_case rollback "$(printf "b%.0s" {1..40})")" = 无 ]'
# 邮件（11/20 生产实测：InstaBox 拦出站 587、465、25，2587 通）：生产默认端口 2587、测试 587；失败时日志写退出码和原因分类、不写密码。
# 假的 curl：把参数记下来，按 FAKE_CURL_RC 退出、按 FAKE_SMTP_CODE 打出回应码（-w %{response_code}）
cat > "$tmp/fakebin/curl" << 'FAKE'
#!/usr/bin/env bash
printf '%s\n' "$*" > "${FAKE_CURL_ARGS:?}"
printf '%s' "${FAKE_SMTP_CODE:-250}"
exit "${FAKE_CURL_RC:-0}"
FAKE
chmod +x "$tmp/fakebin/curl"
mail_env() { printf '%bFX_MAIL_HOST=smtp.resend.com\nFX_MAIL_FROM=ops@example.com\nFX_ALERT_EMAILS=amos@example.com\nFX_MAIL_USERNAME=resend\nFX_MAIL_PASSWORD=re_not_a_real_key_check_only\n' "$1" > "$tmp/tr/.env"; : > "$tmp/tr/logs/trading.log"; }
mail_run() { env PATH="$tmp/fakebin:$PATH" TR_DIR="$tmp/tr" TR_OFFLINE_TEST=1 FAKE_CURL_ARGS="$tmp/curl-args" "$@" bash deploy/kit/trading test-mail > /dev/null 2>&1; }
export -f mail_env mail_run
check "邮件：生产没写端口时默认 2587（虚拟机拦 587），测试环境默认 587" bash -c 'mail_env "TR_ENV=production\n"; mail_run; grep -q "smtp://smtp.resend.com:2587 " "$tmp/curl-args" && mail_env ""; mail_run; grep -q "smtp://smtp.resend.com:587 " "$tmp/curl-args"'
check "邮件：setup / keys 在生产写 2587、测试写 587（resend_port）" bash -c '[ "$(grep -c "envset FX_MAIL_PORT \"\$(resend_port)\"" deploy/kit/trading)" = 2 ] && grep -q "ask FX_MAIL_PORT .* 2587$" deploy/kit/trading && grep -q "ask FX_MAIL_PORT \"端口（587 或 465）\" 587$" deploy/kit/trading'
check "邮件失败：日志写 curl 退出码和原因（连不上 / 认证失败 / 发件人被拒），不写密码" bash -c 'for c in "7 000 连不上" "28 000 连接超时" "67 535 认证失败" "55 550 发件人或收件人被拒"; do set -- $c; mail_env "TR_ENV=production\n"; ! mail_run FAKE_CURL_RC=$1 FAKE_SMTP_CODE=$2 || exit 1; grep -q "邮件发送失败.*curl 退出码 $1，SMTP 回应码 $2：$3" "$tmp/tr/logs/trading.log" || exit 1; ! grep -q re_not_a_real_key "$tmp/tr/logs/trading.log" || exit 1; done'
check "邮件成功：日志写已发邮件" bash -c 'mail_env "TR_ENV=production\n"; mail_run && grep -q "已发邮件" "$tmp/tr/logs/trading.log"'
# 生产不重启、不停 Docker（11/20 事故：install --production 写了 daemon.json 后 systemctl restart docker，同机 TopUP 生产全部容器跟着重启）：
# 生产的代码路径里不能有 systemctl restart / stop docker。只有测试环境的安装（cmd_install 里 install --production 分支之后的部分）允许
check "生产：生产模式的代码里没有 systemctl restart / stop docker、不写 daemon.json（只有测试环境的安装允许）" bash -c '
  code=$(grep -vE "^[[:space:]]*#" deploy/kit/trading)
  n=$(grep -cE "systemctl (restart|stop) docker" <<< "$code")
  t=$(awk "/^cmd_install\\(\\) \\{/{f=1} f&&/^}/{f=0} f" <<< "$code" | grep -cE "systemctl (restart|stop) docker")
  p=$(awk "/^cmd_install_production\\(\\) \\{/{f=1} f&&/^}/{f=0} f" <<< "$code" | grep -cE "systemctl (restart|stop) docker|/etc/docker/daemon\\.json")
  [ "$n" = "$t" ] && [ "$p" = 0 ]'
check "邮件端口默认值：应用（生产 compose）和 trading 命令（resend_port）都是 2587，测试环境都是 587" bash -c 'grep -q "FX_MAIL_PORT: \${FX_MAIL_PORT:-2587}" deploy/kit/docker-compose.production.yml && grep -q "FX_MAIL_PORT: \${FX_MAIL_PORT:-587}" deploy/kit/docker-compose.yml && grep -q "^resend_port() { if prod; then echo 2587; else echo 587; fi; }" deploy/kit/trading && [ "$(jq -r ".services[\"trading-app\"].environment.FX_MAIL_PORT" "$1")" = 2587 ] && [ "$(jq -r ".services[\"trading-app\"].environment.FX_MAIL_PORT" "$2")" = 587 ]' _ "$tmp/prod-noport.json" "$tmp/test-noport.json"
check "生产：不装 unattended-upgrades（会自动升级、重启 Docker）；清没有标签的镜像只清 Trading 自己的" bash -c 'p=$(awk "/^cmd_install_production\\(\\) \\{/{f=1} f&&/^}/{f=0} f" deploy/kit/trading | grep -vE "^[[:space:]]*#"); ! grep -q unattended-upgrades <<< "$p" && grep -q "docker image prune -f --filter \"label=org.opencontainers.image.source=https://github.com/dollar3651314/Trading\"" deploy/kit/trading && [ "$(grep -vE "^[[:space:]]*#" deploy/kit/trading | grep -c "docker image prune -f > /dev/null")" = 1 ]'
# Telegram 机器人（TG 机器人第 1 批）：令牌只能 set-key（隐藏输入），用户名用 set 改（去掉 @、校验像机器人用户名）；compose 默认用户名测试 / 生产各一个，令牌默认空
check "Telegram：令牌在 set-key 清单、不在 set 清单；用户名在 set 清单" bash -c 'grep -q "^KEY_ALLOWED=\".* FX_TG_BOT_TOKEN\"$" deploy/kit/trading && grep -q "^SET_ALLOWED=\".* FX_TG_BOT_USERNAME .*\"$" deploy/kit/trading && ! grep -q "^SET_ALLOWED=.*FX_TG_BOT_TOKEN" deploy/kit/trading'
check "Telegram：set FX_TG_BOT_USERNAME 去掉 @ 保存；不像机器人用户名的拒绝、不改" bash -c ': > "$tmp/tr/.env"; echo IMAGE_TAG=0000000000000000000000000000000000000000 > "$tmp/tr/state/image.env"; echo TR_SHARE_WITH_TOPUP=true > "$tmp/tr/.env"; tr_run bash deploy/kit/trading set FX_TG_BOT_USERNAME @TradingAssistTestBot > /dev/null && grep -q "^FX_TG_BOT_USERNAME=TradingAssistTestBot$" "$tmp/tr/.env" && for v in TradingAssist abc "a b bot" "@@xbot"; do tr_run bash deploy/kit/trading set FX_TG_BOT_USERNAME "$v" > /dev/null 2>&1 && exit 1; done; grep -q "^FX_TG_BOT_USERNAME=TradingAssistTestBot$" "$tmp/tr/.env"'
grep -v "^FX_TG_\|^FX_PHASE1_" "$tmp/.env" > "$tmp/.env-notg"
docker compose --project-name trading --project-directory deploy/kit -f deploy/kit/docker-compose.yml -f deploy/kit/docker-compose.production.yml --env-file "$tmp/.env-notg" --env-file "$tmp/image.env" config --format json > "$tmp/prod-notg.json"
docker compose --project-name trading --project-directory deploy/kit -f deploy/kit/docker-compose.yml --env-file "$tmp/.env-notg" --env-file "$tmp/image.env" config --format json > "$tmp/test-notg.json"
check "Telegram：.env 没写时 compose 默认用户名 测试 TradingAssistTestBot、生产 TradingAssistBot，令牌空（= 机器人不启动）" bash -c '[ "$(jq -r ".services[\"trading-app\"].environment.FX_TG_BOT_USERNAME" "$1")" = TradingAssistBot ] && [ "$(jq -r ".services[\"trading-app\"].environment.FX_TG_BOT_USERNAME" "$2")" = TradingAssistTestBot ] && [ "$(jq -r ".services[\"trading-app\"].environment.FX_TG_BOT_TOKEN" "$1")" = "" ] && [ "$(jq -r ".services[\"trading-app\"].environment.FX_TG_BOT_TOKEN" "$2")" = "" ]' _ "$tmp/prod-notg.json" "$tmp/test-notg.json"
check "trading-app 内存：set TR_MEM_APP / TR_META_APP 只认 数字+m" bash -c 'echo TR_SHARE_WITH_TOPUP=true > "$tmp/tr/.env"; tr_run bash deploy/kit/trading set TR_META_APP 120m > /dev/null && grep -q "^TR_META_APP=120m$" "$tmp/tr/.env" && for v in 120 120M abc 0m; do tr_run bash deploy/kit/trading set TR_MEM_APP "$v" > /dev/null 2>&1 && exit 1; done; ! grep -q "^TR_MEM_APP=" "$tmp/tr/.env"'
check "trading-app 内存：生产拒绝 set TR_MEM_APP / TR_META_APP，什么都不改" bash -c 'prod_env; for k in TR_MEM_APP TR_META_APP; do o=$(tr_run bash deploy/kit/trading set $k 380m) && exit 1; grep -q "只能在测试环境" <<< "$o" || exit 1; done; ! grep -qE "^TR_(MEM|META)_APP=" "$tmp/tr/.env"'
# TG 机器人第 2 批：一键登录落地页的访问日志不记参数（两个模板都是）；全站不带 Referer；网页地址、进入方式、只走 Telegram 的 set 校验；官网去机器人的默认值
check "一键登录落地页 /api/tg/login：访问日志用不带参数的格式（\$uri），错误日志只留 emerg；全站 Referrer-Policy no-referrer" bash -c 'for f in deploy/nginx/site.conf.template deploy/nginx/gateway.conf.template; do awk "/location = \\/api\\/tg\\/login/{f=1} f&&/}/{exit} f" "$f" > "$tmp/loc"; grep -qE "access_log .*(main_noq|tr_gw_noq);" "$tmp/loc" && grep -q "error_log .* emerg;" "$tmp/loc" || exit 1; done; grep -q "log_format main_noq .*\$request_method \$uri \$server_protocol" deploy/nginx/nginx.conf && ! grep "log_format main_noq" deploy/nginx/nginx.conf | grep -q "\$request\b\|\$args\|\$request_uri" && grep -q "log_format tr_gw_noq .*\$request_method \$uri" deploy/nginx/gateway.conf.template && grep -q "^add_header Referrer-Policy no-referrer always;" deploy/nginx/security.inc'
check "TG 第 2 批 set：FX_TG_WEB_URL 只认 https://域名、FX_TG_LOGIN_MODE 只认 login-url / link、FX_PHASE1_TELEGRAM_ONLY 只认 true / false" bash -c 'echo TR_SHARE_WITH_TOPUP=true > "$tmp/tr/.env"; tr_run bash deploy/kit/trading set FX_TG_WEB_URL https://app.example.test > /dev/null && grep -q "^FX_TG_WEB_URL=https://app.example.test$" "$tmp/tr/.env" && tr_run bash deploy/kit/trading set FX_TG_LOGIN_MODE link > /dev/null && tr_run bash deploy/kit/trading set FX_PHASE1_TELEGRAM_ONLY false > /dev/null && for kv in "FX_TG_WEB_URL http://app.example.test" "FX_TG_WEB_URL https://a.test/x?y" "FX_TG_LOGIN_MODE oidc" "FX_PHASE1_TELEGRAM_ONLY yes"; do set -- $kv; tr_run bash deploy/kit/trading set "$1" "$2" > /dev/null 2>&1 && exit 1; done; true'
# 第 5 批应急开关（TB-34）的服务器备用办法：只认 on / off、原因必填；写开关记录和审计（原因只以十六进制进 SQL）、关的时候作废应急登录会话、发告警邮件；已经是这个状态就不写；Trading 停着时不改
em_run() { : > "$tmp/em-sql"; env PATH="$tmp/fakebin:$PATH" TR_DIR="$tmp/tr" TR_OFFLINE_TEST=1 FAKE_SQL_LOG="$tmp/em-sql" FAKE_CURL_ARGS="$tmp/curl-args" FAKE_TRADING_RUNNING=1 "$@" 2>&1; }
export -f em_run
check "应急开关备用办法：sudo trading set TR_TG_EMERGENCY_LOGIN on|off \"原因\" 写开关记录、审计（原因十六进制）、关时作废应急会话、发告警邮件；值不对、没原因、已经是这个状态、Trading 停着都不写" bash -c '
  mail_env ""
  em_run bash deploy/kit/trading set TR_TG_EMERGENCY_LOGIN maybe "x" > /dev/null && exit 1; [ -s "$tmp/em-sql" ] && exit 1
  em_run bash deploy/kit/trading set TR_TG_EMERGENCY_LOGIN on " " < /dev/null > /dev/null && exit 1; [ -s "$tmp/em-sql" ] && exit 1
  em_run bash deploy/kit/trading set TR_TG_EMERGENCY_LOGIN on "bot banned'"'"'); DROP TABLE x; --" > /dev/null || exit 1
  grep -q "INSERT INTO trading.account_tg_emergency_log (turned_on, actor, actor_name, reason, created_at) SELECT 1, '"'"'server'"'"', '"'"'server:root'"'"', CONVERT(UNHEX('"'"'626f742062616e6e656427293b2044524f50205441424c4520783b202d2d'"'"') USING utf8mb4), UTC_TIMESTAMP(3) FROM DUAL WHERE @cur <> 1" "$tmp/em-sql" || exit 1
  grep -q "SELECT turned_on INTO @cur FROM trading.account_tg_emergency_log ORDER BY id DESC LIMIT 1 FOR UPDATE" "$tmp/em-sql" || exit 1
  grep -q "DROP TABLE" "$tmp/em-sql" && exit 1; grep -q "kind = '"'"'EMERGENCY'"'"'" "$tmp/em-sql" && exit 1
  grep -q "'"'"'telegram.emergency_on'"'"'" "$tmp/em-sql" && grep -q "已发邮件：\[测试\] Trading 应急开关已打开" "$tmp/tr/logs/trading.log" || exit 1
  em_run env FAKE_EM_STATE=1 bash deploy/kit/trading set TR_TG_EMERGENCY_LOGIN off "机器人恢复了" > /dev/null || exit 1
  grep -q "UPDATE trading.account_session SET revoked_at = UTC_TIMESTAMP(3) WHERE @n = 1 AND kind = '"'"'EMERGENCY'"'"' AND revoked_at IS NULL" "$tmp/em-sql" && grep -q "'"'"'telegram.emergency_off'"'"'" "$tmp/em-sql" || exit 1
  grep -q "已发邮件：\[测试\] Trading 应急开关已关闭" "$tmp/tr/logs/trading.log" || exit 1
  em_run env FAKE_EM_STATE=1 bash deploy/kit/trading set TR_TG_EMERGENCY_LOGIN on "再开" > /dev/null || exit 1; grep -q "START TRANSACTION" "$tmp/em-sql" && exit 1
  : > "$tmp/em-sql"; env PATH="$tmp/fakebin:$PATH" TR_DIR="$tmp/tr" TR_OFFLINE_TEST=1 FAKE_SQL_LOG="$tmp/em-sql" bash deploy/kit/trading set TR_TG_EMERGENCY_LOGIN on "停着" > /dev/null 2>&1 && exit 1
  [ -s "$tmp/em-sql" ] && exit 1; true'
check "应急开关备用办法：写之前 FOR UPDATE 锁住最新一行再判断（#45 复审）；锁住后发现别人已经改了就不发告警邮件、不报成功" bash -c '
  mail_env ""; out=$(em_run env FAKE_EM_CHANGED=0 bash deploy/kit/trading set TR_TG_EMERGENCY_LOGIN on "并发") || exit 1
  grep -q "刚被别人改成了打开" <<< "$out" && ! grep -q "应急开关已打开" "$tmp/tr/logs/trading.log"'
check "cancel-recovery：只认编号、原因必填；写撤销请求和审计（原因十六进制）、等应用处理的结果；Trading 停着不写" bash -c '
  em_run bash deploy/kit/trading cancel-recovery abc "x" > /dev/null && exit 1; [ -s "$tmp/em-sql" ] && exit 1
  em_run bash deploy/kit/trading cancel-recovery 12 " " < /dev/null > /dev/null && exit 1; [ -s "$tmp/em-sql" ] && exit 1
  out=$(em_run env TR_CANCEL_WAIT_S=3 bash deploy/kit/trading cancel-recovery 12 "x'"'"'); DROP") || exit 1; grep -q "找回申请 #12 已撤销" <<< "$out" || exit 1
  grep -q "INSERT INTO trading.account_recovery_cancel_request (recovery_id, requested_by, reason, created_at) VALUES (12, '"'"'server:root'"'"', CONVERT(UNHEX('"'"'7827293b2044524f50'"'"') USING utf8mb4)" "$tmp/em-sql" && grep -q "'"'"'recovery.cancel_request'"'"'" "$tmp/em-sql" && ! grep -q "DROP" "$tmp/em-sql" || exit 1
  out=$(em_run env TR_CANCEL_WAIT_S=3 FAKE_CANCEL_RESULT=NOT_FOUND bash deploy/kit/trading cancel-recovery 13 "x") && exit 1; grep -q "没有找回申请 #13" <<< "$out" || exit 1
  out=$(em_run env TR_CANCEL_WAIT_S=1 FAKE_CANCEL_RESULT= bash deploy/kit/trading cancel-recovery 14 "x") || exit 1; grep -q "已排队" <<< "$out" || exit 1
  : > "$tmp/em-sql"; env PATH="$tmp/fakebin:$PATH" TR_DIR="$tmp/tr" TR_OFFLINE_TEST=1 FAKE_SQL_LOG="$tmp/em-sql" bash deploy/kit/trading cancel-recovery 12 "停着" > /dev/null 2>&1 && exit 1
  [ -s "$tmp/em-sql" ] && exit 1; true'
check "第 5 批 set：登录机器人用户名、Amos 的后台账号编号在 set 清单（编号只认正整数）；登录机器人令牌只在 set-key 清单；compose 传给 trading-app、默认空" bash -c 'grep -q "^KEY_ALLOWED=\".* FX_TG_LOGIN_BOT_TOKEN .*\"$" deploy/kit/trading && ! grep -q "^SET_ALLOWED=.*FX_TG_LOGIN_BOT_TOKEN" deploy/kit/trading && grep -q "^SET_ALLOWED=\".* FX_TG_LOGIN_BOT_USERNAME FX_ADMIN_OWNER_ID .*\"$" deploy/kit/trading \
  && echo TR_SHARE_WITH_TOPUP=true > "$tmp/tr/.env" && tr_run bash deploy/kit/trading set FX_ADMIN_OWNER_ID 12 > /dev/null && grep -q "^FX_ADMIN_OWNER_ID=12$" "$tmp/tr/.env" && ! tr_run bash deploy/kit/trading set FX_ADMIN_OWNER_ID amos > /dev/null \
  && tr_run bash deploy/kit/trading set FX_TG_LOGIN_BOT_USERNAME @TradingLoginBot > /dev/null && grep -q "^FX_TG_LOGIN_BOT_USERNAME=TradingLoginBot$" "$tmp/tr/.env" \
  && for f in "$1" "$2"; do for k in FX_TG_LOGIN_BOT_TOKEN FX_TG_LOGIN_BOT_USERNAME FX_ADMIN_OWNER_ID; do [ "$(jq -r ".services[\"trading-app\"].environment.$k" "$f")" = "" ] || exit 1; done; done' _ "$tmp/prod-notg.json" "$tmp/test-notg.json"
check "TG 第 2 批 compose：.env 没写时不只走 Telegram（默认关，Amos 验收时打开）、一键登录、网页地址空；官网机器人地址（测试 TradingAssistTestBot、生产 TradingAssistBot）" bash -c 'for f in "$1" "$2"; do [ "$(jq -r ".services[\"trading-app\"].environment.FX_PHASE1_TELEGRAM_ONLY" "$f")" = false ] && [ "$(jq -r ".services[\"trading-app\"].environment.FX_TG_LOGIN_MODE" "$f")" = login-url ] && [ "$(jq -r ".services[\"trading-app\"].environment.FX_TG_WEB_URL" "$f")" = "" ] && [ "$(jq -r ".services[\"trading-web\"].environment.FX_NGINX_TG_ONLY" "$f")" = false ] || exit 1; done; [ "$(jq -r ".services[\"trading-web\"].environment.FX_NGINX_BOT_URL" "$1")" = https://t.me/TradingAssistBot ] && [ "$(jq -r ".services[\"trading-web\"].environment.FX_NGINX_BOT_URL" "$2")" = https://t.me/TradingAssistTestBot ]' _ "$tmp/prod-notg.json" "$tmp/test-notg.json"
exit $fail
