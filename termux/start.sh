#!/usr/bin/env bash
# OC Ferry for Termux - start the loopback service in the background.
# Usage: ./termux/start.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_BIN="${PYTHON:-python3}"
CFG="$ROOT/termux/config.json"
CORE="$ROOT/.agents/skills/oc-ferry/oc_ferry.py"

if [ ! -f "$CFG" ]; then
  echo "请先运行 ./termux/setup.sh" >&2
  exit 1
fi

read_config() {
  "$PYTHON_BIN" -c 'import json,sys; print(json.load(open(sys.argv[1])).get(sys.argv[2], sys.argv[3]))' \
    "$CFG" "$1" "$2"
}
DATA="$(read_config data_dir "$HOME/.oc-ferry")"
PORT="$(read_config port 41980)"
UPSTREAM="$(read_config upstream_base_url "")"
mkdir -p "$DATA"

if [ -z "$UPSTREAM" ]; then
  echo "尚未配置 upstream_base_url：体检 / 转发服务需要你自己的 BYOK 上游。" >&2
  echo "请编辑 $CFG 填 upstream_base_url（可选 upstream_api_key）。" >&2
  echo "只想导出免费模型文档的话，直接运行 ./termux/export.sh（不需要任何密钥）。" >&2
  exit 1
fi

if [ -f "$DATA/ferry.pid" ] && kill -0 "$(cat "$DATA/ferry.pid")" 2>/dev/null; then
  echo "服务已在运行 (PID $(cat "$DATA/ferry.pid"))"
  echo "面板: http://127.0.0.1:$PORT/panel"
  exit 0
fi

# 可选：安装 Termux:API 后可用唤醒锁降低被系统杀后台的概率
if command -v termux-wake-lock >/dev/null 2>&1; then
  termux-wake-lock >/dev/null 2>&1 || true
fi

nohup "$PYTHON_BIN" "$CORE" --config "$CFG" serve > "$DATA/ferry.log" 2>&1 &
echo $! > "$DATA/ferry.pid"
echo "已启动 PID $(cat "$DATA/ferry.pid")"
echo "面板   : http://127.0.0.1:$PORT/panel"
echo "接口   : http://127.0.0.1:$PORT/v1  (Bearer 鉴权)"
echo "日志   : $DATA/ferry.log"
echo "api-key: $DATA/api-key （serve 启动时会打印）"
echo "停止   : ./termux/stop.sh"
