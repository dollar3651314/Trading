# 转发到报价服务 quote-app（只在 Docker 内部网络 quote 上监听，别名 quote-internal）。只有报价对外子域名的 /v1/**、/docs/ 用它。
# 真实 IP 同样覆盖，不追加。内部 Key 只在 trading-app 和报价服务之间，Nginx 不传、也不转发 /internal/**。
set $tr_quote http://quote-internal:8090;
proxy_pass $tr_quote;
proxy_http_version 1.1;
proxy_set_header Host $host;
proxy_set_header X-Forwarded-For $remote_addr;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-Host "";
proxy_set_header X-Request-Id $request_id;
proxy_set_header X-Fx-Health-Alerts "";
proxy_set_header X-Trading-Test-Admin "";
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection $connection_upgrade;
