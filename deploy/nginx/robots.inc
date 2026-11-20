# /robots.txt（测试环境验收 T-2）：四个子域名都 include。以前没有这一段，单页应用把 /robots.txt 回成首页 HTML。
#   客户端、后台、报价子域名永远、官网在 FX_NGINX_NOINDEX 不是 false 时：回"全部禁止"，配合 security.inc 的 X-Robots-Tag: noindex, nofollow
#   官网且 FX_NGINX_NOINDEX=false（生产要让官网被收录时）：前端目录里有 robots.txt 就用它，没有就回"全部允许"
#   （$tr_robots_tag 按域名算，见 site.conf.template 开头）
location = /robots.txt {
  default_type text/plain;
  expires -1;
  if ($tr_robots_tag) { return 200 "User-agent: *\nDisallow: /\n"; }
  try_files $uri @tr_robots_allow;
}
location @tr_robots_allow {
  default_type text/plain;
  return 200 "User-agent: *\nAllow: /\n";
}
