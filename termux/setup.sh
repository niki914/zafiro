#!/usr/bin/env bash
# OC Ferry for Termux - one-time setup.
# Usage: ./termux/setup.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_BIN="${PYTHON:-python3}"
CFG="$ROOT/termux/config.json"
CORE="$ROOT/.agents/skills/oc-ferry/oc_ferry.py"

echo "== OC Ferry / Termux 初始化 =="

if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
  if command -v pkg >/dev/null 2>&1; then
    echo ">> 安装 Python ..."
    pkg install -y python
  else
    echo "未找到 python3，且当前不是 Termux（无 pkg 命令）。请先安装 Python 3.8+" >&2
    exit 1
  fi
fi

if [ ! -f "$CORE" ]; then
  echo "缺少核心文件：$CORE" >&2
  exit 1
fi

# 申请 Android 共享存储权限（把文档导出到「文档」目录时需要）
if command -v termux-setup-storage >/dev/null 2>&1 && [ ! -d "$HOME/storage/shared" ]; then
  echo ">> 申请存储权限（请在弹窗中允许）..."
  termux-setup-storage || true
fi

chmod +x "$ROOT"/termux/*.sh 2>/dev/null || true

"$PYTHON_BIN" - "$ROOT" "$CFG" <<'PYSETUP'
import json, os, sys
root, out = sys.argv[1], sys.argv[2]
with open(os.path.join(root, "termux", "config.termux.json"), "r", encoding="utf-8") as h:
    cfg = json.load(h)
cfg["data_dir"] = os.path.join(os.environ.get("HOME", root), ".oc-ferry")
os.makedirs(cfg["data_dir"], exist_ok=True)
with open(out, "w", encoding="utf-8") as h:
    json.dump(cfg, h, ensure_ascii=False, indent=2)
    h.write("\n")
print(">> 已生成配置:", out)
print(">> data_dir   :", cfg["data_dir"])
PYSETUP

echo ">> 离线自检："
"$PYTHON_BIN" "$CORE" --config "$CFG" doctor
echo
echo "完成。接下来："
echo "  1) 只想导出免费模型文档 -> ./termux/export.sh   （不需要任何 API Key）"
echo "  2) 想体检 / 转发模型    -> 编辑 $CFG 填 upstream_base_url（可选 upstream_api_key）"
echo "  3) 启动本机服务         -> ./termux/start.sh"
