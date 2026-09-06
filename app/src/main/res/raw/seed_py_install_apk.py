#!/usr/bin/env python3
"""直装工具：给定 APK 直链 URL，用 su 下载并安装到本机 Android。

默认非强制：目标应用已安装时跳过安装。不额外写包名判断 —— 依赖
`pm install` 不带 -r 的既有语义：包已存在时报 INSTALL_FAILED_ALREADY_EXISTS，
此时视为"已安装，跳过"。--force 时加 -r 覆盖安装。

用法（Termux 或任意可调 su 的环境）：
    python3 install_apk.py https://example.com/app.apk
    python3 install_apk.py --force https://example.com/app.apk

退出码：0 已安装；1 安装失败；2 su 不可用/未授权；3 已安装，跳过。

也可注册为 Zafiro python tool：main(url, force=False)。
"""
import argparse
import os
import shlex
import shutil
import subprocess
import sys
import urllib.request
import uuid

INSTALL_TIMEOUT = 300
UA = "Mozilla/5.0 (Linux; Android 14)"

# 播种时由宿主替换为绝对路径（files/downloads/py_install_apk）；
# 占位符未替换时用 tempfile.mkstemp 兼容旧路径。
DEFAULT_DOWNLOAD_DIR = "__DEFAULT_DOWNLOAD_DIR__"


def su_run(args, timeout=INSTALL_TIMEOUT):
    """在 root 下执行命令；找不到 su 时抛 FileNotFoundError。"""
    cmd = ["su", "-c", " ".join(shlex.quote(a) for a in args)]
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def download(url, dest):
    print(f"[1/2] downloading {url}")
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=120) as resp, open(dest, "wb") as f:
        shutil.copyfileobj(resp, f)
    print(f"      saved {dest} ({os.path.getsize(dest)} bytes)")


def install(apk_path, force):
    cmd = ["pm", "install"]
    if force:
        cmd.append("-r")
    cmd.append(apk_path)
    return su_run(cmd)


def main(url, force=False, path=""):
    try:
        probe = su_run(["id"], timeout=10)
    except FileNotFoundError:
        print("未找到 su：需要 root（Magisk / KernelSU 授权后重试）", file=sys.stderr)
        sys.exit(2)
    if probe.returncode != 0:
        print("su 未授权或不可用", file=sys.stderr)
        sys.exit(2)

    download_dir = path or DEFAULT_DOWNLOAD_DIR
    os.makedirs(download_dir, exist_ok=True)
    apk_path = os.path.join(download_dir, f"{uuid.uuid4()}.apk")
    try:
        download(url, apk_path)
        print(f"[2/2] installing via su ({'force' if force else 'non-force'})")
        r = install(apk_path, force)
        out = (r.stdout + r.stderr).strip()
        if r.returncode != 0:
            if "INSTALL_FAILED_ALREADY_EXISTS" in out:
                print("已安装，跳过（加 --force 可覆盖安装）")
                sys.exit(3)
            print(f"install failed: {out or 'unknown error'}", file=sys.stderr)
            sys.exit(1)
        print("installed")
    finally:
        try:
            os.unlink(apk_path)
        except OSError:
            pass


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("url", help="APK 直链 URL")
    ap.add_argument("--force", action="store_true", help="覆盖安装（默认已安装则跳过）")
    ap.add_argument("--path", default="", help="APK 下载目录（不传用默认私有目录）")
    args = ap.parse_args()
    main(args.url, force=args.force, path=args.path)
