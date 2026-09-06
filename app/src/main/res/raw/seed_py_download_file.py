#!/usr/bin/env python3
"""下载工具：给定 URL 下载文件到本地目录。

默认目录为应用私有 downloads 子目录（由宿主注入参数，本脚本不自行决定），
进程私有可写、零外部存储权限负担。下载后清理与重命名交由调用方处理。

用法（Termux 或任意可调 python3 的环境）：
    python3 download_file.py https://example.com/file.png
    python3 download_file.py https://example.com/file.png --filename out.png

退出码：0 成功；1 下载失败。

也可注册为 Zafiro python tool：main(url, filename="", path="")。
"""
import argparse
import json
import os
import sys
import urllib.request
import uuid
from urllib.parse import urlparse

UA = "Mozilla/5.0 (Linux; Android 14)"
DOWNLOAD_TIMEOUT = 120

# 播种时由宿主替换为绝对路径（files/downloads/py_download_file）；
# 占位符未替换时退化为脚本当前目录，命令行直跑仍可用。
DEFAULT_DOWNLOAD_DIR = "__DEFAULT_DOWNLOAD_DIR__"


def resolve_dest(url, filename, download_dir):
    """返回最终落盘路径：显式 filename > URL 提取 > UUID。"""
    if filename:
        return os.path.join(download_dir, filename)
    name = os.path.basename(urlparse(url).path)
    if not name or "." not in name:
        name = str(uuid.uuid4())
    return os.path.join(download_dir, name)


def main(url, filename="", path=""):
    try:
        download_dir = path or DEFAULT_DOWNLOAD_DIR
        os.makedirs(download_dir, exist_ok=True)
        dest = resolve_dest(url, filename, download_dir)

        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=DOWNLOAD_TIMEOUT) as resp, open(dest, "wb") as f:
            while True:
                chunk = resp.read(8192)
                if not chunk:
                    break
                f.write(chunk)

        size = os.path.getsize(dest)
        print(json.dumps({"ok": True, "path": dest, "size": size}))
    except Exception as e:
        print(json.dumps({"ok": False, "error": str(e)}))


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("url", help="目标文件 URL")
    ap.add_argument("--filename", default="", help="期望文件名（不传由 URL 提取）")
    ap.add_argument("--path", default="", help="下载目录（不传用当前目录，宿主调用时注入私有目录）")
    args = ap.parse_args()
    main(args.url, filename=args.filename, path=args.path)
