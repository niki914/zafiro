#!/usr/bin/env bash
# OC Ferry for Termux - stop the service gracefully (auto cleanup on exit).
# Usage: ./termux/stop.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_BIN="${PYTHON:-python3}"
CFG="$ROOT/termux/config.json"

DATA="$("$PYTHON_BIN" -c 'import json,sys; print(json.load(open(sys.argv[1]))["data_dir"])' "$CFG")"
PIDFILE="$DATA/ferry.pid"

if [ ! -f "$PIDFILE" ]; then
  echo "没有运行中的服务（未找到 $PIDFILE）"
  exit 0
fi
PID="$(cat "$PIDFILE")"
if kill -0 "$PID" 2>/dev/null; then
  # SIGINT -> Python KeyboardInterrupt -> serve 的 finally 会自动 cleanup 受管条目
  kill -INT "$PID"
  for _ in 1 2 3 4 5; do
    kill -0 "$PID" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "$PID" 2>/dev/null; then
    echo "进程未在 5 秒内退出，发送 SIGTERM" >&2
    kill -TERM "$PID" 2>/dev/null || true
  fi
  echo "已停止 (PID $PID)，自己写入的模型条目已清场"
else
  echo "进程 $PID 已不在运行，清理 PID 文件"
fi
rm -f "$PIDFILE"
if command -v termux-wake-unlock >/dev/null 2>&1; then
  termux-wake-unlock >/dev/null 2>&1 || true
fi
