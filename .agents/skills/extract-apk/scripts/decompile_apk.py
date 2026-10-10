#!/usr/bin/env python3
"""用 jadx 反编译本地 APK（单文件或 split 目录），产出静态源码树。

用法：python3 decompile_apk.py <apk_path_or_dir> [--force] [--no-res]
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

from _common import (
    apk_input_stem,
    apk_inputs,
    decompiled_dir,
    default_threads,
    default_xmx_mb,
    die,
    ensure_jadx,
    progress_percent,
    tail,
)

RGIGNORE = """# common resource noise
resources/assets/
resources/lib/
resources/META-INF/

# common framework and stdlib sources
sources/android/
sources/androidx/
sources/javax/
sources/kotlin/
sources/kotlinx/
"""


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="decompile_apk.py",
        description="用 jadx 反编译 APK。传入 split 目录时会一次性喂入目录内全部 APK。",
    )
    parser.add_argument("apk", help="APK 文件路径，或包含 base.apk 与 split 的目录")
    parser.add_argument("--force", action="store_true", help="产物已存在时删除后重新反编译")
    parser.add_argument("--no-res", action="store_true", help="不解析资源（只产出 sources）")
    return parser


def main() -> int:
    args = build_parser().parse_args()

    target = Path(args.apk).expanduser()
    if not target.exists():
        die(f"输入不存在：{target}")
    inputs = apk_inputs(target)
    if not inputs:
        die(f"目录内没有 .apk 文件：{target}")

    out_dir = decompiled_dir(apk_input_stem(target))
    sources_dir = out_dir / "sources"
    if out_dir.exists():
        if sources_dir.exists() and not args.force:
            report(out_dir, inputs, "cached", no_res=args.no_res)
            return 0
        shutil.rmtree(out_dir)

    jadx = ensure_jadx()
    out_dir.mkdir(parents=True, exist_ok=True)

    threads = default_threads()
    env = dict(os.environ)
    env.setdefault("JADX_OPTS", f"-Xmx{default_xmx_mb()}m")
    command = [jadx, "-j", str(threads), "--log-level", "progress", "-d", str(out_dir)]
    if args.no_res:
        command.append("--no-res")
    command += [str(path) for path in inputs]

    print(
        f"开始反编译：{len(inputs)} 个输入，{threads} 线程，{env['JADX_OPTS']}",
        file=sys.stderr,
    )
    log_lines, exit_code = run_jadx(command, env)

    log_path = out_dir / "jadx.log"
    log_path.write_text("".join(log_lines), encoding="utf-8")

    if not sources_dir.exists():
        die(f"jadx 未产出 sources 目录（退出码 {exit_code}）。日志尾部：\n{tail(''.join(log_lines))}\n完整日志：{log_path}")
    if exit_code != 0:
        print(
            f"警告：jadx 退出码 {exit_code}，部分类反编译失败，sources 已产出。"
            f"错误统计见 {log_path}（搜 'finished with errors'）。",
            file=sys.stderr,
        )

    (out_dir / ".rgignore").write_text(RGIGNORE, encoding="utf-8")
    report(out_dir, inputs, "ok", no_res=args.no_res)
    return 0


def run_jadx(command: list[str], env: dict[str, str]) -> tuple[list[str], int]:
    proc = subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
        env=env,
    )
    assert proc.stdout is not None
    log_lines: list[str] = []
    last_percent = None
    for line in proc.stdout:
        log_lines.append(line)
        percent = progress_percent(line)
        if percent and percent != last_percent:
            last_percent = percent
            print(f"\rprogress: {percent}%", end="", file=sys.stderr, flush=True)
    if last_percent:
        print("", file=sys.stderr)
    return log_lines, proc.wait()


def report(out_dir: Path, inputs: list[Path], status: str, *, no_res: bool) -> None:
    sources_dir = out_dir / "sources"
    manifest = out_dir / "resources" / "AndroidManifest.xml"
    print(f"status: {status}")
    print(f"input_count: {len(inputs)}")
    print(f"output_dir: {out_dir}")
    print(f"sources_path: {sources_dir}")
    print(f"manifest_path: {manifest if manifest.exists() else 'null'}")
    if no_res:
        print("note: 使用了 --no-res，资源与 manifest 未产出")


if __name__ == "__main__":
    raise SystemExit(main())
