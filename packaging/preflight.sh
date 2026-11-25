#!/usr/bin/env bash
# AIcode 部署前体检（只读，不改任何东西）
#
# 用法：bash preflight.sh
# 输出每项 [OK] / [WARN] / [FAIL]；有 FAIL 时退出码 1（先修好再部署）。
# 端口按环境变量检查：GATEWAY_PORT（默认 3300）、DSH_PORT（默认 3080）。
set -u
GATEWAY_PORT="${GATEWAY_PORT:-3300}"
DSH_PORT="${DSH_PORT:-3080}"
fail=0
warn=0
ok() { printf '  [OK]   %s\n' "$*"; }
wa() { printf '  [WARN] %s\n' "$*"; warn=$((warn + 1)); }
fa() { printf '  [FAIL] %s\n' "$*"; fail=$((fail + 1)); }

echo "== AIcode 部署前体检 =="

# 1. 系统
case "$(uname -s 2>/dev/null)" in
  Linux) ok "Linux 系统" ;;
  *) fa "不是 Linux（当前 $(uname -s)；Docker host 网络需要 Linux）" ;;
esac
if [ -r /proc/meminfo ]; then
  mem_mb=$(awk '/MemTotal/{printf "%d", $2/1024}' /proc/meminfo)
  if [ "$mem_mb" -ge 3800 ]; then ok "内存 ${mem_mb}MB"
  elif [ "$mem_mb" -ge 1900 ]; then wa "内存 ${mem_mb}MB（够跑，DSH 第一次构建会紧）"
  else fa "内存 ${mem_mb}MB（至少 2GB，建议 4GB）"; fi
fi
disk_gb=$(df -Pk . 2>/dev/null | awk 'NR==2{printf "%d", $4/1024/1024}')
if [ -n "${disk_gb:-}" ]; then
  if [ "$disk_gb" -ge 6 ]; then ok "磁盘可用 ${disk_gb}GB"
  elif [ "$disk_gb" -ge 3 ]; then wa "磁盘可用 ${disk_gb}GB（镜像约 1.5GB，构建时还要余量）"
  else fa "磁盘可用 ${disk_gb}GB（至少 3GB）"; fi
fi

# 2. Docker
if command -v docker >/dev/null 2>&1; then
  if docker info >/dev/null 2>&1; then ok "Docker 可用"
  else fa "Docker 装了但当前用户连不上守护进程（把用户加入 docker 组：sudo usermod -aG docker \$USER 后重新登录）"; fi
  if docker compose version >/dev/null 2>&1; then ok "docker compose v2"
  else fa "缺少 docker compose v2（apt install docker-compose-v2）"; fi
else
  fa "没有 Docker（Debian/Ubuntu：apt install docker.io docker-compose-v2）"
fi

# 3. 端口
busy=""
for p in "$GATEWAY_PORT" "$DSH_PORT"; do
  if ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$p$"; then busy="$busy $p"; fi
done
if [ -z "$busy" ]; then ok "端口 $GATEWAY_PORT / $DSH_PORT 空闲"
else fa "端口被占用：$busy（在 .env 里改 GATEWAY_PORT / DSH_PORT）"; fi
if [ "$GATEWAY_PORT" = "$DSH_PORT" ]; then fa "GATEWAY_PORT 和 DSH_PORT 不能相同"; fi

# 4. 网络（构镜像要下载 DSH 包）
if curl -fsS -m 8 -o /dev/null https://registry.npmmirror.com 2>/dev/null; then ok "能访问 npm 镜像源"
elif curl -fsS -m 8 -o /dev/null https://registry.npmjs.org 2>/dev/null; then ok "能访问 npm 官方源"
else wa "访问 npm 源失败（离线/被墙）；可先配置代理，或构建时传 --build-arg NPM_REGISTRY=..."; fi

# 5. 目录
if mkdir -p "${AICODE_DIR:-$HOME/aicode}" 2>/dev/null && [ -w "${AICODE_DIR:-$HOME/aicode}" ]; then
  ok "部署目录可写（${AICODE_DIR:-$HOME/aicode}）"
else
  fa "部署目录不可写（${AICODE_DIR:-$HOME/aicode}）"
fi

# 6. 模型 key（如果已经有 .env）
envf="${AICODE_DIR:-$HOME/aicode}/.env"
if [ -f "$envf" ]; then
  key=$(grep -E '^DEEPSEEK_API_KEY=' "$envf" 2>/dev/null | head -1 | cut -d= -f2-)
  if [ -z "$key" ] || [ "$key" = "sk-xxxxxxxx" ]; then wa ".env 里 DEEPSEEK_API_KEY 还没填"
  elif printf '%s' "$key" | grep -qE '^sk-[A-Za-z0-9]{16,}$'; then ok ".env 里的 key 格式正常"
  else wa ".env 里的 key 格式不像 DeepSeek key（sk- 开头），确认没复制错"; fi
else
  wa "还没有 .env（部署时 cp .env.example .env 再填 key）"
fi

echo
if [ "$fail" -gt 0 ]; then
  echo "结论：有 $fail 项 FAIL、$warn 项 WARN —— 先修 FAIL 再部署。"
  exit 1
fi
echo "结论：可以开始部署（$warn 项 WARN 不影响启动）。"
