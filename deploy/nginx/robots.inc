# /robots.txt（测试环境验收 T-2）：四个子域名都 include。以前没有这一段，单页应用把 /robots.txt 回成首页 HTML。
#   FX_NGINX_NOINDEX=true（测试环境）：回"全部禁止"，配合 security.inc 的 X-Robots-Tag: noindex, nofollow
#   FX_NGINX_NOINDEX=false（生产要让官网被收录时）：前端目录里有 robots.txt 就用它，没有就回"全部允许"
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
