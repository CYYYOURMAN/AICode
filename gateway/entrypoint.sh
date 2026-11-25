#!/bin/sh
set -eu

mkdir -p /data

DSH_PORT="${DSH_PORT:-3080}"
DSH_HOST="${DSH_HOST:-127.0.0.1}"

echo "[entrypoint] 启动 DSH 引擎：dsh web --port $DSH_PORT --host $DSH_HOST"
dsh web --port "$DSH_PORT" --host "$DSH_HOST" --no-open >> /data/dsh.log 2>&1 &
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
