# 安全相关的响应头。HSTS 的值 $tr_hsts：测试环境（site.conf.template）1 天；生产平台网关模式（gateway.conf.template）1 年、不加 includeSubDomains（平台的域名不是我们的）
add_header Strict-Transport-Security $tr_hsts always;
add_header X-Content-Type-Options nosniff always;
add_header X-Frame-Options DENY always;
add_header Referrer-Policy strict-origin-when-cross-origin always;
# 不让搜索引擎收录（测试环境验收 T-2）：$tr_robots_tag 由 site.conf.template 按域名算：客户端、后台、报价永远 "noindex, nofollow"；官网看 FX_NGINX_NOINDEX（false → 空，Nginx 不发这个头；其他值包括 true、没设 → "noindex, nofollow"）
add_header X-Robots-Tag $tr_robots_tag always;
