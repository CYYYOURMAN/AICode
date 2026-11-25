#!/usr/bin/env bash
# AIcode 部署前体检（只读，不改任何东西）
#
# 用法：bash preflight.sh
# 输出每项 [OK] / [WARN] / [FAIL]；有 FAIL 时退出码 1（先修好再部署）。
# 端口按环境变量检查：GATEWAY_PORT（默认 3300）、DSH_PORT（默认 3080）。
set -u
# 读脚本所在目录的 .env（改过端口的部署也能正确体检）；环境变量优先
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" 2>/dev/null && pwd)
ENV_FILE="$SCRIPT_DIR/.env"
env_get() { [ -f "$ENV_FILE" ] && grep -E "^$1=" "$ENV_FILE" 2>/dev/null | head -1 | cut -d= -f2-; }
GATEWAY_PORT="${GATEWAY_PORT:-$(env_get GATEWAY_PORT)}"
GATEWAY_PORT="${GATEWAY_PORT:-3300}"
DSH_PORT="${DSH_PORT:-$(env_get DSH_PORT)}"
DSH_PORT="${DSH_PORT:-3080}"
AICODE_DIR="${AICODE_DIR:-$SCRIPT_DIR}"
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

# 3. 端口（还没有 .env 时无法知道最终端口，按默认端口报 FAIL 会误导，改跳过）
if [ ! -f "$ENV_FILE" ]; then
  wa "还没有 .env，跳过端口检查（先 cp .env.example .env；改过端口的话从现在起以 .env 为准）"
else
  busy=""
  for p in "$GATEWAY_PORT" "$DSH_PORT"; do
    if ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$p$"; then busy="$busy $p"; fi
  done
  if [ -z "$busy" ]; then ok "端口 $GATEWAY_PORT / $DSH_PORT 空闲"
  else fa "端口被占用：$busy（在 .env 里改 GATEWAY_PORT / DSH_PORT）"; fi
  if [ "$GATEWAY_PORT" = "$DSH_PORT" ]; then fa "GATEWAY_PORT 和 DSH_PORT 不能相同"; fi
fi

# 4. 网络（构镜像要下载 DSH 包）
if curl -fsS -m 8 -o /dev/null https://registry.npmmirror.com 2>/dev/null; then ok "能访问 npm 镜像源"
elif curl -fsS -m 8 -o /dev/null https://registry.npmjs.org 2>/dev/null; then ok "能访问 npm 官方源"
else wa "访问 npm 源失败（离线/被墙）；可先配置代理，或构建时传 --build-arg NPM_REGISTRY=..."; fi

# 5. 目录
if mkdir -p "${AICODE_DIR:-$HOME/aicode}" 2>/dev/null && [ -w "${AICODE_DIR:-$HOME/aicode}" ]; then
  ok "部署目录可写（$AICODE_DIR）"
else
  fa "部署目录不可写（$AICODE_DIR）"
fi

# 6. 模型 key（如果已经有 .env）：格式 + 真连一次
# 为什么"真连"：没有这一步，假 key / 错 key 时 /health、配对、/app/version 全绿，
# 但 App 一说话就 401——「全绿但对话必失败」是最坑的一种假成功。
envf="$AICODE_DIR/.env"
if [ -f "$envf" ]; then
  key=$(grep -E '^DEEPSEEK_API_KEY=' "$envf" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '[:space:]')
  if [ -z "$key" ] || [ "$key" = "sk-xxxxxxxx" ]; then
    wa ".env 里 DEEPSEEK_API_KEY 还没填（服务能启动，但对话必失败）"
  else
    if ! printf '%s' "$key" | grep -qE '^sk-[A-Za-z0-9]+$'; then
      wa "DEEPSEEK_API_KEY 里有空格/连字符等异常字符（正常是 sk- 加 32 位字母数字），仍会实测一次"
    fi
    code=$(curl -s -o /dev/null -w '%{http_code}' -m 10 \
      -H "Authorization: Bearer $key" https://api.deepseek.com/models 2>/dev/null || true)
    [ -n "${code:-}" ] || code=000
    case "$code" in
      200) ok "模型 key 实测可用（api.deepseek.com 200）" ;;
      401|403) fa "模型 key 无效（api.deepseek.com $code）：服务能启动、/health 也会绿，但 App 里对话必失败——换真 key 再来" ;;
      000) wa "连不上 api.deepseek.com（出网/DNS/代理？），验证不了 key；部署后必须确认 5.1 能返回 model api: 200" ;;
      *) wa "模型 key 校验返回 HTTP $code；部署后必须确认 5.1 能返回 model api: 200" ;;
    esac
  fi
else
  wa "还没有 .env（部署时 cp .env.example .env 再填 key）"
fi

# 7. 防火墙（手机连不进来时第一嫌疑）
if command -v ufw >/dev/null 2>&1; then
  st=$(ufw status 2>/dev/null | head -1)
  case "$st" in
    *active*) wa "ufw 已启用：手机要连进来需放行端口——sudo ufw allow $GATEWAY_PORT/tcp; sudo ufw allow $DSH_PORT/tcp" ;;
    *) wa "检测到 ufw（读不到状态一般要 sudo）：确认 $GATEWAY_PORT/$DSH_PORT 已放行，否则手机连不上" ;;
  esac
elif command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state 2>/dev/null | grep -q running; then
  wa "firewalld 在运行：放行端口——sudo firewall-cmd --add-port=$GATEWAY_PORT/tcp --add-port=$DSH_PORT/tcp"
else
  ok "没有检测到 ufw/firewalld 拦截"
fi

echo
if [ "$fail" -gt 0 ]; then
  echo "结论：有 $fail 项 FAIL、$warn 项 WARN —— 先修 FAIL 再部署。"
  exit 1
fi
echo "结论：可以开始部署（$warn 项 WARN 不影响启动）。"
