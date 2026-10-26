"""多语言资源 (strings.xml) 键值对齐与 UI 文案话痨标点检查。

只依赖 Python 标准库。
- 国际化规范 (strings-alignment)：以 values/strings.xml 为基准，所有支持语种严格对齐键集合，防止漏翻与多余键；
- UI 文案规范 (verbose-ui)：检测中文 UI 文案中的分号与解释性括号，杜绝话痨风格。
"""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from core.ignore import extract_file_ignores, is_rule_ignored, split_rules
from core.models import Category, CheckResult, Finding, Rule, Severity

STRINGS_PATTERN = re.compile(r"^(.*?/res)/values([^/]*)/strings\.xml$")

KNOWN_LOCALES = (
    "values",
    "values-en",
    "values-b+zh+Hant",
    "values-es",
    "values-ja",
    "values-ru",
    "values-ar",
)

RE_ALLOWED_PARENS = re.compile(
    r"\(\s*%\d*\$?[a-zA-Z]\s*\)"          # e.g. (%1$s), (%s), (%d)
    r"|（\s*%\d*\$?[a-zA-Z]\s*）"        # fullwidth: （%1$s）
    r"|\b[a-zA-Z_][a-zA-Z0-9_]*\([^)]*\)"  # code notation: main(), main(query: str)
)

ALIGNMENT_RULE = Rule(
    name="strings-alignment",
    category=Category.I18N,
    severity=Severity.BLOCK,
    description="多语言资源键值集合与基准版本未对齐 (存在缺失、多余或重复键)",
    hint="请在对应语言文件中补齐缺失键，或在基准文件中标记 translatable=\"false\"",
)

VERBOSE_UI_RULE = Rule(
    name="verbose-ui",
    category=Category.UI,
    severity=Severity.WARN,
    description="中文 UI 文案包含分号或解释性括号，文案应保持专业简洁、拒绝话痨",
    hint="建议去掉多余解释括号；分号精简为短句或改用换行；确实需要时加 <!-- githooks:ignore verbose-ui --> 放行",
)


@dataclass(frozen=True)
class VerboseUiWarning:
    path: str
    key: str
    text: str
    reasons: tuple[str, ...]


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
    verbose_warnings: tuple[VerboseUiWarning, ...] = ()

    @property
    def has_error(self) -> bool:
        return bool(self.base_error or any(t.has_error for t in self.target_reports))


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

    return (
        ParsedStrings(
            all_keys=frozenset(raw_names),
            translatable_keys=frozenset(translatable_keys),
            untranslatable_keys=frozenset(untranslatable_keys),
            duplicates=duplicates,
            total_count=len(raw_names),
        ),
        None,
    )


def is_key_ignored(raw_content: str, key: str) -> bool:
    """检查条目是否带有行级/条目级豁免注释。"""
    pattern = (
        r"<!--[^\n]*?githooks:ignore[^\n]*?(?:all|verbose-ui)[^\n]*?-->"
        r"\s*<(?:string|plurals|string-array)[^>]*name=[\"']"
        + re.escape(key)
        + r"[\"']"
    )
    return re.search(pattern, raw_content, re.DOTALL) is not None


def check_verbose_ui(content: str, path: str) -> list[VerboseUiWarning]:
    """检查中文 strings.xml 是否存在话痨违规（分号或解释性括号）。"""
    m = STRINGS_PATTERN.match(path)
    if not m:
        return []
    suffix = m.group(2)
    # 仅检测中文语言包（默认 values/strings.xml 与 values-b+zh*）
    if suffix != "" and not ("zh" in suffix):
        return []

    # 文件级豁免：<!-- githooks:ignore-file verbose-ui --> 或 all
    if re.search(r"<!--.*?githooks:ignore-file\s+(?:all|verbose-ui).*?-->", content):
        return []

    try:
        root = ET.fromstring(content)
    except ET.ParseError:
        return []

    warnings: list[VerboseUiWarning] = []
    for child in root:
        if child.tag in ("string", "plurals", "string-array"):
            key = child.attrib.get("name", "")
            if not key or is_key_ignored(content, key):
                continue

            texts: list[str] = []
            if child.tag == "string":
                if child.text:
                    texts.append(child.text)
            else:
                for item in child.findall("item"):
                    if item.text:
                        texts.append(item.text)

            for text in texts:
                reasons: list[str] = []
                if "；" in text or ";" in text:
                    reasons.append("分号")

                stripped = RE_ALLOWED_PARENS.sub("", text)
                if any(p in stripped for p in ("（", "）", "(", ")")):
                    reasons.append("括号")

                if reasons:
                    warnings.append(
                        VerboseUiWarning(
                            path=path,
                            key=key,
                            text=text.strip().replace("\n", " "),
                            reasons=tuple(reasons),
                        )
                    )
                    break
    return warnings


