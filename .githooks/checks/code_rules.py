"""Kotlin 源码规范与坏味道检查（并发安全、协程生命周期、异常、日志、UI圆角、注释时态与导包）。

只依赖 Python 标准库。
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

from core.ignore import extract_file_ignores, extract_line_ignores, is_rule_ignored
from core.models import Category, CheckResult, Finding, Rule, Severity

EXCLUDE_PREFIXES = ("libs/",)
TARGET_SUFFIX = ".kt"

LOG_TAG_PREFIX = "niki914_zafiro_"
TAGISH_NAMES = ("tag", "log_tag", "logtag", "logging_tag")

NARRATIVE = re.compile(
    r"不再|改为|改成|此前|原来|原本|原先|原为|曾经|旧版|解决|修复"
    r"|\bno longer\b|\bused to\b|\bformerly\b|\bpreviously\b|\bsolve\b|\bfixed\b"
    r"|\brenamed from\b|\bwas renamed\b",
    re.IGNORECASE,
)

IMPORT = re.compile(r"^\s*import\s+([\w.]+)")
MONITOR_LOCK = re.compile(r"\bsynchronized\s*\(|@Synchronized\b")
FQN = re.compile(
    r"(?<![\w.])(?:com|org|android|androidx|java|kotlin|javax)\.(?:[a-z]\w*\.)+([A-Z]\w*)"
)
STRING_LITERAL = re.compile(r'""".*?"""|"(?:[^"\\]|\\.)*"|\'(?:[^\'\\]|\\.)*\'')
ANDROID_LOG_IMPORT = re.compile(r"^\s*import\s+android\.util\.Log\b")
ANDROID_LOG_CALL = re.compile(r"(?<![\w.])Log\.[dviwe]\s*\(")
PRINT_CALL = re.compile(r"(?<![\w.])(?:println|print)\s*\(|System\.out\b")
TAG_DECL = re.compile(r'(?i)(?:val|var)\s+(\w+)\s*(?::\s*[\w<>?.]+\s*)?=\s*"([^"]*)"')
LOGGER_LITERAL_TAG = re.compile(r'Logger\.[dviwe]\s*\(\s*"([^"]*)"')
UNCANCELLABLE_RUN_CATCHING = re.compile(
    r"\b(?:runCatching|mapCatching)\s*(?:[\(\{]|::)|^\s*import\s+[\w.]*\b(?:runCatching|mapCatching)\b"
)
BARE_CATCH = re.compile(
    r"\bcatch\s*\(\s*(?:\w+|_)\s*:\s*(?:[\w.]+\.)?(?:Throwable|Exception)\s*\)"
)
ROUNDED_CORNER_SHAPE = re.compile(
    r"\bRoundedCornerShape\s*\(|^\s*import\s+[\w.]*\bRoundedCornerShape\b"
)
CONTENT_DESC_ASSIGN_I18N = re.compile(
    r"\bcontentDescription\s*(?::\s*[\w<>?.]+\s*)?=\s*(?:stringResource|stringResourceOrNull|getString\s*\(\s*R\.string)\b"
)
CONTENT_DESC_TRAILING_ASSIGN = re.compile(
    r"\bcontentDescription\s*(?::\s*[\w<>?.]+\s*)?=\s*$"
)
I18N_RESOURCE_CALL = re.compile(
    r"\b(?:stringResource|stringResourceOrNull|getString\s*\(\s*R\.string)\b"
)
CONTENT_DESC_LITERAL = re.compile(
    r'\bcontentDescription\s*(?::\s*[\w<>?.]+\s*)?=\s*"([^"]*)"'
)


@dataclass(frozen=True)
class LineContext:
    imported: frozenset[str]
    android_log: bool
    lines: tuple[str, ...] = ()
    lineno: int = 0


def is_comment(line: str) -> bool:
    return line.strip().startswith(("//", "*", "/*"))


def strip_strings(line: str) -> str:
    return STRING_LITERAL.sub('""', line)


def raw_lines(lines: list[str]) -> set[int]:
    """三引号字符串（多为内联的 Python / Markdown）内部的行号。"""
    inside = False
    raw: set[int] = set()
    for number, line in enumerate(lines, 1):
        quotes = line.count('"""')
        if inside or quotes % 2 == 1:
            raw.add(number)
        if quotes % 2 == 1:
            inside = not inside
    return raw


