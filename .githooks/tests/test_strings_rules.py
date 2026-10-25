#!/usr/bin/env python3
"""多语言资源对齐与 UI 文案规则单元测试。"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

HOOKS_DIR = Path(__file__).resolve().parent.parent
if str(HOOKS_DIR) not in sys.path:
    sys.path.insert(0, str(HOOKS_DIR))

from checks.strings_rules import (
    check_res_dir,
    check_verbose_ui,
    parse_strings_xml,
    reports_to_check_result,
)
from core.git import parse_name_status


class ParseStringsXmlTest(unittest.TestCase):
    def test_parse_normal_strings(self):
        xml = """<?xml version="1.0" encoding="utf-8"?>
        <resources>
            <string name="app_name">Zafiro</string>
            <string name="welcome">Hello %1$s</string>
        </resources>
        """
        parsed, err = parse_strings_xml(xml)
        self.assertIsNone(err)
        self.assertIsNotNone(parsed)
        self.assertEqual(parsed.total_count, 2)
        self.assertIn("app_name", parsed.all_keys)
        self.assertIn("welcome", parsed.translatable_keys)
        self.assertEqual(len(parsed.duplicates), 0)

    def test_untranslatable_flag(self):
        xml = """<?xml version="1.0" encoding="utf-8"?>
        <resources>
            <string name="app_name" translatable="false">Zafiro</string>
            <string name="action_ok">OK</string>
        </resources>
        """
        parsed, err = parse_strings_xml(xml)
        self.assertIsNone(err)
        self.assertIn("app_name", parsed.untranslatable_keys)
        self.assertNotIn("app_name", parsed.translatable_keys)


class VerboseUiTest(unittest.TestCase):
    def test_semicolon_is_flagged(self):
        xml = """<resources><string name="foo">请点击；继续操作</string></resources>"""
        warnings = check_verbose_ui(xml, "app/res/values/strings.xml")
        self.assertEqual(len(warnings), 1)
        self.assertIn("分号", warnings[0].reasons)

    def test_explanatory_parentheses_is_flagged(self):
        xml = """<resources><string name="foo">启用服务（推荐）</string></resources>"""
        warnings = check_verbose_ui(xml, "app/res/values/strings.xml")
        self.assertEqual(len(warnings), 1)
        self.assertIn("括号", warnings[0].reasons)

    def test_dynamic_format_placeholder_is_allowed(self):
        xml = """<resources><string name="foo">已选择 (%1$s) 个项目</string></resources>"""
        warnings = check_verbose_ui(xml, "app/res/values/strings.xml")
        self.assertEqual(len(warnings), 0)

    def test_code_syntax_is_allowed(self):
        xml = """<resources><string name="foo">调用 main() 函数</string></resources>"""
        warnings = check_verbose_ui(xml, "app/res/values/strings.xml")
        self.assertEqual(len(warnings), 0)


    def test_target_locale_verbose_ui_is_collected(self):
        files = {
            "app/src/main/res/values/strings.xml": '<resources><string name="k1">正常文案</string></resources>',
            "app/src/main/res/values-b+zh+Hant/strings.xml": '<resources><string name="k1">說明（括號解釋）</string></resources>',
        }
        rep = check_res_dir("/dummy", "app/src/main/res", lambda p: files.get(p), set(files.keys()))
        self.assertIsNotNone(rep)
        self.assertEqual(len(rep.verbose_warnings), 1)
        self.assertEqual(rep.verbose_warnings[0].key, "k1")
        self.assertIn("括号", rep.verbose_warnings[0].reasons)
        self.assertEqual(rep.verbose_warnings[0].path, "app/src/main/res/values-b+zh+Hant/strings.xml")


class AlignmentIntegrationTest(unittest.TestCase):
    def test_missing_and_extra_keys_flagged_in_check_result(self):
        base_xml = """<resources><string name="k1">A</string><string name="k2">B</string></resources>"""
        target_xml = """<resources><string name="k2">B</string><string name="k3">C</string></resources>"""

        files = {
            "app/src/main/res/values/strings.xml": base_xml,
            "app/src/main/res/values-en/strings.xml": target_xml,
        }

        # Mock discovery via dummy root
        from checks.strings_rules import ModuleReport, TargetLanguageReport
        rep = ModuleReport(
            res_dir="app/src/main/res",
            base_file="app/src/main/res/values/strings.xml",
            base_total=2,
            target_reports=[
                TargetLanguageReport(
                    path="app/src/main/res/values-en/strings.xml",
                    locale="values-en",
                    total_count=2,
                    missing_keys=("k1",),
                    extra_keys=("k3",),
                    duplicates=(),
                )
            ],
        )

        res = reports_to_check_result([rep], [])
        self.assertEqual(len(res.findings), 1)
        self.assertEqual(res.findings[0].rule, "strings-alignment")
        self.assertEqual(res.findings[0].category, "i18n")
        self.assertEqual(res.findings[0].severity, "block")
        self.assertIn("缺失键: [k1]", res.findings[0].message)


if __name__ == "__main__":
    unittest.main()
