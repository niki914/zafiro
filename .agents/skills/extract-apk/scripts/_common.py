#!/usr/bin/env python3
"""extract-apk skill 的共享工具：路径约定、adb 封装、纯函数解析。

自检：python3 _common.py
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import List, Optional, Sequence

DEFAULT_ROOT_NAME = ".apk_decs"
ROOT_ENV = "APK_DECS_ROOT"
PROGRESS_RE = re.compile(r"progress:.*?\((\d+)%\)")
PROGRESS_FALLBACK_RE = re.compile(r"progress:\s*(\d+)\s*%")
SPLITS_SUFFIX = "_splits"


def die(message: str) -> "None":
    print(message, file=sys.stderr)
    raise SystemExit(1)


# --- 路径约定 ---------------------------------------------------------------


def root_dir() -> Path:
    """默认落在运行脚本时的工作目录下，可用 APK_DECS_ROOT 覆盖。"""
    override = os.environ.get(ROOT_ENV, "").strip()
    if override:
        return Path(override).expanduser()
    return Path.cwd() / DEFAULT_ROOT_NAME


def extracted_dir() -> Path:
    return root_dir() / "extracted_apps"


def decompiled_dir(stem: str) -> Path:
    return root_dir() / f"{stem}_apk"


def normalize_package_name(package_name: str) -> str:
    return package_name.replace(".", "_")


def apk_inputs(target: Path) -> List[Path]:
    """文件 → [本身]；目录 → 目录内 *.apk，base.apk 优先。"""
    if not target.is_dir():
        return [target]
    files = sorted(target.glob("*.apk"))
    return sorted(files, key=lambda p: (p.name != "base.apk", p.name))


def apk_input_stem(target: Path) -> str:
    if not target.is_dir():
        return target.stem
    name = target.name
    return name[: -len(SPLITS_SUFFIX)] if name.endswith(SPLITS_SUFFIX) else name


# --- 纯解析 -----------------------------------------------------------------


def parse_package_lines(text: str) -> List[str]:
    values: List[str] = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("package:"):
            line = line[len("package:") :]
        values.append(line)
    return values


def pick_base_apk(apk_paths: Sequence[str]) -> str:
    for apk_path in apk_paths:
        if apk_path.endswith("/base.apk"):
            return apk_path
    return apk_paths[0]


def progress_percent(line: str) -> Optional[str]:
    """jadx 1.5 输出形如 'progress: 8264 of 14571 (56%)'，取括号内百分比。"""
    match = PROGRESS_RE.search(line) or PROGRESS_FALLBACK_RE.search(line)
    return match.group(1) if match else None


# --- 外部命令 ---------------------------------------------------------------


def run(command: Sequence[str]) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(list(command), text=True, capture_output=True)
    except FileNotFoundError:
        die(f"命令不存在：{command[0]}")


def adb(args: Sequence[str], serial: Optional[str] = None) -> subprocess.CompletedProcess:
    command = ["adb"] + (["-s", serial] if serial else []) + list(args)
    return run(command)


def ensure_adb(serial: Optional[str] = None) -> None:
    proc = adb(["get-state"], serial)
    if proc.returncode == 0:
        return
    detail = (proc.stderr or proc.stdout).strip()
    devices = adb(["devices"]).stdout.strip()
    die(
        "adb 设备未就绪。\n"
        f"get-state 输出：{detail or '(空)'}\n"
        f"当前设备列表：\n{devices}\n"
        "提示：多设备时用 --serial 指定，或设置 ANDROID_SERIAL。"
    )


def tail(text: str, lines: int = 20) -> str:
    rows = text.splitlines()
    return "\n".join(rows[-lines:])


def physical_cores() -> int:
    if sys.platform == "darwin":
        proc = run(["sysctl", "-n", "hw.physicalcpu"])
        value = proc.stdout.strip()
        if proc.returncode == 0 and value.isdigit():
            return int(value)
    return os.cpu_count() or 1


def default_threads() -> int:
    return max(1, physical_cores() * 60 // 100)


def default_xmx_mb() -> int:
    mem_bytes = 0
    if sys.platform == "darwin":
        proc = run(["sysctl", "-n", "hw.memsize"])
        if proc.returncode == 0 and proc.stdout.strip().isdigit():
            mem_bytes = int(proc.stdout.strip())
    if not mem_bytes:
        try:
            mem_bytes = os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES")
        except (ValueError, OSError, AttributeError):
            mem_bytes = 0
    if mem_bytes <= 0:
        return 4096
    xmx = max(4096, mem_bytes // 1024 // 1024 * 50 // 100)
    return xmx // 512 * 512


def ensure_jadx() -> str:
    jadx = shutil.which("jadx")
    if jadx is None:
        hints = ["未找到 jadx，请先安装。"]
        brew = shutil.which("brew")
        if brew:
            if run([brew, "list", "--formula", "jadx"]).returncode == 0:
                hints.append("Homebrew 记录显示 jadx 已安装但不在 PATH，请检查 brew --prefix 下的 bin。")
            else:
                hints.append("安装命令：brew install jadx")
        else:
            hints.append("未检测到 Homebrew，请从 https://github.com/skylot/jadx 安装 jadx 并加入 PATH。")
        die("\n".join(hints))

    if shutil.which("java") is None:
        die("未找到 java。jadx 需要 JRE/JDK，请先安装：brew install openjdk")

    version = run([jadx, "--version"])
    version_text = (version.stdout or version.stderr).strip() or "unknown"
    print(f"jadx: {jadx} ({version_text})", file=sys.stderr)
    return jadx


# --- 自检 -------------------------------------------------------------------


def _self_check() -> None:
    assert parse_package_lines("package:/data/app/x/base.apk\n\n") == ["/data/app/x/base.apk"]
    assert parse_package_lines("/data/app/y/split_config.en.apk") == ["/data/app/y/split_config.en.apk"]
    paths = [
        "/data/app/x/split_config.en.apk",
        "/data/app/x/base.apk",
        "/data/app/x/split_config.arm64.apk",
    ]
    assert pick_base_apk(paths) == "/data/app/x/base.apk"
    assert pick_base_apk(["/other/only.apk"]) == "/other/only.apk"
    assert normalize_package_name("com.x.y") == "com_x_y"
    assert progress_percent("INFO  - progress: 12 % (1/2)") == "12"
    assert progress_percent("INFO  - progress: 12%") == "12"
    assert progress_percent("INFO  - progress: 8264 of 14571 (56%)") == "56"
    assert progress_percent("nothing here") is None
    assert apk_input_stem(Path("/t/com_x.apk")) == "com_x"
    assert apk_inputs(Path("/t/com_x.apk")) == [Path("/t/com_x.apk")]
    with tempfile.TemporaryDirectory() as tmp:
        splits = Path(tmp) / "com_x_splits"
        splits.mkdir()
        (splits / "split_config.en.apk").write_text("")
        (splits / "base.apk").write_text("")
        assert apk_input_stem(splits) == "com_x"
        assert [p.name for p in apk_inputs(splits)] == ["base.apk", "split_config.en.apk"]
    assert default_threads() >= 1
    assert default_xmx_mb() >= 4096 and default_xmx_mb() % 512 == 0
    assert root_dir() == Path.cwd() / ".apk_decs"
    os.environ["APK_DECS_ROOT"] = "/tmp/override_root"
    assert root_dir() == Path("/tmp/override_root")
    del os.environ["APK_DECS_ROOT"]
    print("_common.py self-check ok")


if __name__ == "__main__":
    _self_check()
