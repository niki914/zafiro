#!/usr/bin/env python3
"""check_strings.py 的单元测试：全部是纯函数与内存结构，不碰真实 git。

    python3 .githooks/test_check_strings.py
"""

from __future__ import annotations

import unittest

from check_strings import (
    ParsedStrings,
    TargetLanguageReport,
    check_single_res_dir,
    parse_name_status,
    parse_strings_xml,
)


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

    def test_parse_plurals_and_arrays(self):
        xml = """<?xml version="1.0" encoding="utf-8"?>
        <resources>
            <string name="simple">Text</string>
            <plurals name="items_count">
                <item quantity="one">%d item</item>
                <item quantity="other">%d items</item>
            </plurals>
            <string-array name="tags">
                <item>tag1</item>
            </string-array>
        </resources>
        """
        parsed, err = parse_strings_xml(xml)
        self.assertIsNone(err)
        self.assertEqual(parsed.all_keys, {"simple", "items_count", "tags"})
        self.assertEqual(parsed.translatable_keys, {"simple", "items_count", "tags"})

    def test_untranslatable_flag(self):
        xml = """<?xml version="1.0" encoding="utf-8"?>
        <resources>
            <string name="app_name" translatable="false">Zafiro</string>
            <string name="action_ok">OK</string>
        </resources>
        """
        parsed, err = parse_strings_xml(xml)
        self.assertIsNone(err)
        self.assertEqual(parsed.all_keys, {"app_name", "action_ok"})
        self.assertEqual(parsed.translatable_keys, {"action_ok"})
        self.assertEqual(parsed.untranslatable_keys, {"app_name"})

    def test_duplicate_keys_detection(self):
        xml = """<?xml version="1.0" encoding="utf-8"?>
        <resources>
            <string name="dup">First</string>
            <string name="dup">Second</string>
            <string name="unique">Only one</string>
        </resources>
        """
        parsed, err = parse_strings_xml(xml)
        self.assertIsNone(err)
        self.assertEqual(parsed.duplicates, ("dup",))
        self.assertEqual(parsed.total_count, 3)

    def test_malformed_xml_returns_error(self):
        xml = """<resources><string name="broken">unclosed</resources>"""
        parsed, err = parse_strings_xml(xml)
        self.assertIsNone(parsed)
        self.assertIsNotNone(err)


class ParseNameStatusTest(unittest.TestCase):
    def test_parse_diff_output(self):
        raw = b"M\x00app/src/main/res/values/strings.xml\x00A\x00app/src/main/res/values-de/strings.xml\x00"
        result = parse_name_status(raw)
        self.assertEqual(
            result,
            [
                ("M", "app/src/main/res/values/strings.xml"),
                ("A", "app/src/main/res/values-de/strings.xml"),
            ],
        )

    def test_parse_rename(self):
        raw = b"R100\x00old/path.xml\x00new/path.xml\x00"
        result = parse_name_status(raw)
        self.assertEqual(result, [("R100", "new/path.xml")])


class CheckSingleResDirTest(unittest.TestCase):
    def test_aligned_locales_pass(self):
        files = {
            "mod/src/main/res/values/strings.xml": """<resources>
                <string name="k1">v1</string>
                <string name="k2">v2</string>
            </resources>""",
            "mod/src/main/res/values-en/strings.xml": """<resources>
                <string name="k1">v1</string>
                <string name="k2">v2</string>
            </resources>""",
        }

        report = check_single_res_dir(
            root="/dummy",
            res_dir="mod/src/main/res",
            content_provider=lambda p: files.get(p),
            extra_paths=set(files.keys()),
        )
        self.assertIsNotNone(report)
        self.assertFalse(report.has_error)
        self.assertEqual(len(report.target_reports), 1)
        self.assertEqual(report.target_reports[0].missing_keys, ())
        self.assertEqual(report.target_reports[0].extra_keys, ())

    def test_missing_keys_in_target_flagged(self):
        files = {
            "mod/src/main/res/values/strings.xml": """<resources>
                <string name="k1">v1</string>
                <string name="k2">v2</string>
            </resources>""",
            "mod/src/main/res/values-en/strings.xml": """<resources>
                <string name="k1">v1</string>
            </resources>""",
        }

        report = check_single_res_dir(
            root="/dummy",
            res_dir="mod/src/main/res",
            content_provider=lambda p: files.get(p),
            extra_paths=set(files.keys()),
        )
        self.assertIsNotNone(report)
        self.assertTrue(report.has_error)
        self.assertEqual(report.target_reports[0].missing_keys, ("k2",))

    def test_extra_keys_in_target_flagged(self):
        files = {
            "mod/src/main/res/values/strings.xml": """<resources>
                <string name="k1">v1</string>
            </resources>""",
            "mod/src/main/res/values-en/strings.xml": """<resources>
                <string name="k1">v1</string>
                <string name="k2">v2</string>
            </resources>""",
        }

        report = check_single_res_dir(
            root="/dummy",
            res_dir="mod/src/main/res",
            content_provider=lambda p: files.get(p),
            extra_paths=set(files.keys()),
        )
        self.assertIsNotNone(report)
        self.assertTrue(report.has_error)
        self.assertEqual(report.target_reports[0].extra_keys, ("k2",))

    def test_untranslatable_keys_in_base_not_required_in_target(self):
        files = {
            "mod/src/main/res/values/strings.xml": """<resources>
                <string name="app_name" translatable="false">Zafiro</string>
                <string name="greeting">Hello</string>
            </resources>""",
            "mod/src/main/res/values-en/strings.xml": """<resources>
                <string name="greeting">Hello</string>
            </resources>""",
        }

        report = check_single_res_dir(
            root="/dummy",
            res_dir="mod/src/main/res",
            content_provider=lambda p: files.get(p),
            extra_paths=set(files.keys()),
        )
        self.assertIsNotNone(report)
        self.assertFalse(report.has_error)
        self.assertEqual(report.target_reports[0].missing_keys, ())

    def test_missing_base_file_flagged(self):
        files = {
            "mod/src/main/res/values-en/strings.xml": """<resources>
                <string name="k1">v1</string>
            </resources>""",
        }

        report = check_single_res_dir(
            root="/dummy",
            res_dir="mod/src/main/res",
            content_provider=lambda p: files.get(p),
            extra_paths=set(files.keys()),
        )
        self.assertIsNotNone(report)
        self.assertTrue(report.has_error)
        self.assertIn("缺少基准语言文件", report.base_error)


if __name__ == "__main__":
    unittest.main()
