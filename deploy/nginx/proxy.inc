# 转发到 trading-app。客户端真实 IP 用 $remote_addr **覆盖** X-Forwarded-For、X-Real-IP（不追加）：客户端自己带的不会传给后端；
# 后台 IP 白名单、登录失败计数靠它（AdminInterceptor、application.yml 的 server.forward-headers-strategy: native，只信任内网代理）。
set $tr_app http://trading-app:8080;
proxy_pass $tr_app;
proxy_http_version 1.1;
proxy_set_header Host $host;
proxy_set_header X-Forwarded-For $remote_addr;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-Host "";
# 追踪号：每个请求 Nginx 生成一个 $request_id，覆盖客户端自己带的 X-Request-Id
proxy_set_header X-Request-Id $request_id;
# 健康检查里的业务告警只给后台域名：默认空 = 不发这个头（并覆盖客户端自己带的同名头）；只有后台域名的 location = /api/health 在 include 之后 set 成 1。
# 后端见 common/web/HealthController
set $fx_health_alerts "";
proxy_set_header X-Fx-Health-Alerts $fx_health_alerts;
# 测试管理员的请求头只认 GitHub 上的验收用例直接带的；这里不动它（Nginx 不加也不删），后端只在测试环境认（admin/security/TestAdmin）
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection $connection_upgrade;
