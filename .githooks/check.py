#!/usr/bin/env python3
"""提交前坏味道检查。

只依赖 Python 标准库。默认检查暂存区新增行（增量），--all 检查整个工作区（全量）。

策略全部是文件顶部的常量，改策略不用改逻辑，见 .githooks/README.md。
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

# --------------------------------------------------------------------------
# 策略：改这里就能改行为
# --------------------------------------------------------------------------

# 规则严重级别："block" 拦截提交，"warn" 只提示。
SEVERITY = {
    "monitor-lock": "block",
    "legacy-comment": "warn",
    "inline-fqn": "warn",
    "log-api": "block",
    "log-tag": "warn",
}

# 不做检查的路径前缀（增量与全量共用）。
EXCLUDE_PREFIXES = (
    "libs/",
)

# 变化叙述注释的关键词。描述了"曾经/不再是什么"的注释一律不合格：
# 注释只描述代码当前是什么样。
NARRATIVE_PATTERNS = (
    # 中文
    r"不再",
    r"改为",
    r"改成",
    r"此前",
    r"原来",
    r"原本",
    r"原先",
    r"原为",
    r"曾经",
    r"旧版",
    # 英文
    r"no longer",
    r"used to\b",
    r"formerly",
    r"previously",
    r"renamed from",
    r"was renamed",
)

TARGET_SUFFIX = ".kt"

# 日志 TAG 必须以此开头（现存 59 个 tag 常量全部合规）。
LOG_TAG_PREFIX = "niki914_zafiro_"

# 会被当成日志 TAG 的常量名（小写后比较）。TAG_PREFIX 之类不在内。
TAGISH_NAMES = ("tag", "log_tag", "logtag", "logging_tag")

# --------------------------------------------------------------------------
# 规则实现
# --------------------------------------------------------------------------

IGNORE_DIRECTIVE = re.compile(r"githooks:ignore(-file)?\s+([\w,\-]+)")
IMPORT = re.compile(r"^\s*import\s+([\w.]+)")
MONITOR_LOCK = re.compile(r"\bsynchronized\s*\(|@Synchronized\b")
NARRATIVE = re.compile("|".join(NARRATIVE_PATTERNS), re.IGNORECASE)
FQN = re.compile(
    r"(?<![\w.])(?:com|org|android|androidx|java|kotlin|javax)\.(?:[a-z]\w*\.)+([A-Z]\w*)"
)
STRING_LITERAL = re.compile(
    r'""".*?"""|"(?:[^"\\]|\\.)*"|\'(?:[^\'\\]|\\.)*\''
)
ANDROID_LOG_IMPORT = re.compile(r"^\s*import\s+android\.util\.Log\b")
ANDROID_LOG_CALL = re.compile(r"(?<![\w.])Log\.[dviwe]\s*\(")
PRINT_CALL = re.compile(r"(?<![\w.])(?:println|print)\s*\(|System\.out\b")
TAG_DECL = re.compile(r'(?i)(?:val|var)\s+(\w+)\s*(?::\s*[\w<>?.]+\s*)?=\s*"([^"]*)"')
LOGGER_LITERAL_TAG = re.compile(r'Logger\.[dviwe]\s*\(\s*"([^"]*)"')


@dataclass(frozen=True)
class LineContext:
    imported: frozenset[str]
    android_log: bool


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


RULES = (
    (
        "monitor-lock",
        hit_monitor_lock,
        "使用了监视器锁 synchronized",
        "改用 Mutex / 协程；确实需要时在本行或上一行加 // githooks:ignore monitor-lock",
    ),
    (
        "legacy-comment",
        hit_legacy_comment,
        "注释在描述已经被替换掉的旧状态",
        "只描述当前行为；或在本行或上一行加 // githooks:ignore legacy-comment",
    ),
    (
        "inline-fqn",
        hit_inline_fqn,
        "内联写全限定名而不 import",
        "补 import；若为消歧义则加 // githooks:ignore inline-fqn",
    ),
    (
        "log-api",
        hit_log_api,
        "用了 android.util.Log / println，绕过业务 Logger",
        "改用 com.niki914.logging.Logger；或加 // githooks:ignore log-api",
    ),
    (
        "log-tag",
        hit_log_tag,
        f"日志 TAG 不以 {LOG_TAG_PREFIX} 开头",
        f"改成 {LOG_TAG_PREFIX}<ClassName>；或加 // githooks:ignore log-tag",
    ),
)

RULE_NAMES = tuple(name for name, *_ in RULES)


# --------------------------------------------------------------------------
# 检查
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class Finding:
    path: str
    lineno: int
    rule: str
    message: str
    hint: str


def excluded(path: str) -> bool:
    return path.startswith(EXCLUDE_PREFIXES)


def split_rules(text: str) -> set[str]:
    return {t for t in re.split(r"[,\s]+", text) if t}


def collect_findings(
    files: dict[str, str],
    candidates: dict[str, list[int]],
    rules: tuple[str, ...] = RULE_NAMES,
) -> tuple[list[Finding], int]:
    """files: 路径 -> 完整文件内容；candidates: 路径 -> 待检行号（1 起）。"""
    findings: list[Finding] = []
    ignored = 0
    for path, linenos in candidates.items():
        source = files.get(path)
        if source is None:
            continue
        lines = source.splitlines()
        imported = frozenset(
            m.group(1).rsplit(".", 1)[-1] for m in map(IMPORT.match, lines) if m
        )
        ctx = LineContext(imported, any(ANDROID_LOG_IMPORT.match(l) for l in lines))
        raw = raw_lines(lines)
        file_scope: set[str] = set()
        for line in lines:
            m = IGNORE_DIRECTIVE.search(line)
            if m and m.group(1):
                file_scope |= split_rules(m.group(2))

        for lineno in linenos:
            if lineno < 1 or lineno > len(lines) or lineno in raw:
                continue
            text = lines[lineno - 1]
            line_scope: set[str] = set()
            for probe in (text, lines[lineno - 2] if lineno >= 2 else ""):
                m = IGNORE_DIRECTIVE.search(probe)
                if m and not m.group(1):
                    line_scope |= split_rules(m.group(2))
            allowed = file_scope | line_scope
            for name, hit, message, hint in RULES:
                if name not in rules or not hit(text, ctx):
                    continue
                if name in allowed or "all" in allowed:
                    ignored += 1
                    continue
                findings.append(Finding(path, lineno, name, message, hint))
    return findings, ignored


# --------------------------------------------------------------------------
# 待检行来源
# --------------------------------------------------------------------------


def parse_diff(diff_text: str) -> dict[str, list[int]]:
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
            if path and path.endswith(TARGET_SUFFIX) and not excluded(path):
                candidates[path].append(lineno)
            lineno += 1
        elif line.startswith(" "):
            lineno += 1
    return dict(candidates)


def git(root: str, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", root, *args], text=True, capture_output=True, check=False
    )


def staged_candidates(root: str) -> dict[str, list[int]]:
    result = git(
        root, "-c", "core.quotePath=false", "diff", "--cached", "-U0",
        "--diff-filter=ACMR", "--", f"*{TARGET_SUFFIX}",
    )
    return parse_diff(result.stdout)


def staged_contents(root: str, paths: list[str]) -> dict[str, str]:
    files: dict[str, str] = {}
    for path in paths:
        result = git(root, "show", f":{path}")
        if result.returncode == 0:
            files[path] = result.stdout
    return files


def all_candidates(root: str, scope: list[str]) -> tuple[dict[str, str], dict[str, list[int]]]:
    result = git(root, "ls-files", "-z", f"*{TARGET_SUFFIX}")
    files: dict[str, str] = {}
    candidates: dict[str, list[int]] = {}
    for path in result.stdout.split("\0"):
        if excluded(path) or (scope and not path.startswith(tuple(scope))):
            continue
        try:
            source = Path(root, path).read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        files[path] = source
        candidates[path] = list(range(1, len(source.splitlines()) + 1))
    return files, candidates


# --------------------------------------------------------------------------
# 输出
# --------------------------------------------------------------------------


def print_findings(findings: list[Finding], ignored: int, scope_note: str) -> int:
    blocking = sum(1 for f in findings if SEVERITY[f.rule] == "block")
    warnings = len(findings) - blocking

    grouped: dict[str, list[Finding]] = defaultdict(list)
    for f in findings:
        grouped[f.path].append(f)

    for path in sorted(grouped):
        for f in sorted(grouped[path], key=lambda x: x.lineno):
            print(f"{f.path}:{f.lineno}: {f.rule}: {f.message}")
            print(f"    → {f.hint}")

    if findings or ignored:
        parts = []
        if blocking:
            parts.append(f"{blocking} 阻断")
        if warnings:
            parts.append(f"{warnings} 警告")
        parts.append(f"{ignored} 已豁免")
        print()
        print(f"githooks: {'，'.join(parts)}{scope_note}")

    if blocking:
        print("githooks: 提交被拦截。修正后重新 git add；确有必要时按 .githooks/README.md 放行。")
    return 1 if blocking else 0


def print_stat(findings: list[Finding], files_scanned: int) -> int:
    per_module: dict[str, Counter] = defaultdict(Counter)
    for f in findings:
        per_module[f.path.split("/")[0]][f.rule] += 1

    width = max((len(m) for m in per_module), default=6)
    print(f"{'module':<{width}}  {'lock':>5} {'comment':>7} {'fqn':>5} {'log':>5} {'tag':>5} {'total':>6}")
    for module in sorted(per_module, key=lambda m: -sum(per_module[m].values())):
        c = per_module[module]
        print(
            f"{module:<{width}}  {c['monitor-lock']:>5} {c['legacy-comment']:>7}"
            f" {c['inline-fqn']:>5} {c['log-api']:>5} {c['log-tag']:>5} {sum(c.values()):>6}"
        )
    total = sum(sum(c.values()) for c in per_module.values())
    print()
    print(f"githooks: {files_scanned} 个文件，{total} 处违规")
    return 0


def print_rules() -> int:
    for name, _, message, hint in RULES:
        print(f"{name} ({SEVERITY[name]}): {message}")
        print(f"    → {hint}")
    return 0


# --------------------------------------------------------------------------
# 入口
# --------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="check.py",
        description="坏味道检查：默认只查暂存区新增行（增量）。",
    )
    parser.add_argument(
        "--all", action="store_true",
        help="检查整个工作区（全量），可跟路径参数限定模块。",
    )
    parser.add_argument(
        "--staged", action="store_true", help="检查暂存区新增行（默认行为）。"
    )
    parser.add_argument(
        "--rule", action="append", choices=RULE_NAMES, dest="rules",
        help="只检查指定规则，可重复。",
    )
    parser.add_argument("--stat", action="store_true", help="只输出按模块统计。")
    parser.add_argument("--list-rules", action="store_true", help="列出规则。")
    parser.add_argument("scope", nargs="*", help="--all 时的路径前缀限定。")
    args = parser.parse_args(argv)

    if args.list_rules:
        return print_rules()

    rules = tuple(args.rules or RULE_NAMES)
    root = git(str(Path.cwd()), "rev-parse", "--show-toplevel").stdout.strip()
    if not root:
        print("githooks: 不在 git 仓库内", file=sys.stderr)
        return 0

    if args.all:
        files, candidates = all_candidates(root, args.scope)
        scanned = len(files)
        note = "" if not EXCLUDE_PREFIXES else "（已排除 " + ", ".join(EXCLUDE_PREFIXES) + "）"
    else:
        candidates = staged_candidates(root)
        files = staged_contents(root, list(candidates))
        scanned = len(files)
        note = ""

    findings, ignored = collect_findings(files, candidates, rules)
    if args.stat:
        return print_stat(findings, scanned)
    return print_findings(findings, ignored, note)


if __name__ == "__main__":
    sys.exit(main())
