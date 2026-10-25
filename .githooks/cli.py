#!/usr/bin/env python3
"""githooks 统一命令行入口与规则调度中心。

只依赖 Python 标准库。
提供一致的增量与全量扫描、规则/分类过滤、动态大盘统计与拦截报告。
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

# 确保本目录可作为根包被导入
HOOKS_DIR = Path(__file__).resolve().parent
if str(HOOKS_DIR) not in sys.path:
    sys.path.insert(0, str(HOOKS_DIR))

from checks import ALL_RULE_NAMES, ALL_RULES, RULES_BY_NAME
from checks.code_rules import (
    CODE_RULE_DICT,
    EXCLUDE_PREFIXES as CODE_EXCLUDE_PREFIXES,
    TARGET_SUFFIX as KT_SUFFIX,
    check_code_files,
)
from checks.strings_rules import (
    STRINGS_PATTERN,
    check_res_dir,
    reports_to_check_result,
)
from checks.tokens_rules import (
    EXCLUDE_PREFIXES as TOKEN_EXCLUDE_PREFIXES,
    TARGET_SUFFIX as TOKEN_SUFFIX,
    check_token_limits,
    format_splitting_recipes,
)
from core.git import (
    get_all_tracked_files,
    get_repo_root,
    get_staged_content,
    get_staged_contents,
    get_staged_line_candidates,
    get_staged_size,
    get_worktree_line_candidates,
    git_exec,
    parse_name_status,
)
from core.models import Category, CheckResult, Finding, Severity
from core.reporter import print_findings, print_rules_catalog, print_stat


def resolve_active_rules(
    category_filters: list[str] | None,
    rule_filters: list[str] | None,
) -> set[str]:
    """根据命令行传入的类别与规则筛选计算最终生效的规则名集合。"""
    active_rules = set(ALL_RULE_NAMES)

    if category_filters:
        cats = {c.strip().lower() for cf in category_filters for c in cf.split(",") if c.strip()}
        active_rules = {
            r.name for r in ALL_RULES if r.category.value.lower() in cats
        }

    if rule_filters:
        specific = {rf.strip() for item in rule_filters for rf in item.split(",") if rf.strip()}
        active_rules = active_rules & specific

    return active_rules


def run_checks(
    root: str,
    active_rules: set[str],
    mode: str,  # "staged", "all", "worktree"
    scope: list[str],
) -> CheckResult:
    """按模式调度执行各项检查。"""
    all_findings: list[Finding] = []
    total_ignored = 0
    scanned_count = 0
    notices: list[str] = []

    need_code = any(r in CODE_RULE_DICT for r in active_rules)
    need_tokens = "file-token-limit" in active_rules
    need_strings = any(r in ("strings-alignment", "verbose-ui") for r in active_rules)

    # ----------------------------------------------------------------------
    # 1. Kotlin 源码代码规则与 Token 限制检查
    # ----------------------------------------------------------------------
    if need_code or need_tokens:
        if mode == "all":
            all_kt_files = get_all_tracked_files(
                root, KT_SUFFIX, CODE_EXCLUDE_PREFIXES, scope
            )
            scanned_count += len(all_kt_files)

            if need_code:
                # 全量代码行检查
                files_content: dict[str, str] = {}
                candidates: dict[str, list[int]] = {}
                for p in all_kt_files:
                    try:
                        content = Path(root, p).read_text(encoding="utf-8", errors="replace")
                        files_content[p] = content
                        candidates[p] = list(range(1, len(content.splitlines()) + 1))
                    except OSError:
                        continue
                code_res = check_code_files(files_content, candidates, active_rules)
                all_findings.extend(code_res.findings)
                total_ignored += code_res.ignored

            if need_tokens:
                def fs_size(p: str) -> int:
                    try:
                        return Path(root, p).stat().st_size
                    except OSError:
                        return 0

                def fs_content(p: str) -> str:
                    try:
                        with open(Path(root, p), "r", encoding="utf-8", errors="replace") as f:
                            lines = [f.readline() for _ in range(20)]
                            return "".join(lines)
                    except OSError:
                        return ""

                token_res = check_token_limits(all_kt_files, fs_size, fs_content, active_rules)
                all_findings.extend(token_res.findings)
                total_ignored += token_res.ignored

        elif mode == "worktree":
            # 工作区检查（staged + unstaged）
            worktree_lines = get_worktree_line_candidates(root, KT_SUFFIX, CODE_EXCLUDE_PREFIXES)
            worktree_kt_files = list(worktree_lines.keys())
            scanned_count += len(worktree_kt_files)

            if need_code and worktree_lines:
                files_content = {}
                for p in worktree_kt_files:
                    try:
                        files_content[p] = Path(root, p).read_text(encoding="utf-8", errors="replace")
                    except OSError:
                        continue
                code_res = check_code_files(files_content, worktree_lines, active_rules)
                all_findings.extend(code_res.findings)
                total_ignored += code_res.ignored

            if need_tokens:
                res = git_exec(
                    root,
                    "-c", "core.quotePath=false",
                    "diff", "HEAD", "--name-only", "--diff-filter=ACMR",
                    "--", f"*{TOKEN_SUFFIX}",
                )
                candidate_paths = [
                    p.strip()
                    for p in (res.stdout.splitlines() if isinstance(res.stdout, str) else [])
                    if p.strip()
                ]

                def wt_size(p: str) -> int:
                    try:
                        return Path(root, p).stat().st_size
                    except OSError:
                        return 0

                def wt_content(p: str) -> str:
                    try:
                        with open(Path(root, p), "r", encoding="utf-8", errors="replace") as f:
                            lines = [f.readline() for _ in range(20)]
                            return "".join(lines)
                    except OSError:
                        return ""

                token_res = check_token_limits(candidate_paths, wt_size, wt_content, active_rules)
                all_findings.extend(token_res.findings)
                total_ignored += token_res.ignored

        else:
            # 增量检查（staged）
            staged_lines = get_staged_line_candidates(root, KT_SUFFIX, CODE_EXCLUDE_PREFIXES)
            staged_kt_files = list(staged_lines.keys())
            scanned_count += len(staged_kt_files)

            if need_code and staged_lines:
                files_content = get_staged_contents(root, staged_kt_files)
                code_res = check_code_files(files_content, staged_lines, active_rules)
                all_findings.extend(code_res.findings)
                total_ignored += code_res.ignored

            if need_tokens:
                res = git_exec(
                    root,
                    "-c", "core.quotePath=false",
                    "diff", "--cached", "--name-only", "--diff-filter=ACMR",
                    "--", f"*{TOKEN_SUFFIX}",
                )
                candidate_paths = [
                    p.strip()
                    for p in (res.stdout.splitlines() if isinstance(res.stdout, str) else [])
                    if p.strip()
                ]

                def stg_size(p: str) -> int:
                    return get_staged_size(root, p)

                def stg_content(p: str) -> str:
                    content = get_staged_content(root, p)
                    if content is None:
                        return ""
                    return "\n".join(content.splitlines()[:20])

                token_res = check_token_limits(candidate_paths, stg_size, stg_content, active_rules)
                all_findings.extend(token_res.findings)
                total_ignored += token_res.ignored

    # ----------------------------------------------------------------------
    # 2. 多语言与 UI 文案规范检查
    # ----------------------------------------------------------------------
    if need_strings:
        res_dirs: set[str] = set()
        new_lang_files: list[str] = []
        staged_contents_map: dict[str, str] = {}

        if mode == "all":
            # 扫描全仓所有含 strings.xml 的 res 目录
            for p in Path(root).glob("**/res/values/strings.xml"):
                try:
                    rel = p.relative_to(Path(root)).as_posix()
                    if scope and not any(rel.startswith(s) for s in scope):
                        continue
                    m = STRINGS_PATTERN.match(rel)
                    if m:
                        res_dirs.add(m.group(1))
                except ValueError:
                    pass

            def content_provider(rel_path: str) -> str | None:
                p = Path(root, rel_path)
                if not p.is_file():
                    return None
                try:
                    return p.read_text(encoding="utf-8", errors="replace")
                except OSError:
                    return None

        elif mode == "worktree":
            # 工作区检查（暂存 + 未暂存 + 未跟踪文件）
            staged_diff = git_exec(
                root, "-c", "core.quotePath=false", "diff", "--cached", "--name-status", "-z"
            ).stdout
            unstaged_diff = git_exec(
                root, "-c", "core.quotePath=false", "diff", "--name-status", "-z"
            ).stdout
            untracked_out = git_exec(
                root, "-c", "core.quotePath=false", "ls-files", "--others", "--exclude-standard", "-z"
            ).stdout

            entries = parse_name_status(staged_diff) + parse_name_status(unstaged_diff)
            for p in (untracked_out.split("\0") if isinstance(untracked_out, str) else []):
                if p:
                    entries.append(("A", p))

            for status, path in entries:
                m = STRINGS_PATTERN.match(path)
                if not m:
                    continue
                res_dirs.add(m.group(1))
                if status.startswith("A"):
                    new_lang_files.append(path)

            def content_provider(rel_path: str) -> str | None:
                p = Path(root, rel_path)
                if not p.is_file():
                    return None
                try:
                    return p.read_text(encoding="utf-8", errors="replace")
                except OSError:
                    return None

        else:
            # 增量检查（staged）
            raw_diff = git_exec(
                root, "-c", "core.quotePath=false", "diff", "--cached", "--name-status", "-z"
            ).stdout
            entries = parse_name_status(raw_diff)

            for status, path in entries:
                m = STRINGS_PATTERN.match(path)
                if not m:
                    continue
                res_dirs.add(m.group(1))
                if status.startswith("A"):
                    new_lang_files.append(path)
                if not status.startswith("D"):
                    content = get_staged_content(root, path)
                    if content is not None:
                        staged_contents_map[path] = content

            def content_provider(rel_path: str) -> str | None:
                if rel_path in staged_contents_map:
                    return staged_contents_map[rel_path]
                p = Path(root, rel_path)
                if not p.is_file():
                    return None
                try:
                    return p.read_text(encoding="utf-8", errors="replace")
                except OSError:
                    return None

        reports = []
        for r_dir in sorted(res_dirs):
            extra = set(staged_contents_map.keys()) if staged_contents_map else None
            rep = check_res_dir(root, r_dir, content_provider, extra)
            if rep:
                reports.append(rep)

        strings_res = reports_to_check_result(reports, new_lang_files, active_rules)
        all_findings.extend(strings_res.findings)
        total_ignored += strings_res.ignored
        scanned_count += strings_res.scanned_count
        if strings_res.notices:
            notices.extend(strings_res.notices)

    return CheckResult(
        findings=all_findings,
        ignored=total_ignored,
        scanned_count=scanned_count,
        notices=notices,
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="cli.py",
        description="githooks 统一代码与规范检查器。",
    )
    parser.add_argument(
        "--all", action="store_true",
        help="全量扫描工作区（可跟路径参数限定模块）。",
    )
    parser.add_argument(
        "--staged", action="store_true",
        help="仅检查暂存区增量变动（默认行为）。",
    )
    parser.add_argument(
        "--worktree", action="store_true",
        help="检查工作区变动（含未暂存与已暂存）。",
    )
    parser.add_argument(
        "--category", "-c", action="append", dest="categories",
        help="按领域分类过滤规则 (safety, ui, hygiene, i18n, architecture，支持逗号分隔)。",
    )
    parser.add_argument(
        "--rule", "-r", action="append", dest="rules",
        help="按具体规则名过滤（支持逗号分隔）。",
    )
    parser.add_argument(
        "--stat", action="store_true",
        help="输出按模块统计的动态列宽大盘表格。",
    )
    parser.add_argument(
        "--list-rules", action="store_true",
        help="列出所有受支持的规则分类目录字典。",
    )
    parser.add_argument("scope", nargs="*", help="限定的模块或路径前缀。")

    args = parser.parse_args(argv)

    if args.list_rules:
        return print_rules_catalog(ALL_RULES)

    root = get_repo_root()
    if not root:
        print("githooks: 不在 git 仓库内", file=sys.stderr)
        return 0

    mode = "all" if args.all else ("worktree" if args.worktree else "staged")
    active_rules = resolve_active_rules(args.categories, args.rules)

    result = run_checks(root, active_rules, mode, args.scope)

    if args.stat:
        evaluated = tuple(r for r in ALL_RULE_NAMES if r in active_rules)
        return print_stat(result.findings, result.scanned_count, evaluated)

    # 打印违规明细
    exit_code = print_findings(result.findings, result.ignored, notices=result.notices)

    # 如果有 Token 超标违规，打印拆解指引
    token_violations = [f for f in result.findings if f.rule == "file-token-limit"]
    if token_violations:
        print()
        print(format_splitting_recipes([]))

    return exit_code


if __name__ == "__main__":
    sys.exit(main())
