#!/usr/bin/env bash
# OC Ferry for Termux - export the free-model list to a document.
# No API key and no inference quota needed: only the public catalog is read.
#
# Usage:
#   ./termux/export.sh                      # Markdown -> 手机「文档/oc-ferry/FREE_MODELS.md」
#   ./termux/export.sh /path/out.md        # 自定义输出路径
#   ./termux/export.sh /path/out.json json # 也可选 json / csv
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_BIN="${PYTHON:-python3}"
CFG="$ROOT/termux/config.json"
CORE="$ROOT/.agents/skills/oc-ferry/oc_ferry.py"

if [ ! -f "$CFG" ]; then
  echo "请先运行 ./termux/setup.sh" >&2
  exit 1
fi

FMT="${2:-md}"
if [ -d "$HOME/storage/shared" ]; then
  DEFAULT_OUT="$HOME/storage/shared/Documents/oc-ferry/FREE_MODELS.$FMT"
else
  DEFAULT_OUT="$HOME/FREE_MODELS.$FMT"
fi
OUT="${1:-$DEFAULT_OUT}"
mkdir -p "$(dirname "$OUT")"

echo ">> 读取公开模型目录（不需要 API Key、不消耗任何额度）..."
"$PYTHON_BIN" "$CORE" --config "$CFG" export \
  --format "$FMT" --online --output "$OUT" --no-cache
echo ">> 文档已生成：$OUT"
echo ">> 可在手机文件管理器的「文档 / oc-ferry」里打开"
