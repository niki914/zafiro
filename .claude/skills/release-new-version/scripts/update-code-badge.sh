#!/usr/bin/env bash
# Count production Kotlin + Python lines (excluding tests / build / generated)
# and update the "kotlin-XX.Xk" badge in README.md and README_CN.md.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

files=$(git ls-files '*.kt' '*.py' | grep -vE '(^|/)(build|generated)/|/src/(android)?Test/|Test\.kt$|/test_[^/]*\.py$')
if [ -z "$files" ]; then
  echo "error: no source files found" >&2
  exit 1
fi

lines=$(printf '%s\n' "$files" | xargs wc -l | tail -1 | awk '{print $1}')
k=$(awk -v n="$lines" 'BEGIN { printf "%.1fk", n/1000 }')
badge="https://img.shields.io/badge/kotlin-${k}-blue"

for f in README.md README_CN.md; do
  if grep -q 'img.shields.io/badge/kotlin-' "$f"; then
    sed -i.bak "s|https://img.shields.io/badge/kotlin-[0-9.]*k-blue|${badge}|" "$f" && rm -f "$f.bak"
  else
    echo "error: kotlin badge not found in $f" >&2
    exit 1
  fi
done

echo "updated: ${lines} lines -> ${badge}"
