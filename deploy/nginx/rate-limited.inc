# 限流超限（limit_req / limit_conn）时的响应：和后端同一个错误格式。只处理 Nginx 自己产生的 429，后端返回的 429 原样转发
error_page 429 = @tr_rate_limited;
location @tr_rate_limited {
  include /etc/nginx/snippets/security.inc;
  add_header Retry-After 1 always;
  add_header X-Request-Id $request_id always;
  default_type application/json;
  return 429 '{"code":"RATE_LIMITED","message":"too many requests","fields":[],"cooldownSeconds":1}';
}
