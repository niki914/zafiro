#!/usr/bin/env python3
"""兼容入口：Kotlin 代码坏味道检查。

转发至 cli.py，并保留原有纯函数接口以兼容历史导入与单测。
"""

from __future__ import annotations

import sys
from pathlib import Path

HOOKS_DIR = Path(__file__).resolve().parent
if str(HOOKS_DIR) not in sys.path:
    sys.path.insert(0, str(HOOKS_DIR))

from checks.code_rules import (
    CODE_RULE_DICT,
    CODE_RULES,
    EXCLUDE_PREFIXES,
    TARGET_SUFFIX,
    check_code_files,
)
from core.git import parse_diff_added_lines
from core.models import Severity

RULE_NAMES = tuple(r.name for r, _ in CODE_RULES)
SEVERITY = {r.name: r.severity.value for r, _ in CODE_RULES}


def excluded(path: str) -> bool:
    return any(path.startswith(p) for p in EXCLUDE_PREFIXES)


def parse_diff(diff_text: str) -> dict[str, list[int]]:
    return parse_diff_added_lines(diff_text, TARGET_SUFFIX, EXCLUDE_PREFIXES)


def collect_findings(
    files: dict[str, str],
    candidates: dict[str, list[int]],
    rules: tuple[str, ...] = RULE_NAMES,
):
    active = set(rules)
    res = check_code_files(files, candidates, active)
    return res.findings, res.ignored


def main(argv: list[str] | None = None) -> int:
    from cli import main as cli_main

    args = list(argv) if argv is not None else sys.argv[1:]
    # 如果没有指定 category 或 rule，默认执行全部 Kotlin 代码坏味道规则
    if not any(a in ("--category", "-c", "--rule", "-r") for a in args):
        args = ["--rule", ",".join(RULE_NAMES), *args]
    return cli_main(args)


if __name__ == "__main__":
    sys.exit(main())
