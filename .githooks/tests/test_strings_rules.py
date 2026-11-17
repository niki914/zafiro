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
    APP_MAIN_RES_DIR,
    KNOWN_LOCALES,
    LANGUAGE_UI_RULE,
    THEME_SETTINGS_PATH,
    check_res_dir,
    check_ui_language_registration,
    check_verbose_ui,
    get_effective_locales,
    parse_strings_xml,
    parse_ui_language_options,
    reports_to_check_result,
    tag_matches_locale,
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


class DynamicLocaleDiscoveryTest(unittest.TestCase):
    def test_get_effective_locales_unions_base_and_discovered(self):
        base = ("values", "values-en")
        extra_paths = {"app/src/main/res/values-de/strings.xml", "app/src/main/res/values-fr/strings.xml"}
        effective, new_locales = get_effective_locales(
            root=None,
            res_dir="app/src/main/res",
            extra_paths=extra_paths,
            base_known=base,
        )
        self.assertEqual(effective, {"values", "values-en", "values-de", "values-fr"})
        self.assertEqual(new_locales, {"values-de", "values-fr"})

    def test_missing_expected_locale_in_res_dir_is_flagged(self):
        files = {
            "app/src/main/res/values/strings.xml": '<resources><string name="k1">A</string></resources>',
            "app/src/main/res/values-en/strings.xml": '<resources><string name="k1">A</string></resources>',
        }
        rep = check_res_dir(
            root="/dummy",
            res_dir="app/src/main/res",
            content_provider=lambda p: files.get(p),
            extra_paths=set(files.keys()),
            expected_locales={"values", "values-en", "values-de"},
        )
        self.assertIsNotNone(rep)
        self.assertTrue(rep.has_error)
        missing_reports = [t for t in rep.target_reports if "缺少语言资源文件" in (t.xml_error or "")]
        self.assertEqual(len(missing_reports), 1)
        self.assertEqual(missing_reports[0].locale, "values-de")
        self.assertIn("app/src/main/res/values-de/strings.xml", missing_reports[0].xml_error)


class ParseUiLanguageOptionsTest(unittest.TestCase):
    VALID_KT = """
    private const val LANGUAGE_TAG_ZH_CN = "zh-CN"
    private const val LANGUAGE_TAG_ZH_TW = "zh-TW"
    private const val LANGUAGE_TAG_EN = "en"
    private const val LANGUAGE_TAG_DE = "de"

    @Composable
    private fun languageOptions(): List<LanguageOption> {
        return listOf(
            LanguageOption(tag = "", label = "跟随系统"),
            LanguageOption(tag = LANGUAGE_TAG_ZH_CN, label = "简体中文"),
            LanguageOption(tag = LANGUAGE_TAG_ZH_TW, label = "繁體中文"),
            LanguageOption(tag = LANGUAGE_TAG_EN, label = "English"),
            LanguageOption(tag = LANGUAGE_TAG_DE, label = "Deutsch"),
        )
    }
    """

    def test_parse_valid_content(self):
        opts, err = parse_ui_language_options(self.VALID_KT, "ThemeSettingsContent.kt")
        self.assertIsNone(err)
        self.assertIsNotNone(opts)
        self.assertEqual(len(opts), 5)
        self.assertIn("zh-CN", opts)
        self.assertEqual(opts["de"], "Deutsch")
        self.assertIn("", opts)

    def test_missing_language_options_function_reports_actionable_error(self):
        refactored_kt = """
        // 重构：已改名为 getSupportedLocales
        fun getSupportedLocales(): List<LocaleItem> = emptyList()
        """
        opts, err = parse_ui_language_options(refactored_kt, "ThemeSettingsContent.kt")
        self.assertIsNone(opts)
        self.assertIsNotNone(err)
        self.assertIn("未匹配到 'languageOptions()' 函数声明", err)
        self.assertIn(".githooks/checks/strings_rules.py", err)

    def test_empty_options_reports_actionable_error(self):
        empty_kt = """
        fun languageOptions(): List<LanguageOption> {
            return emptyList()
        }
        """
        opts, err = parse_ui_language_options(empty_kt, "ThemeSettingsContent.kt")
        self.assertIsNone(opts)
        self.assertIsNotNone(err)
        self.assertIn("未解析到任何有效的 LanguageOption(...) 项", err)
        self.assertIn(".githooks/checks/strings_rules.py", err)


