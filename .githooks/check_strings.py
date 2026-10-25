#!/usr/bin/env python3
"""兼容入口：多语言资源对齐与 UI 文案检查。

转发至 cli.py，并保留原有纯函数接口以兼容历史导入与单测。
"""

from __future__ import annotations

import sys
from pathlib import Path

HOOKS_DIR = Path(__file__).resolve().parent
if str(HOOKS_DIR) not in sys.path:
    sys.path.insert(0, str(HOOKS_DIR))

from checks.strings_rules import (
    KNOWN_LOCALES,
    ModuleReport,
    ParsedStrings,
    STRINGS_PATTERN,
    TargetLanguageReport,
    VerboseUiWarning,
    check_res_dir,
    check_verbose_ui,
    discover_locale_files,
    parse_strings_xml,
)
from core.git import parse_name_status


STRING_RULE_NAMES = ("strings-alignment", "verbose-ui")


def check_single_res_dir(
    root: str,
    res_dir: str,
    content_provider,
    extra_paths: set[str] | None = None,
) -> ModuleReport | None:
    return check_res_dir(root, res_dir, content_provider, extra_paths)


def main(argv: list[str] | None = None) -> int:
    from cli import main as cli_main

    args = list(argv) if argv is not None else sys.argv[1:]
    if "--stat" in args:
        from core.git import get_repo_root
        from checks.strings_rules import check_res_dir, print_stat_table, STRINGS_PATTERN
        root = get_repo_root()
        if root:
            res_dirs: set[str] = set()
            for p in Path(root).glob("**/res/values*/strings.xml"):
                try:
                    rel = p.relative_to(Path(root)).as_posix()
                    m = STRINGS_PATTERN.match(rel)
                    if m:
                        res_dirs.add(m.group(1))
                except ValueError:
                    pass
            def cp(rel_path: str) -> str | None:
                f = Path(root, rel_path)
                return f.read_text(encoding="utf-8", errors="replace") if f.is_file() else None
            reports = [check_res_dir(root, d, cp) for d in sorted(res_dirs)]
            return print_stat_table([r for r in reports if r])

    if not any(a in ("--category", "-c", "--rule", "-r") for a in args):
        args = ["--rule", ",".join(STRING_RULE_NAMES), *args]
    return cli_main(args)


if __name__ == "__main__":
    sys.exit(main())
