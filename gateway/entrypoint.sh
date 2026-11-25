#!/bin/sh
set -eu

mkdir -p /data

if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  echo "[entrypoint] 错误：没有 DEEPSEEK_API_KEY，请在 .env 里配置（cp .env.example .env）" >&2
  exit 1
fi

DSH_PORT="${DSH_PORT:-3080}"

# DSH 的 --host 只接受本机具体 IP（不接受 0.0.0.0），而手机 App 要直连这个地址，
# 所以默认 127.0.0.1 会让手机连不上引擎。没显式指定时探测服务器对外 IP。
probe_host() {
  python3 - <<'PY' 2>/dev/null || true
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
try:
    s.connect(("8.8.8.8", 80))
    print(s.getsockname()[0])
except OSError:
    pass
finally:
    s.close()
PY
}

DSH_HOST="${DSH_HOST:-}"
if [ -z "$DSH_HOST" ] || [ "$DSH_HOST" = "127.0.0.1" ]; then
  probed=$(probe_host)
  if [ -n "$probed" ]; then
    if [ "$DSH_HOST" = "127.0.0.1" ]; then
      echo "[entrypoint] 提示：DSH_HOST 原来写的是 127.0.0.1（手机连不上），改用探测到的对外地址 $probed"
    else
      echo "[entrypoint] 自动探测到服务器地址 $probed（手机 App 要连这个地址）"
    fi
    DSH_HOST="$probed"
  else
    DSH_HOST="127.0.0.1"
    echo "[entrypoint] 警告：探测不到对外地址，DSH 只监听 127.0.0.1——手机可能连不上引擎。" >&2
    echo "[entrypoint]       请在 .env 里把 DSH_HOST 设为服务器局域网 IP（如 192.168.1.10）。" >&2
  fi
fi
export DSH_HOST

echo "[entrypoint] 启动 DSH 引擎：dsh web --port $DSH_PORT --host $DSH_HOST"
dsh web --port "$DSH_PORT" --host "$DSH_HOST" \
  --trusted-host "$DSH_HOST:$DSH_PORT" --trusted-host "127.0.0.1:$DSH_PORT" \
  --no-open >> /data/dsh.log 2>&1 &
DSH_PID=$!

# 等 DSH 打印带 token 的地址（网关靠它抓 token）
i=0
while [ "$i" -lt 60 ]; do
  if grep -q 'token=' /data/dsh.log 2>/dev/null; then
    echo "[entrypoint] DSH 已就绪（token 已打印）"
    break
  fi
  i=$((i + 1))
  sleep 1
done

if ! grep -q 'token=' /data/dsh.log 2>/dev/null; then
  echo "[entrypoint] 警告：60 秒内没在 /data/dsh.log 看到 token，网关会继续等待"
  tail -5 /data/dsh.log 2>/dev/null || true
fi

echo "[entrypoint] 启动 AIcode Gateway"
exec python3 /app/gateway.py
