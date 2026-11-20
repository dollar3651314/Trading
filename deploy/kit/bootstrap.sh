#!/usr/bin/env bash
# 第一次部署的入口（第 4 批）：在一台全新的 Ubuntu 24.04 上装 Docker、从公开镜像 trading-web:staging 取出工具包放到 /opt/trading，
# 再运行 trading install（参数原样传过去）。主会话给 Amos 的那一条命令就是它（固定到某个提交号，不跟着分支变）：
#   curl -fsSL https://raw.githubusercontent.com/dollar3651314/Trading/<提交号>/deploy/kit/bootstrap.sh | sudo bash -s -- <客户端域名> <后台域名> <官网域名> <报价域名> <告警邮箱> [TopUP 地址]
# 生产（生产方案 v1 06 页第 6 步）：第一个参数写 --production，取 trading-web:production 里的工具包，后面是三～四个平台主机名：
#   curl -fsSL https://raw.githubusercontent.com/dollar3651314/Trading/<提交号>/deploy/kit/bootstrap.sh | sudo bash -s -- --production <客户端主机名> <后台主机名> <官网主机名> [报价主机名]
# 可以重复运行。不改系统防火墙（防火墙在轻量服务器控制台设，免得把人锁在外面）；不需要也不保存任何登录凭据（镜像公开）。
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "请用 root 运行（sudo）" >&2; exit 1; }
REG=${TR_REGISTRY:-ghcr.io/dollar3651314}
TAG=staging
[ "${1:-}" = --production ] && TAG=production
export DEBIAN_FRONTEND=noninteractive
echo "== 装 Docker（Ubuntu 官方源的 docker.io、docker-compose-v2）"
if ! command -v docker > /dev/null || ! docker compose version > /dev/null 2>&1; then
  apt-get update -q > /dev/null
  apt-get install -y -q docker.io docker-compose-v2 > /dev/null
fi
systemctl enable --now docker > /dev/null 2>&1 || true
echo "  ✓ $(docker --version)"
echo "== 取工具包（$REG/trading-web:$TAG）"
docker pull -q "$REG/trading-web:$TAG" > /dev/null || {
  echo "  ✗ 拉不到镜像：GitHub 上 trading-app、trading-quote、trading-web 三个包要先设为 Public，或者 CI 还没推出第一个镜像（生产：要 Amos 先批准一次 promote-production）" >&2; exit 1; }
mkdir -p /opt/trading
tmp=$(mktemp -d)
docker run --rm --entrypoint sh -v "$tmp:/out" "$REG/trading-web:$TAG" -c 'cp -r /trading-kit/. /out/'
cp -r "$tmp"/. /opt/trading/
rm -rf "$tmp"
chmod 755 /opt/trading/trading /opt/trading/mysql-init/*.sh
ln -sf /opt/trading/trading /usr/local/bin/trading
echo "  ✓ 工具包版本 $(cat /opt/trading/VERSION)"
exec /opt/trading/trading install "$@"
