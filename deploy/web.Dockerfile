# web 镜像（Nginx + 客户端、后台、官网三个前端 + 服务器端部署工具包），包名 trading-web（第 4 批，部署方案 v1 02 页）。
# 先构建三个前端（真实接口模式，不设 VITE_API_MODE），再在仓库根目录构建：
#   npm ci --prefix web/client && npm run build --prefix web/client   （admin、site 同样）
#   docker build -f deploy/web.Dockerfile --build-arg FX_VERSION=<完整提交号> -t trading-web:<提交号> .
# 四个子域名（客户端、后台、官网、报价对外；生产是四个端口的平台主机名）在容器启动时由 .env 替换进 Nginx 配置；官网里的客户端地址占位符 __TR_CLIENT_URL__ 由 Nginx 换成 https://${APP_DOMAIN}。
# /trading-kit 是服务器端工具包（deploy/kit），服务器每次更新时从这里同步到 /opt/trading（trading update）。
FROM nginx:1.27-bookworm
ARG FX_VERSION=dev
LABEL org.opencontainers.image.source="https://github.com/dollar3651314/Trading" \
      org.opencontainers.image.revision="${FX_VERSION}"
# Nginx 外层限流（比后端宽松，只防明显的洪水）；报价子域名单独一档
ENV FX_NGINX_API_RATE=20r/s \
    FX_NGINX_API_BURST=400 \
    FX_NGINX_API_CONN=200 \
    FX_NGINX_WS_RATE=5r/s \
    FX_NGINX_WS_BURST=100 \
    FX_NGINX_WS_CONN=500 \
    FX_NGINX_QUOTE_RATE=20r/s \
    FX_NGINX_QUOTE_BURST=100 \
    FX_NGINX_QUOTE_CONN=50
# 搜索引擎收录（测试环境验收 T-2，site.conf.template 开头）：只管官网（客户端、后台、报价子域名永远不收录）；默认 true = 不让收录，生产要让官网被收录时在 compose 里设 false
ENV FX_NGINX_NOINDEX=true
RUN rm -f /etc/nginx/conf.d/default.conf
COPY deploy/nginx/nginx.conf /etc/nginx/nginx.conf
COPY deploy/nginx/site.conf.template /etc/nginx/templates/site.conf.template
# 生产平台网关模式（生产方案 v1 07 页 R-2）：模板在另一个目录，生产 compose 设 NGINX_ENVSUBST_TEMPLATE_DIR=/etc/nginx/templates-gateway 才用它；
# 启动脚本只在 TR_NGINX_MODE=gateway 时生成真实 IP 设置、放宽限流（测试环境什么都不做）
COPY deploy/nginx/gateway.conf.template /etc/nginx/templates-gateway/gateway.conf.template
COPY --chmod=755 deploy/nginx/trading-gateway.envsh /docker-entrypoint.d/19-trading-gateway.envsh
COPY deploy/nginx/proxy.inc deploy/nginx/proxy-quote.inc deploy/nginx/security.inc deploy/nginx/rate-limited.inc deploy/nginx/robots.inc /etc/nginx/snippets/
COPY web/client/dist /usr/share/nginx/client
COPY web/admin/dist /usr/share/nginx/admin
COPY web/site/dist /usr/share/nginx/site
COPY deploy/kit /trading-kit
RUN echo "${FX_VERSION}" > /trading-kit/VERSION && chmod 755 /trading-kit/trading /trading-kit/*.sh
