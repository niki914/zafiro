#!/usr/bin/env bash
set -e

TARGET=".agents/skills/add-worktree/scripts/add-worktree.sh"

# 使用 exec 替换当前进程，并将所有参数原样转发
exec "$TARGET" "$@"