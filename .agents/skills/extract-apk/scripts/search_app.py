#!/usr/bin/env python3
"""在连接的 Android 设备上按关键字搜索已安装应用的包名。

用法：python3 search_app.py <keyword> [--limit N] [--serial SERIAL]
"""

from __future__ import annotations

import argparse
import sys

from _common import adb, ensure_adb, parse_package_lines

LABEL_NOTE = (
    "adb 拿不到应用显示名（label），英文系统下微信的包名仍是 com.tencent.mm。"
    "下面列出设备上全部第三方应用，按语义自行挑选，或改用包名片段重搜。"
)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="search_app.py",
        description="按关键字搜索设备上已安装应用的包名（adb shell pm list packages）。",
    )
    parser.add_argument("keyword", help="包名关键字，大小写不敏感的子串匹配")
    parser.add_argument("--limit", type=int, default=0, help="最多返回多少条，0 表示不限制")
    parser.add_argument("--serial", help="adb 设备序列号，多设备时使用")
    return parser


def main() -> int:
    args = build_parser().parse_args()

    keyword = args.keyword.strip()
    if not keyword:
        print("keyword 不能为空", file=sys.stderr)
        return 1

    ensure_adb(args.serial)
    packages = list_packages(args.serial, only_third_party=False)
    if packages is None:
        return 1

    needle = keyword.lower()
    matched = sorted(package for package in packages if needle in package.lower())
    if args.limit > 0:
        matched = matched[: args.limit]

    print(f"keyword: {keyword}")
    print(f"matched: {len(matched)}")
    for package in matched:
        print(f"package_name: {package}")
    if matched:
        return 0

    candidates = list_packages(args.serial, only_third_party=True)
    if candidates is None:
        return 1
    candidates = sorted(candidates)
    if args.limit > 0:
        candidates = candidates[: args.limit]

    print(f"note: {LABEL_NOTE}")
    print(f"candidates: {len(candidates)}")
    for package in candidates:
        print(f"device_package: {package}")
    return 0


def list_packages(serial: str | None, *, only_third_party: bool) -> list[str] | None:
    command = ["shell", "pm", "list", "packages"] + (["-3"] if only_third_party else [])
    proc = adb(command, serial)
    if proc.returncode != 0:
        print(f"列出包名失败：{(proc.stderr or proc.stdout).strip()}", file=sys.stderr)
        return None
    return parse_package_lines(proc.stdout)


if __name__ == "__main__":
    raise SystemExit(main())
