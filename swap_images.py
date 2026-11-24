#!/usr/bin/env python3
"""
swap_images.py

切换 Markdown 文件中指向本仓库的图片链接：
- --local: 将远程 GitHub 图片 URL 切换为本地相对路径 (例如 ./res/...)
- --remote: 将本地图片路径切换为远程 GitHub raw/blob URL (例如 https://github.com/niki914/zafiro/blob/main/res/...)
"""

import argparse
import re
import sys
from pathlib import Path

# 支持匹配各种 GitHub 组织/用户下的 zafiro 仓库分支/raw/blob 链接
# 示例：
# https://github.com/niki914/zafiro/blob/main/res/demos/demo.gif?raw=true
# https://raw.githubusercontent.com/niki914/zafiro/main/res/demos/demo.gif
REMOTE_PATTERN = re.compile(
    r"https?://(?:raw\.githubusercontent\.com/[^/]+/zafiro/[^/]+|github\.com/[^/]+/zafiro/(?:blob|raw)/[^/]+)/(res/[^\s\"'>\)]+)"
)

# 本地路径匹配：./res/... 或 直接 res/...
LOCAL_PATTERN = re.compile(
    r"(?<![a-zA-Z0-9_\-\./])(?:\./)?(res/[^\s\"'>\)]+)"
)

DEFAULT_REMOTE_PREFIX = "https://github.com/niki914/zafiro/blob/main"


def to_local_path(match: re.Match) -> str:
    path_inside_repo = match.group(1)
    # 去除可能存在的 ?raw=true 等 URL query 参数
    clean_path = path_inside_repo.split("?")[0]
    return clean_path


def to_remote_url(match: re.Match, base_url: str = DEFAULT_REMOTE_PREFIX) -> str:
    path_inside_repo = match.group(1)
    clean_path = path_inside_repo.split("?")[0]
    return f"{base_url}/{clean_path}?raw=true"


def process_content(content: str, mode: str, base_url: str = DEFAULT_REMOTE_PREFIX) -> tuple[str, int]:
    count = 0

    if mode == "local":
        def repl(m: re.Match) -> str:
            nonlocal count
            count += 1
            return to_local_path(m)

        new_content = REMOTE_PATTERN.sub(repl, content)

    elif mode == "remote":
        # 如果已经存在远程链接则不要重复替换，LOCAL_PATTERN 通过负向后行断言避免了匹配 https://.../res/...
        def repl(m: re.Match) -> str:
            nonlocal count
            count += 1
            return to_remote_url(m, base_url)

        new_content = LOCAL_PATTERN.sub(repl, content)

    else:
        raise ValueError(f"Unknown mode: {mode}")

    return new_content, count


def main():
    parser = argparse.ArgumentParser(
        description="Swap image URLs between local relative paths and remote GitHub URLs in Markdown files."
    )
    parser.add_argument("files", nargs="+", help="Markdown file(s) to process (e.g. README.md)")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--local", action="store_true", help="Switch images to local paths (./res/...)")
    group.add_argument("--remote", action="store_true", help="Switch images to remote URLs (https://github.com/.../res/...)")
    parser.add_argument(
        "--base-url",
        default=DEFAULT_REMOTE_PREFIX,
        help=f"Base GitHub URL for remote mode (default: {DEFAULT_REMOTE_PREFIX})",
    )
    parser.add_argument("--dry-run", action="store_true", help="Print changes without modifying files")

    args = parser.parse_args()
    mode = "local" if args.local else "remote"

    for file_str in args.files:
        file_path = Path(file_str)
        if not file_path.is_file():
            print(f"Error: {file_path} is not a valid file.", file=sys.stderr)
            continue

        try:
            content = file_path.read_text(encoding="utf-8")
        except Exception as e:
            print(f"Error reading {file_path}: {e}", file=sys.stderr)
            continue

        new_content, count = process_content(content, mode, args.base_url)

        if count == 0:
            print(f"[{file_path}] No matching images found to swap to {mode}.")
            continue

        if args.dry_run:
            print(f"[{file_path}] (Dry-run) Would swap {count} image(s) to {mode}.")
        else:
            file_path.write_text(new_content, encoding="utf-8")
            print(f"[{file_path}] Successfully swapped {count} image(s) to {mode}.")


if __name__ == "__main__":
    main()
