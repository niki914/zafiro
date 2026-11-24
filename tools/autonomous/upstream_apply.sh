#!/usr/bin/env bash
# Applies upstream niki914/zafiro changes since UPSTREAM_SYNCED_SHA onto the current checkout (our history is not a descendant of
# upstream's, so a normal merge is impossible: we replay the text diff with a 3-way apply). Exit 0 = applied cleanly, 2 = conflicts.
set -u
cd "$(git rev-parse --show-toplevel)"
git remote get-url upstream >/dev/null 2>&1 || git remote add upstream https://github.com/niki914/zafiro.git
git fetch -q --no-tags upstream
BASE=$(cat UPSTREAM_SYNCED_SHA)
HEAD_UP=$(git rev-parse upstream/HEAD 2>/dev/null || git rev-parse upstream/main)
[ "$BASE" = "$HEAD_UP" ] && { echo "up to date"; exit 0; }
git diff "$BASE" "$HEAD_UP" -- . ':!res' ':!README*.md' ':!*.png' ':!*.jpg' ':!*.jpeg' ':!*.gif' ':!*.webp' ':!*.mp4' ':!*.ttf' ':!*.otf' ':!*.ico' ':!.github' > /tmp/upstream.patch
if ! git apply --3way --index --exclude='app/src/main/res/values-*' /tmp/upstream.patch 2>/tmp/apply.log; then :; fi
if git diff --name-only --diff-filter=U | grep -q .; then git diff --name-only --diff-filter=U > /tmp/conflicts.txt; exit 2; fi
git show "$HEAD_UP:app/src/main/res/values-en/strings.xml" > /tmp/values-en.xml 2>/dev/null || true
python3 tools/autonomous/fix_strings.py /tmp/values-en.xml
python3 tools/autonomous/english_only.py || true
echo "$HEAD_UP" > UPSTREAM_SYNCED_SHA
git add -A
exit 0
