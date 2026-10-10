#!/usr/bin/env python3
"""从连接的 Android 设备提取已安装应用的 APK（含 split APK）。

用法：python3 extract_apk.py <package_name> [--serial SERIAL] [--force]
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

from _common import (
    adb,
    die,
    ensure_adb,
    extracted_dir,
    normalize_package_name,
    parse_package_lines,
    pick_base_apk,
)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="extract_apk.py",
        description="用 adb 拉取已安装应用的 APK。检测到 split APK 时会拉取全部 split 到同一目录。",
    )
    parser.add_argument("package_name", help="已安装的包名，例如 com.example.app")
    parser.add_argument("--serial", help="adb 设备序列号，多设备时使用")
    parser.add_argument("--force", action="store_true", help="目标已存在时重新拉取")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    package_name = args.package_name.strip()
    if not package_name:
        die("package_name 不能为空")

    ensure_adb(args.serial)
    proc = adb(["shell", "pm", "path", package_name], args.serial)
    if proc.returncode != 0:
        die(f"查询 APK 路径失败：{(proc.stderr or proc.stdout).strip()}")

    remote_paths = parse_package_lines(proc.stdout)
    if not remote_paths:
        die(f"未找到该包或其 APK 路径为空：{package_name}（确认应用已安装：adb shell pm list packages）")

    base_remote = pick_base_apk(remote_paths)
    stem = normalize_package_name(package_name)
    is_split = len(remote_paths) > 1
    target = extracted_dir() / (f"{stem}{'_splits'}" if is_split else f"{stem}.apk")

    if target.exists() and not args.force:
        report(package_name, target, len(remote_paths), "cached")
        return 0

    if target.exists():
        shutil.rmtree(target) if target.is_dir() else target.unlink()

    if is_split:
        target.mkdir(parents=True, exist_ok=True)
        for remote in remote_paths:
            name = Path(remote).name
            pull(remote, target / name, args.serial)
    else:
        target.parent.mkdir(parents=True, exist_ok=True)
        pull(base_remote, target, args.serial)

    report(package_name, target, len(remote_paths), "ok")
    return 0


def pull(remote: str, local: Path, serial: str | None) -> None:
    proc = adb(["pull", remote, str(local)], serial)
    if proc.returncode != 0:
        die(f"拉取失败：{remote}\n{(proc.stderr or proc.stdout).strip()}")


def report(package_name: str, target: Path, remote_count: int, status: str) -> None:
    is_split = target.is_dir()
    print(f"status: {status}")
    print(f"package_name: {package_name}")
    print(f"split_count: {remote_count - 1 if is_split else 0}")
    print(f"apk_input: {target}")


if __name__ == "__main__":
    raise SystemExit(main())
