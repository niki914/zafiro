"""统一的豁免指令（Ignore Directive）解析模块。

只依赖 Python 标准库。
支持 Kotlin 注释与 XML 注释中的行级与文件级豁免：
- Kotlin 行级:  // githooks:ignore <rule1>,<rule2> [理由]
- Kotlin 文件级: // githooks:ignore-file <rule1>,<rule2> [理由]
- XML 行级:     <!-- githooks:ignore <rule1>,<rule2> [理由] -->
- XML 文件级:    <!-- githooks:ignore-file <rule1>,<rule2> [理由] -->
"""

from __future__ import annotations

import re

# 匹配 Kotlin (//) 或 XML (<!--) 中的豁免指令
# group(1): 可选的 "-file" 后缀，表示文件级豁免
# group(2): 逗号或空白分隔的规则名列表
IGNORE_DIRECTIVE = re.compile(
    r"(?://|<!--)\s*githooks:ignore(-file)?\s+([\w,\-]+)"
)


def split_rules(text: str) -> set[str]:
    """拆分规则名列表字符串为集合，如 'monitor-lock,bare-catch' -> {'monitor-lock', 'bare-catch'}。"""
    return {t.strip() for t in re.split(r"[,\s]+", text) if t.strip()}


def extract_file_ignores(lines: list[str]) -> set[str]:
    """提取文件内的全部文件级豁免规则（如 // githooks:ignore-file 或 <!-- githooks:ignore-file）。"""
    allowed: set[str] = set()
    for line in lines:
        m = IGNORE_DIRECTIVE.search(line)
        if m and m.group(1):  # 有 -file
            allowed |= split_rules(m.group(2))
    return allowed


def extract_line_ignores(lines: list[str], lineno: int) -> set[str]:
    """提取行级豁免规则（违规行本行或上一行）。lineno 从 1 开始。"""
    allowed: set[str] = set()
    if lineno < 1 or lineno > len(lines):
        return allowed

    # 违规行本行以及紧贴在其上的上一行
    probes = [lines[lineno - 1]]
    if lineno >= 2:
        probes.append(lines[lineno - 2])

    for probe in probes:
        m = IGNORE_DIRECTIVE.search(probe)
        if m and not m.group(1):  # 无 -file
            allowed |= split_rules(m.group(2))
    return allowed


def is_rule_ignored(rule_name: str, allowed_rules: set[str]) -> bool:
    """判断规则是否被豁免（支持单规则匹配或 'all' 通配放行）。"""
    return rule_name in allowed_rules or "all" in allowed_rules


def is_file_ignored(content: str, rule_name: str) -> bool:
    """便捷方法：检查整个文件内容是否包含针对该规则或 all 的文件级豁免。"""
    lines = content.splitlines()
    file_ignores = extract_file_ignores(lines)
    return is_rule_ignored(rule_name, file_ignores)
