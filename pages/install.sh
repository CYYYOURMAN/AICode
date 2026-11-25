#!/usr/bin/env bash
# AIcode 一键部署脚本（人或 AI 都可以跑）
#
#   curl -fsSL "$SITE/install.sh" | AICODE_SITE="$SITE" bash
# 或下载后：AICODE_SITE="$SITE" bash install.sh
#
# 只做：检查环境 → 下载部署包 → 解压 → 复制 .env → 写入端口 → 跑部署前体检。
# 不会替你填 API key，也不会自动启动服务（下一步会打印出来）。
#
# 可选环境变量：
#   AICODE_DIR            部署目录（默认 ~/aicode）
#   AICODE_GATEWAY_PORT   网关端口（写进 .env）
#   AICODE_DSH_PORT       引擎端口（写进 .env）
set -euo pipefail

SITE="${AICODE_SITE:-https://cyyyourman.github.io/AICode}"
DEST="${AICODE_DIR:-$HOME/aicode}"
GW="${AICODE_GATEWAY_PORT:-}"
DSH="${AICODE_DSH_PORT:-}"
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

set_env() { # key value
  if grep -qE "^$1=" .env; then
    sed -i "s|^$1=.*|$1=$2|" .env
  else
    printf '%s=%s\n' "$1" "$2" >> .env
  fi
}
[ -n "$GW" ] && set_env GATEWAY_PORT "$GW"
[ -n "$DSH" ] && set_env DSH_PORT "$DSH"

echo
if [ -f preflight.sh ]; then
  echo "== 部署前体检 =="
  AICODE_DIR="$DEST" bash preflight.sh || echo "（体检有 FAIL：按上面的提示先修，再继续）"
  echo
fi

GW_NOW=$(grep -E '^GATEWAY_PORT=' .env | cut -d= -f2); GW_NOW="${GW_NOW:-3300}"
DSH_NOW=$(grep -E '^DSH_PORT=' .env | cut -d= -f2); DSH_NOW="${DSH_NOW:-3080}"

echo "部署包已解压到：$DEST（网关端口 $GW_NOW，引擎端口 $DSH_NOW）"
echo "下一步："
echo "  1) 编辑 $DEST/.env：填 DEEPSEEK_API_KEY；DSH_HOST 留空（自动探测）"
echo "  2) cd $DEST && docker compose up -d --build"
echo "  3) docker compose exec -T aicode cat /data/pair-code   # 拿配对码（一次性）"
echo
echo "详细步骤见：$SITE/deploy-for-ai.md"
