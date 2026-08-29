import shlex
import subprocess
import sys


def su_run(args, timeout=15):
    """在 root 下执行命令；找不到 su 时抛 FileNotFoundError。"""
    cmd = ["su", "-c", " ".join(shlex.quote(a) for a in args)]
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def main():
    '''启动微信 (Launch WeChat).'''
    # 非 root 直接 am start 会 SecurityException: Permission Denied；经 su 执行，
    # 首次调用会弹出 su 授权对话框，同意后启动。
    try:
        result = su_run(["am", "start", "-n", "com.tencent.mm/com.tencent.mm.ui.LauncherUI"])
    except FileNotFoundError:
        print("未找到 su：需要 root（Magisk / KernelSU 授权后重试）", file=sys.stderr)
        raise SystemExit(2)
    if result.returncode != 0:
        print((result.stderr or result.stdout).strip())
        raise SystemExit(1)
    print("WeChat launched.")
