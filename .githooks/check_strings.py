#!/usr/bin/env python3
"""多语言资源（strings.xml）键值对齐检查。

只依赖 Python 标准库。
- 默认（--staged）：检查暂存区中修改了 strings.xml 的模块（增量）；
- --worktree：检查工作区中有修改的模块（未暂存 + 暂存）；
- --all：检查整个仓库所有模块（全量）；
- --stat：输出模块多语言对齐统计表；
- 若检测到新增语言文件（status=A），会输出提示检查全项目语种支持范围。
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

STRINGS_PATTERN = re.compile(r"^(.*?/res)/values([^/]*)/strings\.xml$")

KNOWN_LOCALES = (
    "values",
    "values-en",
    "values-b+zh+Hant",
    "values-es",
    "values-ja",
    "values-ru",
)


@dataclass(frozen=True)
class ParsedStrings:
    all_keys: frozenset[str]
    translatable_keys: frozenset[str]
    untranslatable_keys: frozenset[str]
    duplicates: tuple[str, ...]
    total_count: int


@dataclass(frozen=True)
class TargetLanguageReport:
    path: str
    locale: str
    total_count: int
    missing_keys: tuple[str, ...]
    extra_keys: tuple[str, ...]
    duplicates: tuple[str, ...]
    xml_error: str | None = None

    @property
    def has_error(self) -> bool:
        return bool(self.xml_error or self.missing_keys or self.extra_keys or self.duplicates)


@dataclass(frozen=True)
class ModuleReport:
    res_dir: str
    base_file: str | None
    base_total: int
    target_reports: list[TargetLanguageReport]
    base_error: str | None = None

    @property
    def has_error(self) -> bool:
        return bool(self.base_error or any(t.has_error for t in self.target_reports))


def git(root: str, *args: str) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        ["git", "-C", root, *args], capture_output=True, check=False
    )


def parse_name_status(data: bytes) -> list[tuple[str, str]]:
    """解析 git diff --name-status -z 的输出为 [(status, path), ...]"""
    parts = [p.decode("utf-8", errors="replace") for p in data.split(b"\x00") if p]
    results: list[tuple[str, str]] = []
    i = 0
    while i < len(parts):
        status = parts[i]
        if status.startswith(("R", "C")):
            new_path = parts[i + 2] if i + 2 < len(parts) else ""
            results.append((status, new_path))
            i += 3
        else:
            path = parts[i + 1] if i + 1 < len(parts) else ""
            results.append((status, path))
            i += 2
    return results


def parse_strings_xml(content: str) -> tuple[ParsedStrings | None, str | None]:
    """解析 strings.xml 内容为 ParsedStrings。返回 (parsed, error_message)。"""
    try:
        root = ET.fromstring(content)
    except ET.ParseError as e:
        return None, str(e)

    raw_names: list[str] = []
    translatable_keys: set[str] = set()
    untranslatable_keys: set[str] = set()

    for child in root:
        if child.tag in ("string", "plurals", "string-array"):
            name = child.attrib.get("name")
            if not name:
                continue
            raw_names.append(name)
            is_trans = child.attrib.get("translatable", "true").lower() != "false"
            if is_trans:
                translatable_keys.add(name)
            else:
                untranslatable_keys.add(name)

    counts = Counter(raw_names)
    duplicates = tuple(sorted(n for n, c in counts.items() if c > 1))

    return ParsedStrings(
        all_keys=frozenset(raw_names),
        translatable_keys=frozenset(translatable_keys),
        untranslatable_keys=frozenset(untranslatable_keys),
        duplicates=duplicates,
        total_count=len(raw_names),
    ), None


def find_all_res_dirs(root: str, scope: list[str]) -> set[str]:
    """扫描全仓所有包含 values*/strings.xml 的 res 目录。"""
    res_dirs: set[str] = set()
    root_path = Path(root)
    for p in root_path.glob("**/res/values*/strings.xml"):
        rel = p.relative_to(root_path).as_posix()
        if scope and not any(rel.startswith(s) for s in scope):
            continue
        m = STRINGS_PATTERN.match(rel)
        if m:
            res_dirs.add(m.group(1))
    return res_dirs


def get_staged_changes(root: str) -> tuple[set[str], list[str], dict[str, str]]:
    """获取暂存区变动。
    返回 (affected_res_dirs, new_language_files, staged_contents_map)
    """
    out = git(root, "-c", "core.quotePath=false", "diff", "--cached", "--name-status", "-z").stdout
    entries = parse_name_status(out)

    affected_res_dirs: set[str] = set()
    new_language_files: list[str] = []
    staged_contents: dict[str, str] = {}

    for status, path in entries:
        m = STRINGS_PATTERN.match(path)
        if not m:
            continue
        res_dir = m.group(1)
        suffix = m.group(2)
        affected_res_dirs.add(res_dir)

        if status.startswith("A"):
            new_language_files.append(path)

        if not status.startswith("D"):
            show_res = git(root, "show", f":{path}")
            if show_res.returncode == 0:
                staged_contents[path] = show_res.stdout.decode("utf-8", errors="replace")

    return affected_res_dirs, new_language_files, staged_contents


def get_worktree_changes(root: str) -> tuple[set[str], list[str]]:
    """获取工作区变动（暂存 + 未暂存）。"""
    affected_res_dirs: set[str] = set()
    new_language_files: list[str] = []

    # 1. 暂存区
    staged_out = git(root, "-c", "core.quotePath=false", "diff", "--cached", "--name-status", "-z").stdout
    # 2. 工作区修改
    unstaged_out = git(root, "-c", "core.quotePath=false", "diff", "--name-status", "-z").stdout
    # 3. 未跟踪文件
    untracked_out = git(root, "-c", "core.quotePath=false", "ls-files", "--others", "--exclude-standard", "-z").stdout

    all_entries = parse_name_status(staged_out) + parse_name_status(unstaged_out)
    for p in [x.decode("utf-8", errors="replace") for x in untracked_out.split(b"\x00") if x]:
        all_entries.append(("A", p))

    for status, path in all_entries:
        m = STRINGS_PATTERN.match(path)
        if not m:
            continue
        res_dir = m.group(1)
        affected_res_dirs.add(res_dir)
        if status.startswith("A"):
            new_language_files.append(path)

    return affected_res_dirs, new_language_files


def discover_locale_files(root: str, res_dir: str, extra_paths: set[str] | None = None) -> dict[str, str]:
    """发现给定 res 目录下所有语言版本的 strings.xml。
    返回 {locale_suffix: relative_path}
    """
    res_path = Path(root, res_dir)
    results: dict[str, str] = {}

    all_candidate_paths: set[str] = set()
    if res_path.is_dir():
        for p in res_path.glob("values*/strings.xml"):
            all_candidate_paths.add(p.relative_to(Path(root)).as_posix())

    if extra_paths:
        for p in extra_paths:
            if p.startswith(res_dir + "/values"):
                all_candidate_paths.add(p)

    for rel in all_candidate_paths:
        m = STRINGS_PATTERN.match(rel)
        if m:
            suffix = m.group(2)
            results[suffix] = rel

    return results


def check_single_res_dir(
    root: str,
    res_dir: str,
    content_provider,
    extra_paths: set[str] | None = None,
) -> ModuleReport | None:
    """检查单个 res 目录下的各语言 strings.xml 对齐情况。"""
    locale_map = discover_locale_files(root, res_dir, extra_paths)
    if not locale_map:
        return None

    base_path = locale_map.get("")
    if not base_path:
        return ModuleReport(
            res_dir=res_dir,
            base_file=None,
            base_total=0,
            target_reports=[],
            base_error="缺少基准语言文件 (values/strings.xml)",
        )

    base_content = content_provider(base_path)
    if base_content is None:
        return ModuleReport(
            res_dir=res_dir,
            base_file=base_path,
            base_total=0,
            target_reports=[],
            base_error=f"无法读取基准语言文件: {base_path}",
        )

    base_parsed, base_err = parse_strings_xml(base_content)
    if base_err:
        return ModuleReport(
            res_dir=res_dir,
            base_file=base_path,
            base_total=0,
            target_reports=[],
            base_error=f"基准语言 XML 解析失败: {base_err}",
        )

    target_reports: list[TargetLanguageReport] = []
    for suffix, target_path in sorted(locale_map.items()):
        if suffix == "":
            continue
        locale_name = f"values{suffix}"
        target_content = content_provider(target_path)
        if target_content is None:
            target_reports.append(
                TargetLanguageReport(
                    path=target_path,
                    locale=locale_name,
                    total_count=0,
                    missing_keys=(),
                    extra_keys=(),
                    duplicates=(),
                    xml_error=f"无法读取文件: {target_path}",
                )
            )
            continue

        target_parsed, target_err = parse_strings_xml(target_content)
        if target_err:
            target_reports.append(
                TargetLanguageReport(
                    path=target_path,
                    locale=locale_name,
                    total_count=0,
                    missing_keys=(),
                    extra_keys=(),
                    duplicates=(),
                    xml_error=f"XML 解析失败: {target_err}",
                )
            )
            continue

        missing = tuple(sorted(base_parsed.translatable_keys - target_parsed.all_keys))
        extra = tuple(
            sorted(
                target_parsed.all_keys
                - (base_parsed.translatable_keys | base_parsed.untranslatable_keys)
            )
        )
        target_reports.append(
            TargetLanguageReport(
                path=target_path,
                locale=locale_name,
                total_count=target_parsed.total_count,
                missing_keys=missing,
                extra_keys=extra,
                duplicates=target_parsed.duplicates,
            )
        )

    return ModuleReport(
        res_dir=res_dir,
        base_file=base_path,
        base_total=base_parsed.total_count,
        target_reports=target_reports,
    )


def print_stat_table(reports: list[ModuleReport]) -> int:
    """输出各模块多语言键值统计大盘。"""
    all_locales: set[str] = set()
    for r in reports:
        for t in r.target_reports:
            all_locales.add(t.locale)
    sorted_targets = sorted(all_locales)

    headers = ["module", "values"] + sorted_targets + ["status"]
    rows = []
    for r in sorted(reports, key=lambda x: x.res_dir):
        row = [r.res_dir]
        if r.base_error:
            row.extend(["ERR"] * (len(headers) - 2))
            row.append(f"错误: {r.base_error}")
        else:
            row.append(str(r.base_total))
            loc_counts = {t.locale: t.total_count for t in r.target_reports}
            has_mismatch = False
            for loc in sorted_targets:
                if loc in loc_counts:
                    row.append(str(loc_counts[loc]))
                else:
                    row.append("-")

            if r.has_error:
                issues = []
                for t in r.target_reports:
                    if t.missing_keys:
                        issues.append(f"{t.locale} 缺 {len(t.missing_keys)}")
                    if t.extra_keys:
                        issues.append(f"{t.locale} 多 {len(t.extra_keys)}")
                status_str = f"不一致 ({', '.join(issues)})"
            else:
                status_str = "对齐"
            row.append(status_str)
        rows.append(row)

    col_widths = [max(len(row[i]) for row in ([headers] + rows)) for i in range(len(headers))]
    line_format = "  ".join(f"{{:<{w}}}" for w in col_widths)

    print(line_format.format(*headers))
    print("  ".join("-" * w for w in col_widths))
    for row in rows:
        print(line_format.format(*row))
    return 0


def print_reports(reports: list[ModuleReport], warnings: list[str]) -> int:
    """打印检查结果与警告。"""
    for warn_file in warnings:
        print(f"githooks: [提示] 检测到新增语言文件: {warn_file}")
        print(
            "    → 请确认项目实际支持的语种清单（当前主要支持: "
            + ", ".join(KNOWN_LOCALES)
            + "），检查其他模块是否也需要同步补齐，避免遗漏。"
        )
        print()

    has_error = False
    for r in reports:
        if not r.has_error:
            continue
        has_error = True
        print(f"githooks: 多语言键值检查未通过（模块: {r.res_dir}）")
        if r.base_error:
            print(f"  [阻断] 基准语言异常: {r.base_error}")
            print()
            continue

        print(f"  基准版本: {r.base_file} (共 {r.base_total} 个有效键)")
        for t in r.target_reports:
            if not t.has_error:
                continue
            if t.xml_error:
                print(f"  [阻断] {t.path}: {t.xml_error}")
                continue

            diff_parts = []
            if t.missing_keys:
                diff_parts.append(f"缺少 {len(t.missing_keys)} 个")
            if t.extra_keys:
                diff_parts.append(f"多余 {len(t.extra_keys)} 个")
            if t.duplicates:
                diff_parts.append(f"重复定义 {len(t.duplicates)} 个")

            print(f"  [阻断] {t.path}: {t.total_count} 个键 ({'，'.join(diff_parts)})")
            if t.missing_keys:
                print("      → 缺失以下键:")
                for k in t.missing_keys[:10]:
                    print(f"          - {k}")
                if len(t.missing_keys) > 10:
                    print(f"          ... 其余 {len(t.missing_keys) - 10} 个省略")
            if t.extra_keys:
                print("      → 多余以下基准中未声明的键:")
                for k in t.extra_keys[:10]:
                    print(f"          - {k}")
                if len(t.extra_keys) > 10:
                    print(f"          ... 其余 {len(t.extra_keys) - 10} 个省略")
            if t.duplicates:
                print(f"      → 重复定义的键: {', '.join(t.duplicates)}")
        print()

    if has_error:
        print("githooks: 提交被拦截。请补齐相应语言版本的键值，或在基准中配置 translatable=\"false\"。")
        return 1
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="check_strings.py",
        description="多语言资源对齐检查：默认检查暂存区受影响的模块。",
    )
    parser.add_argument(
        "--all", action="store_true",
        help="检查整个工作区所有模块（全量），可后跟路径参数限定范围。",
    )
    parser.add_argument(
        "--staged", action="store_true",
        help="检查暂存区中发生变更的模块（默认行为）。",
    )
    parser.add_argument(
        "--worktree", action="store_true",
        help="检查工作区中发生变更的模块（含未暂存与已暂存）。",
    )
    parser.add_argument(
        "--stat", action="store_true",
        help="输出各模块多语言键值统计大盘。",
    )
    parser.add_argument("scope", nargs="*", help="--all 时的路径前缀限定。")
    args = parser.parse_args(argv)

    root_proc = git(str(Path.cwd()), "rev-parse", "--show-toplevel")
    root = root_proc.stdout.decode("utf-8", errors="replace").strip()
    if not root:
        print("githooks: 不在 git 仓库内", file=sys.stderr)
        return 0

    warnings: list[str] = []
    staged_contents_map: dict[str, str] = {}

    if args.all:
        res_dirs = find_all_res_dirs(root, args.scope)
        def content_provider(rel_path: str) -> str | None:
            p = Path(root, rel_path)
            if not p.is_file():
                return None
            try:
                return p.read_text(encoding="utf-8", errors="replace")
            except OSError:
                return None
    elif args.worktree:
        res_dirs, warnings = get_worktree_changes(root)
        def content_provider(rel_path: str) -> str | None:
            p = Path(root, rel_path)
            if not p.is_file():
                return None
            try:
                return p.read_text(encoding="utf-8", errors="replace")
            except OSError:
                return None
    else:
        # Default: --staged
        res_dirs, warnings, staged_contents_map = get_staged_changes(root)
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

    if not res_dirs:
        # 没有涉及任何 strings 变更
        if warnings:
            return print_reports([], warnings)
        return 0

    reports: list[ModuleReport] = []
    for res_dir in sorted(res_dirs):
        extra = set(staged_contents_map.keys()) if staged_contents_map else None
        rep = check_single_res_dir(root, res_dir, content_provider, extra)
        if rep:
            reports.append(rep)

    if args.stat:
        return print_stat_table(reports)

    return print_reports(reports, warnings)


if __name__ == "__main__":
    sys.exit(main())
