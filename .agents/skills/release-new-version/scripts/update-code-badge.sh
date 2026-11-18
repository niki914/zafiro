#!/usr/bin/env bash
# Count production Kotlin + Python non-blank lines (excluding tests / build / generated)
# and update the "kotlin-XX.Xk" badge in README.md and README_CN.md.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

files=$(git ls-files '*.kt' '*.py' | grep -vE '(^|/)(build|generated)/|/src/(android)?Test/|Test\.kt$|/test_[^/]*\.py$')
if [ -z "$files" ]; then
  echo "error: no source files found" >&2
  exit 1
fi

lines=$(printf '%s\n' "$files" | xargs awk 'NF {c++} END {print c}' | awk '{s+=$1} END {print s}')
k=$(awk -v n="$lines" 'BEGIN { printf "%.1fk", n/1000 }')
badge="https://img.shields.io/badge/Kotlin-${k}-a503fc?logo=kotlin&logoColor=white&style=for-the-badge&labelColor=6b02a3"

python3 - <<EOF
import re, sys

badge = "${badge}"
for path in ["README.md", "README_CN.md"]:
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    new_content, count = re.subn(r'https://img\.shields\.io/badge/[Kk]otlin-[0-9.]*k-[^"\s]+', badge, content)
    if count == 0:
        print(f"error: kotlin badge not found in {path}", file=sys.stderr)
        sys.exit(1)
    with open(path, "w", encoding="utf-8") as f:
        f.write(new_content)
EOF

echo "updated: ${lines} lines -> ${badge}"