def hit_monitor_lock(line: str, ctx: LineContext) -> bool:
    return not is_comment(line) and MONITOR_LOCK.search(line) is not None


def hit_legacy_comment(line: str, ctx: LineContext) -> bool:
    return is_comment(line) and NARRATIVE.search(line) is not None


def hit_inline_fqn(line: str, ctx: LineContext) -> bool:
    if is_comment(line) or line.strip().startswith(("import ", "package ")):
        return False
    # 同名类已 import 时，内联全限定名是刻意消歧义，放行。
    return any(
        m.group(1) not in ctx.imported for m in FQN.finditer(strip_strings(line))
    )


def hit_log_api(line: str, ctx: LineContext) -> bool:
    if is_comment(line):
        return False
    if ctx.android_log and (ANDROID_LOG_IMPORT.match(line) or ANDROID_LOG_CALL.search(line)):
        return True
    return PRINT_CALL.search(strip_strings(line)) is not None


def hit_log_tag(line: str, ctx: LineContext) -> bool:
    if is_comment(line):
        return False
    decl = TAG_DECL.search(line)
    if decl and decl.group(1).lower() in TAGISH_NAMES:
        return not decl.group(2).startswith(LOG_TAG_PREFIX)
    literal = LOGGER_LITERAL_TAG.search(line)
    if literal:
        return not literal.group(1).startswith(LOG_TAG_PREFIX)
    return False


def hit_uncancellable_run_catching(line: str, ctx: LineContext) -> bool:
    if is_comment(line):
        return False
    return UNCANCELLABLE_RUN_CATCHING.search(strip_strings(line)) is not None


def hit_bare_catch(line: str, ctx: LineContext) -> bool:
    if is_comment(line):
        return False
    return BARE_CATCH.search(strip_strings(line)) is not None


def hit_rounded_corner_shape(line: str, ctx: LineContext) -> bool:
    if is_comment(line):
        return False
    return ROUNDED_CORNER_SHAPE.search(strip_strings(line)) is not None


def hit_content_description_i18n(line: str, ctx: LineContext) -> bool:
    if is_comment(line):
        return False
    literal_match = CONTENT_DESC_LITERAL.search(line)
    if literal_match and any(ord(c) > 127 for c in literal_match.group(1)):
        return True

    clean = strip_strings(line)
    if CONTENT_DESC_ASSIGN_I18N.search(clean):
        return True

    if CONTENT_DESC_TRAILING_ASSIGN.search(clean):
        for next_line in ctx.lines[ctx.lineno : ctx.lineno + 3]:
            stripped = strip_strings(next_line).strip()
            if not stripped or is_comment(next_line):
                continue
            if I18N_RESOURCE_CALL.search(stripped):
                return True
            break
    elif I18N_RESOURCE_CALL.search(clean):
        for prev_line in reversed(ctx.lines[max(0, ctx.lineno - 4) : ctx.lineno - 1]):
            stripped = strip_strings(prev_line).strip()
            if not stripped or is_comment(prev_line):
                continue
            if CONTENT_DESC_TRAILING_ASSIGN.search(stripped):
                return True
            break
    return False