def discover_locale_files(root: str, res_dir: str, extra_paths: set[str] | None = None) -> dict[str, str]:
    """发现给定 res 目录下所有语言版本的 strings.xml。返回 {locale_suffix: relative_path}。"""
    res_path = Path(root, res_dir)
    results: dict[str, str] = {}
    all_candidate_paths: set[str] = set()

    if res_path.is_dir():
        for p in res_path.glob("values*/strings.xml"):
            try:
                all_candidate_paths.add(p.relative_to(Path(root)).as_posix())
            except ValueError:
                pass

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


def check_res_dir(
    root: str,
    res_dir: str,
    content_provider: Callable[[str], str | None],
    extra_paths: set[str] | None = None,
) -> ModuleReport | None:
    """检查单个 res 目录下的各语言 strings.xml 对齐情况与 UI 文案。"""
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
    if base_err or not base_parsed:
        return ModuleReport(
            res_dir=res_dir,
            base_file=base_path,
            base_total=0,
            target_reports=[],
            base_error=f"基准语言 XML 解析失败: {base_err}",
        )

    verbose_warnings: list[VerboseUiWarning] = []
    verbose_warnings.extend(check_verbose_ui(base_content, base_path))
    target_reports: list[TargetLanguageReport] = []

    for suffix, target_path in sorted(locale_map.items()):
        if suffix == "":
            continue
        locale_name = f"values{suffix}"
        content = content_provider(target_path)
        if content is None:
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

        target_parsed, target_err = parse_strings_xml(content)
        if target_err or not target_parsed:
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

        verbose_warnings.extend(check_verbose_ui(content, target_path))

        missing = tuple(sorted(base_parsed.translatable_keys - target_parsed.all_keys))
        extra = tuple(sorted(target_parsed.all_keys - base_parsed.all_keys))
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
        verbose_warnings=tuple(verbose_warnings),
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


def reports_to_check_result(
    reports: list[ModuleReport],
    new_language_files: list[str],
    active_rules: set[str] | None = None,
) -> CheckResult:
    """将 ModuleReport 转换为统一的 CheckResult 结构。"""
    findings: list[Finding] = []
    notices: list[str] = []

    if new_language_files:
        notices.append(
            f"检测到新增语言文件: {', '.join(new_language_files)}。"
            f"请确认项目支持的语种范围 ({', '.join(KNOWN_LOCALES)})，检查其他模块是否需同步补齐。"
        )

    # 1. verbose-ui 规则 (UI 分类)
    if active_rules is None or VERBOSE_UI_RULE.name in active_rules:
        for r in reports:
            for w in r.verbose_warnings:
                reasons_str = "与".join(w.reasons)
                findings.append(
                    Finding(
                        path=w.path,
                        lineno=None,
                        rule=VERBOSE_UI_RULE.name,
                        category=VERBOSE_UI_RULE.category.value,
                        severity=VERBOSE_UI_RULE.severity.value,
                        message=f"条目 [{w.key}] 包含{reasons_str}: \"{w.text}\"",
                        hint=VERBOSE_UI_RULE.hint,
                    )
                )

    # 2. strings-alignment 规则 (I18N 分类)
    if active_rules is None or ALIGNMENT_RULE.name in active_rules:
        for r in reports:
            if r.base_error:
                findings.append(
                    Finding(
                        path=r.base_file or r.res_dir,
                        lineno=None,
                        rule=ALIGNMENT_RULE.name,
                        category=ALIGNMENT_RULE.category.value,
                        severity=ALIGNMENT_RULE.severity.value,
                        message=r.base_error,
                        hint=ALIGNMENT_RULE.hint,
                    )
                )
            for t in r.target_reports:
                if not t.has_error:
                    continue
                diff_parts = []
                if t.missing_keys:
                    diff_parts.append(f"缺少 {len(t.missing_keys)} 个")
                if t.extra_keys:
                    diff_parts.append(f"多余 {len(t.extra_keys)} 个")
                if t.duplicates:
                    diff_parts.append(f"重复定义 {len(t.duplicates)} 个")

                detail_msg = f"{t.locale} ({', '.join(diff_parts)})"
                if t.xml_error:
                    detail_msg = f"{t.locale}: {t.xml_error}"
                elif t.missing_keys:
                    top_missing = ", ".join(t.missing_keys[:5])
                    if len(t.missing_keys) > 5:
                        top_missing += f" 等 {len(t.missing_keys)} 个"
                    detail_msg += f" ➔ 缺失键: [{top_missing}]"

                findings.append(
                    Finding(
                        path=t.path,
                        lineno=None,
                        rule=ALIGNMENT_RULE.name,
                        category=ALIGNMENT_RULE.category.value,
                        severity=ALIGNMENT_RULE.severity.value,
                        message=detail_msg,
                        hint=ALIGNMENT_RULE.hint,
                    )
                )

    return CheckResult(
        findings=findings,
        ignored=0,
        scanned_count=len(reports),
        notices=notices,
    )
