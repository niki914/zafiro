"""Git 仓储交互与暂存区数据提取统一工具模块。

只依赖 Python 标准库。提供轻量、健壮的 git 命令调用与 diff/文件解析。
"""

from __future__ import annotations

import re
import subprocess
from collections import defaultdict
from pathlib import Path


def git_exec(
    root: str, *args: str, text: bool = True
) -> subprocess.CompletedProcess[str] | subprocess.CompletedProcess[bytes]:
    """在指定 root 路径下执行 git 命令。"""
    return subprocess.run(
        ["git", "-C", root, *args],
        text=text,
        capture_output=True,
        check=False,
    )


def get_repo_root(cwd: str | Path | None = None) -> str:
    """获取当前工作目录所在的 git 仓库根目录。"""
    working_dir = str(cwd or Path.cwd())
    proc = subprocess.run(
        ["git", "-C", working_dir, "rev-parse", "--show-toplevel"],
        text=True,
        capture_output=True,
        check=False,
    )
    return proc.stdout.strip()


def parse_diff_added_lines(
    diff_text: str,
    target_suffix: str = ".kt",
    exclude_prefixes: tuple[str, ...] = ("libs/",),
) -> dict[str, list[int]]:
    """解析 git diff -U0 的输出，提取新增行号映射 {path: [lineno, ...]}。"""
    candidates: dict[str, list[int]] = defaultdict(list)
    path = None
    lineno = 0

    for line in diff_text.splitlines():
        if line.startswith("+++ b/"):
            path = line[6:]
        elif line.startswith("@@"):
            m = re.search(r"\+(\d+)", line)
            lineno = int(m.group(1)) if m else 0
        elif line.startswith("+") and not line.startswith("+++"):
            if path and path.endswith(target_suffix) and not any(path.startswith(p) for p in exclude_prefixes):
                candidates[path].append(lineno)
            lineno += 1
        elif line.startswith(" "):
            lineno += 1

    return dict(candidates)


def get_staged_line_candidates(
    root: str,
    target_suffix: str = ".kt",
    exclude_prefixes: tuple[str, ...] = ("libs/",),
) -> dict[str, list[int]]:
    """获取暂存区新增/修改文件中新增的行号。"""
    res = git_exec(
        root,
        "-c",
        "core.quotePath=false",
        "diff",
        "--cached",
        "-U0",
        "--diff-filter=ACMR",
        "--",
        f"*{target_suffix}",
    )
    if res.returncode != 0 or not isinstance(res.stdout, str):
        return {}
    return parse_diff_added_lines(res.stdout, target_suffix, exclude_prefixes)


def get_worktree_line_candidates(
    root: str,
    target_suffix: str = ".kt",
    exclude_prefixes: tuple[str, ...] = ("libs/",),
) -> dict[str, list[int]]:
    """获取工作区（含暂存与未暂存）新增/修改文件中新增的行号。"""
    res = git_exec(
        root,
        "-c",
        "core.quotePath=false",
        "diff",
        "HEAD",
        "-U0",
        "--diff-filter=ACMR",
        "--",
        f"*{target_suffix}",
    )
    if res.returncode != 0 or not isinstance(res.stdout, str):
        return {}
    return parse_diff_added_lines(res.stdout, target_suffix, exclude_prefixes)


def get_staged_content(root: str, path: str) -> str | None:
    """从 Git 索引区（暂存区）直接读取指定文件内容。"""
    res = git_exec(root, "show", f":{path}")
    if res.returncode == 0 and isinstance(res.stdout, str):
        return res.stdout
    return None


def get_staged_contents(root: str, paths: list[str]) -> dict[str, str]:
    """批量读取暂存区文件内容。"""
    files: dict[str, str] = {}
    for p in paths:
        content = get_staged_content(root, p)
        if content is not None:
            files[p] = content
    return files


def get_staged_size(root: str, path: str) -> int:
    """从 Git 对象库查询暂存区对应文件的精确字节数。"""
    res = git_exec(root, "cat-file", "-s", f":{path}")
    if res.returncode == 0 and isinstance(res.stdout, str) and res.stdout.strip().isdigit():
        return int(res.stdout.strip())
    return 0


def get_all_tracked_files(
    root: str,
    target_suffix: str = ".kt",
    exclude_prefixes: tuple[str, ...] = ("libs/",),
    scope: list[str] | None = None,
) -> list[str]:
    """获取工作区所有已跟踪的匹配后缀文件。"""
    res = git_exec(
        root,
        "-c",
        "core.quotePath=false",
        "ls-files",
        "-z",
        f"*{target_suffix}",
    )
    if res.returncode != 0 or not isinstance(res.stdout, str):
        return []

    paths: list[str] = []
    for p in res.stdout.split("\0"):
        if not p:
            continue
        if any(p.startswith(prefix) for prefix in exclude_prefixes):
            continue
        if scope and not any(p.startswith(s) for s in scope):
            continue
        paths.append(p)
    return paths


def parse_name_status(data: bytes | str) -> list[tuple[str, str]]:
    """解析 git diff --name-status -z 的输出为 [(status, path), ...]"""
    if isinstance(data, bytes):
        raw_parts = [p.decode("utf-8", errors="replace") for p in data.split(b"\x00") if p]
    else:
        raw_parts = [p for p in data.split("\0") if p]

    results: list[tuple[str, str]] = []
    i = 0
    while i < len(raw_parts):
        status = raw_parts[i]
        if status.startswith(("R", "C")):
            new_path = raw_parts[i + 2] if i + 2 < len(raw_parts) else ""
            results.append((status, new_path))
            i += 3
        else:
            path = raw_parts[i + 1] if i + 1 < len(raw_parts) else ""
            results.append((status, path))
            i += 2
    return results
