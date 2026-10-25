"""检查器集合与规则全集目录。"""

from __future__ import annotations

from core.models import Category, Rule, Severity
from checks.code_rules import CODE_RULES, check_code_files
from checks.strings_rules import ALIGNMENT_RULE, VERBOSE_UI_RULE, check_res_dir, reports_to_check_result
from checks.tokens_rules import TOKEN_RULE, check_token_limits

ALL_RULES: list[Rule] = [
    *(r for r, _ in CODE_RULES),
    TOKEN_RULE,
    ALIGNMENT_RULE,
    VERBOSE_UI_RULE,
]

RULES_BY_NAME: dict[str, Rule] = {r.name: r for r in ALL_RULES}
ALL_RULE_NAMES: tuple[str, ...] = tuple(r.name for r in ALL_RULES)
