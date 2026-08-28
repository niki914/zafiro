import subprocess


def main():
    '''启动微信 (Launch WeChat).'''
    result = subprocess.run(
        ["am", "start", "-n", "com.tencent.mm/com.tencent.mm.ui.LauncherUI"],
        capture_output=True, text=True, timeout=10,
    )
    if result.returncode != 0:
        print(result.stderr.strip())
        raise SystemExit(1)
    print("WeChat launched.")
