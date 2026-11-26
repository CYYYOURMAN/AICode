#!/usr/bin/env bash
# 生成 pages/aicode-deploy.tar.gz —— GitHub Pages 上给人 / 给 AI 下载的部署包。
#
#   bash packaging/build-tarball.sh
#
# 改了 docker-compose.yml / .env.example / gateway/ / packaging/preflight.sh 之后必须跑一次，
# 否则线上部署包还是旧代码（2026-11-26 之前是手工打包，容易忘）。
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=pages/aicode-deploy.tar.gz
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

cp docker-compose.yml .env.example "$TMP/"
cp -a gateway "$TMP/gateway"
rm -rf "$TMP/gateway/__pycache__"
cp packaging/preflight.sh "$TMP/preflight.sh"
chmod +x "$TMP/preflight.sh" "$TMP/gateway/entrypoint.sh"

cat > "$TMP/README-DEPLOY.md" <<'MD'
# AIcode 部署包

- `docker-compose.yml` + `.env.example`：先 `cp .env.example .env` 再填 `DEEPSEEK_API_KEY`
- `gateway/`：网关（鉴权/配对/手机控制/更新通道）
- `preflight.sh`：部署前体检（只读）——建好 `.env` 后跑 `bash preflight.sh`，有 FAIL 先修
- 启动：`docker compose up -d --build`
- 配对码：`docker compose exec -T aicode cat /data/pair-code`（一次性，交付前重新取）
- 交付主人：优先给一段 `http://<IP>:<网关端口>|<密钥>`（`cat /data/secret` 取），App 连接页「从剪贴板导入连接」直接粘
- 详细步骤见站点上的 deploy-for-ai.md
MD

# 固定时间/属主/顺序：内容没变时 tar 字节不变，避免每次打包产生无意义 diff
tar --sort=name --mtime='2026-01-01 00:00:00Z' --owner=0 --group=0 --numeric-owner \
    -czf "$OUT" -C "$TMP" README-DEPLOY.md docker-compose.yml .env.example gateway preflight.sh

echo "已生成 $OUT（$(du -h "$OUT" | cut -f1)）"
tar tzf "$OUT"
