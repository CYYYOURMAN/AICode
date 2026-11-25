#!/usr/bin/env bash
# AIcode 一键部署脚本（人或 AI 都可以跑）
# 用法：
#   curl -fsSL "http://<站点>/install.sh" | AICODE_SITE="http://<站点>" bash
# 或者下载后：AICODE_SITE="http://<站点>" bash install.sh
set -euo pipefail

SITE="${AICODE_SITE:-https://CYYYOURMAN.github.io/AICode}"
DEST="${AICODE_DIR:-$HOME/aicode}"

echo "== AIcode 一键部署 =="
command -v docker >/dev/null || { echo "缺少 Docker，请先安装：apt install docker.io"; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "缺少 docker compose v2"; exit 1; }

mkdir -p "$DEST"
curl -fsSL "$SITE/aicode-deploy.tar.gz" -o /tmp/aicode-deploy.tar.gz
tar xzf /tmp/aicode-deploy.tar.gz -C "$DEST"
cd "$DEST"
[ -f .env ] || cp .env.example .env

echo
echo "部署包已解压到：$DEST"
echo "下一步："
echo "  1) 编辑 $DEST/.env，填 DEEPSEEK_API_KEY"
echo "  2) cd $DEST && docker compose up -d --build"
echo "  3) docker compose exec aicode cat /data/pair-code   # 拿配对码"
echo
echo "详细步骤见：$SITE/deploy-for-ai.md"