class CheckUiLanguageRegistrationTest(unittest.TestCase):
    THEME_KT = """
    private const val LANGUAGE_TAG_ZH_CN = "zh-CN"
    private const val LANGUAGE_TAG_EN = "en"

    private fun languageOptions(): List<LanguageOption> {
        return listOf(
            LanguageOption(tag = "", label = "跟随系统"),
            LanguageOption(tag = LANGUAGE_TAG_ZH_CN, label = "简体中文"),
            LanguageOption(tag = LANGUAGE_TAG_EN, label = "English"),
        )
    }
    """

    def test_missing_file_reports_actionable_finding(self):
        findings = check_ui_language_registration(
            app_res_locales={"values", "values-en"},
            content_provider=lambda p: None,
            theme_settings_path="ThemeSettingsContent.kt",
            app_res_dir="app/src/main/res",
        )
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].rule, "language-ui-registration")
        self.assertIn("未找到 UI 语言注册文件", findings[0].message)
        self.assertIn(".githooks/checks/strings_rules.py", findings[0].message)

    def test_refactored_content_reports_actionable_finding(self):
        refactored = "fun completelyDifferentCode() {}"
        findings = check_ui_language_registration(
            app_res_locales={"values", "values-en"},
            content_provider=lambda p: refactored,
            theme_settings_path="ThemeSettingsContent.kt",
            app_res_dir="app/src/main/res",
        )
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].rule, "language-ui-registration")
        self.assertIn("UI 语言注册解析失败", findings[0].message)
        self.assertIn("未匹配到 'languageOptions()' 函数声明", findings[0].message)

    def test_unregistered_locale_in_res_is_flagged(self):
        files = {
            "ThemeSettingsContent.kt": self.THEME_KT,
            "app/src/main/res/values-de/strings.xml": "<resources></resources>",
        }
        findings = check_ui_language_registration(
            app_res_locales={"values", "values-en", "values-de"},
            content_provider=lambda p: files.get(p),
            theme_settings_path="ThemeSettingsContent.kt",
            app_res_dir="app/src/main/res",
        )
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].rule, "language-ui-registration")
        self.assertIn("检测到语言资源目录 'values-de'", findings[0].message)
        self.assertIn("缺少对应 tag: 'de'", findings[0].message)
        self.assertIn("LanguageOption(tag = \"de\", label = \"...\")", findings[0].hint)

    def test_unregistered_locale_with_ignore_comment_passes(self):
        ignored_xml = '<!-- githooks:ignore-file language-ui-registration 暂不对外开放 -->\n<resources></resources>'
        files = {
            "ThemeSettingsContent.kt": self.THEME_KT,
            "app/src/main/res/values-de/strings.xml": ignored_xml,
        }
        findings = check_ui_language_registration(
            app_res_locales={"values", "values-en", "values-de"},
            content_provider=lambda p: files.get(p),
            theme_settings_path="ThemeSettingsContent.kt",
            app_res_dir="app/src/main/res",
        )
        self.assertEqual(len(findings), 0)

    def test_registered_tag_missing_resource_dir_is_flagged(self):
        theme_with_extra = """
        private fun languageOptions(): List<LanguageOption> {
            return listOf(
                LanguageOption(tag = "", label = "跟随系统"),
                LanguageOption(tag = "fr", label = "Français"),
            )
        }
        """
        findings = check_ui_language_registration(
            app_res_locales={"values"},
            content_provider=lambda p: theme_with_extra,
            theme_settings_path="ThemeSettingsContent.kt",
            app_res_dir="app/src/main/res",
        )
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].rule, "language-ui-registration")
        self.assertIn("UI 语言选项中注册了 tag: 'fr'", findings[0].message)
        self.assertIn("未找到对应的 values 资源目录", findings[0].message)

    def test_aligned_locales_pass_cleanly(self):
        files = {
            "ThemeSettingsContent.kt": self.THEME_KT,
            "app/src/main/res/values/strings.xml": "<resources></resources>",
            "app/src/main/res/values-en/strings.xml": "<resources></resources>",
        }
        findings = check_ui_language_registration(
            app_res_locales={"values", "values-en"},
            content_provider=lambda p: files.get(p),
            theme_settings_path="ThemeSettingsContent.kt",
            app_res_dir="app/src/main/res",
        )
        self.assertEqual(len(findings), 0)


if __name__ == "__main__":
    unittest.main()
