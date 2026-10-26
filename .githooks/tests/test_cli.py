#!/usr/bin/env python3
"""CLI 规则解析与调度器单元测试。"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

HOOKS_DIR = Path(__file__).resolve().parent.parent
if str(HOOKS_DIR) not in sys.path:
    sys.path.insert(0, str(HOOKS_DIR))

from cli import resolve_active_rules
from core.models import Category


class CliResolutionTest(unittest.TestCase):
    def test_default_resolves_all_rules(self):
        rules = resolve_active_rules(None, None)
        self.assertIn("monitor-lock", rules)
        self.assertIn("file-token-limit", rules)
        self.assertIn("strings-alignment", rules)
        self.assertIn("content-description-i18n", rules)
        self.assertIn("rounded-corner-shape", rules)
        self.assertEqual(len(rules), 12)

    def test_category_filter_limits_rules(self):
        rules = resolve_active_rules(["ui"], None)
        self.assertEqual(rules, {"rounded-corner-shape", "verbose-ui"})

        arch_rules = resolve_active_rules(["architecture"], None)
        self.assertEqual(arch_rules, {"file-token-limit"})

        safety_rules = resolve_active_rules(["safety"], None)
        self.assertEqual(
            safety_rules,
            {"monitor-lock", "uncancellable-run-catching", "bare-catch", "log-api", "log-tag"},
        )

        i18n_rules = resolve_active_rules(["i18n"], None)
        self.assertEqual(
            i18n_rules,
            {"strings-alignment", "content-description-i18n"},
        )

    def test_specific_rule_filter(self):
        rules = resolve_active_rules(None, ["monitor-lock,log-api"])
        self.assertEqual(rules, {"monitor-lock", "log-api"})

    def test_strings_wrapper_rules_resolve_both_alignment_and_verbose_ui(self):
        from check_strings import STRING_RULE_NAMES
        rules = resolve_active_rules(None, [",".join(STRING_RULE_NAMES)])
        self.assertEqual(rules, {"strings-alignment", "verbose-ui"})

    def test_code_wrapper_rules_resolve_only_code_rules(self):
        from check import RULE_NAMES
        rules = resolve_active_rules(None, [",".join(RULE_NAMES)])
        self.assertEqual(len(rules), 9)
        self.assertNotIn("strings-alignment", rules)
        self.assertNotIn("verbose-ui", rules)
        self.assertNotIn("file-token-limit", rules)


if __name__ == "__main__":
    unittest.main()