CODE_RULES: list[tuple[Rule, callable]] = [
    # 1. Category.SAFETY
    (
        Rule(
            name="monitor-lock",
            category=Category.SAFETY,
            severity=Severity.BLOCK,
            description="使用了监视器锁 synchronized",
            hint="改用 Mutex / 协程；确实需要时在本行或上一行加 // githooks:ignore monitor-lock",
        ),
        hit_monitor_lock,
    ),
    (
        Rule(
            name="uncancellable-run-catching",
            category=Category.SAFETY,
            severity=Severity.BLOCK,
            description="用了未处理协程取消的标准库 runCatching / mapCatching",
            hint="改用 runCatchingCancellable / mapCatchingCancellable；确实需要生吞取消时加 // githooks:ignore uncancellable-run-catching",
        ),
        hit_uncancellable_run_catching,
    ),
    (
        Rule(
            name="bare-catch",
            category=Category.SAFETY,
            severity=Severity.WARN,
            description="捕获了泛化异常 (Throwable/Exception)",
            hint="建议改用 runCatchingCancellable / tryOrNull，或捕获具体异常类型；确实需要时加 // githooks:ignore bare-catch",
        ),
        hit_bare_catch,
    ),
    (
        Rule(
            name="log-api",
            category=Category.SAFETY,
            severity=Severity.BLOCK,
            description="用了 android.util.Log / println，绕过业务 Logger",
            hint="改用 com.niki914.logging.Logger；或加 // githooks:ignore log-api",
        ),
        hit_log_api,
    ),
    (
        Rule(
            name="log-tag",
            category=Category.SAFETY,
            severity=Severity.WARN,
            description=f"日志 TAG 不以 {LOG_TAG_PREFIX} 开头",
            hint=f"改成 {LOG_TAG_PREFIX}<ClassName>；或加 // githooks:ignore log-tag",
        ),
        hit_log_tag,
    ),
    # 2. Category.UI
    (
        Rule(
            name="rounded-corner-shape",
            category=Category.UI,
            severity=Severity.BLOCK,
            description="使用了 RoundedCornerShape，项目要求统一使用 G2 连续圆角",
            hint="改用 G2FieldShape / G2CardShape / G2BubbleShape；确实需要普通圆角时加 // githooks:ignore rounded-corner-shape",
        ),
        hit_rounded_corner_shape,
    ),
    # 3. Category.HYGIENE
    (
        Rule(
            name="legacy-comment",
            category=Category.HYGIENE,
            severity=Severity.WARN,
            description="注释在描述已经被替换掉的旧状态",
            hint="只描述当前行为；或在本行或上一行加 // githooks:ignore legacy-comment",
        ),
        hit_legacy_comment,
    ),
    (
        Rule(
            name="inline-fqn",
            category=Category.HYGIENE,
            severity=Severity.WARN,
            description="内联写全限定名而不 import",
            hint="补 import；若为消歧义则加 // githooks:ignore inline-fqn",
        ),
        hit_inline_fqn,
    ),
    # 4. Category.I18N
    (
        Rule(
            name="content-description-i18n",
            category=Category.I18N,
            severity=Severity.BLOCK,
            description="contentDescription 使用了多语言资源或非英文文案，预期统一使用纯英文硬编码或 null",
            hint="改用纯英文字符串（如 \"Close\"、\"Clear\"）或 null；确实需要多语言时加 // githooks:ignore content-description-i18n",
        ),
        hit_content_description_i18n,
    ),
]

CODE_RULE_DICT: dict[str, Rule] = {r.name: r for r, _ in CODE_RULES}


def file_context(lines: list[str]) -> LineContext:
    imported = frozenset(
        m.group(1).rsplit(".", 1)[-1] for m in map(IMPORT.match, lines) if m
    )
    return LineContext(
        imported=imported,
        android_log=any(ANDROID_LOG_IMPORT.match(l) for l in lines),
        lines=tuple(lines),
    )


def check_code_files(
    files: dict[str, str],
    candidates: dict[str, list[int]],
    active_rules: set[str] | None = None,
) -> CheckResult:
    """对候选文件的候选行执行代码规则检查。"""
    findings: list[Finding] = []
    ignored_count = 0

    target_rules = [
        (r, fn)
        for r, fn in CODE_RULES
        if active_rules is None or r.name in active_rules
    ]

    for path, linenos in candidates.items():
        source = files.get(path)
        if source is None:
            continue
        lines = source.splitlines()
        ctx = file_context(lines)
        raw = raw_lines(lines)
        file_ignores = extract_file_ignores(lines)

        for lineno in linenos:
            if lineno < 1 or lineno > len(lines) or lineno in raw:
                continue
            line_text = lines[lineno - 1]
            line_ignores = extract_line_ignores(lines, lineno)
            allowed = file_ignores | line_ignores
            line_ctx = LineContext(ctx.imported, ctx.android_log, ctx.lines, lineno)

            for rule, hit_fn in target_rules:
                if not hit_fn(line_text, line_ctx):
                    continue
                if is_rule_ignored(rule.name, allowed):
                    ignored_count += 1
                    continue

                findings.append(
                    Finding(
                        path=path,
                        lineno=lineno,
                        rule=rule.name,
                        category=rule.category.value,
                        severity=rule.severity.value,
                        message=rule.description,
                        hint=rule.hint,
                    )
                )

    return CheckResult(
        findings=findings,
        ignored=ignored_count,
        scanned_count=len(files),
    )
