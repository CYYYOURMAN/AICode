#!/usr/bin/env bash
# AIcode 一键部署脚本（人或 AI 都可以跑）
#
#   curl -fsSL "$SITE/install.sh" | AICODE_SITE="$SITE" bash
# 或下载后：AICODE_SITE="$SITE" bash install.sh
#
# 只做：检查环境 → 下载部署包 → 解压 → 复制 .env → 跑部署前体检。
# 不会替你填 API key，也不会自动启动服务（下一步会打印出来）。
set -euo pipefail

SITE="${AICODE_SITE:-https://cyyyourman.github.io/AICode}"
DEST="${AICODE_DIR:-$HOME/aicode}"
TARBALL="$(mktemp -t aicode-deploy.XXXXXX.tar.gz)"
trap 'rm -f "$TARBALL"' EXIT

echo "== AIcode 一键部署 =="
command -v docker >/dev/null || { echo "缺少 Docker，请先安装：apt install docker.io"; exit 1; }
docker info >/dev/null 2>&1 || { echo "当前用户连不上 Docker（sudo usermod -aG docker $USER 后重新登录）"; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "缺少 docker compose v2：apt install docker-compose-v2"; exit 1; }

mkdir -p "$DEST"
echo "下载部署包：$SITE/aicode-deploy.tar.gz"
curl -fsSL "$SITE/aicode-deploy.tar.gz" -o "$TARBALL"
tar xzf "$TARBALL" -C "$DEST"
cd "$DEST"
[ -f .env ] || cp .env.example .env

echo
if [ -f preflight.sh ]; then
  echo "== 部署前体检 =="
  GATEWAY_PORT="$(grep -E '^GATEWAY_PORT=' .env | cut -d= -f2)" DSH_PORT="$(grep -E '^DSH_PORT=' .env | cut -d= -f2)" \
    AICODE_DIR="$DEST" bash preflight.sh || echo "（体检有 FAIL：按上面的提示先修，再继续）"
  echo
fi

echo "部署包已解压到：$DEST"
echo "下一步："
echo "  1) 编辑 $DEST/.env：填 DEEPSEEK_API_KEY；DSH_HOST 留空；端口被占改 GATEWAY_PORT / DSH_PORT"
echo "  2) cd $DEST && docker compose up -d --build"
echo "  3) docker compose exec -T aicode cat /data/pair-code   # 拿配对码（一次性）"
echo
echo "详细步骤见：$SITE/deploy-for-ai.md"
