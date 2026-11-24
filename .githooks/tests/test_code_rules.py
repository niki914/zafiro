#!/usr/bin/env python3
"""Kotlin 代码规则单元测试。全部是纯函数与内存数据，不碰真实 git。"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

HOOKS_DIR = Path(__file__).resolve().parent.parent
if str(HOOKS_DIR) not in sys.path:
    sys.path.insert(0, str(HOOKS_DIR))

from checks.code_rules import (
    CODE_RULE_DICT,
    check_code_files,
)
from core.git import parse_diff_added_lines


def findings_for(source: str, active_rules: set[str] | None = None):
    lines = list(range(1, len(source.splitlines()) + 1))
    res = check_code_files({"f.kt": source}, {"f.kt": lines}, active_rules)
    return res.findings, res.ignored


def rules_hit(source: str, active_rules: set[str] | None = None) -> list[str]:
    return [f.rule for f in findings_for(source, active_rules)[0]]


class ParseDiffTest(unittest.TestCase):
    DIFF = (
        "diff --git a/a.kt b/a.kt\n"
        "--- a/a.kt\n"
        "+++ b/a.kt\n"
        "@@ -1,2 +1,3 @@\n"
        "+first\n"
        " second\n"
        "+third\n"
        "diff --git a/b.md b/b.md\n"
        "--- a/b.md\n"
        "+++ b/b.md\n"
        "@@ -0,0 +1,1 @@\n"
        "+doc\n"
    )

    def test_added_lines_are_collected_with_line_numbers(self):
        self.assertEqual(parse_diff_added_lines(self.DIFF), {"a.kt": [1, 3]})

    def test_non_kotlin_files_are_skipped(self):
        self.assertNotIn("b.md", parse_diff_added_lines(self.DIFF))


class MonitorLockTest(unittest.TestCase):
    def test_synchronized_is_flagged(self):
        self.assertEqual(rules_hit("    synchronized(lock) { x() }\n"), ["monitor-lock"])

    def test_annotation_is_flagged(self):
        self.assertEqual(rules_hit("@Synchronized\nfun f() {}\n"), ["monitor-lock"])

    def test_mention_in_comment_is_not_flagged(self):
        self.assertEqual(rules_hit("// 不用 synchronized，改用 Mutex\n"), [])


class LegacyCommentTest(unittest.TestCase):
    def test_chinese_change_narration_is_flagged(self):
        self.assertEqual(rules_hit("// 不再走旧路径\n"), ["legacy-comment"])

    def test_english_change_narration_is_flagged(self):
        self.assertEqual(rules_hit("// no longer reachable\nexport\n"), ["legacy-comment"])

    def test_ordering_meaning_is_not_flagged(self):
        self.assertEqual(rules_hit("// 截在下一个条目之前\n"), [])

    def test_code_line_with_such_word_is_not_flagged(self):
        self.assertEqual(rules_hit('    log("不再")\n'), [])


class InlineFqnTest(unittest.TestCase):
    def test_fqn_without_import_is_flagged(self):
        self.assertEqual(rules_hit("val d = java.security.MessageDigest.getInstance()\n"), ["inline-fqn"])

    def test_fqn_with_same_import_is_allowed(self):
        source = "import java.security.MessageDigest\nval d = java.security.MessageDigest.getInstance()\n"
        self.assertEqual(rules_hit(source), [])

    def test_fqn_inside_string_literal_is_not_flagged(self):
        self.assertEqual(rules_hit('val a = "android.intent.action.MAIN"\n'), [])

    def test_import_line_is_not_flagged(self):
        self.assertEqual(rules_hit("import java.security.MessageDigest\n"), [])

    def test_kdoc_link_is_not_flagged(self):
        self.assertEqual(rules_hit(" * 见 [com.niki914.zafiro.api.TurnStart]\n"), [])


class LogApiTest(unittest.TestCase):
    def test_println_is_flagged(self):
        self.assertEqual(rules_hit('    println("hello")\n'), ["log-api"])

    def test_system_out_is_flagged(self):
        self.assertEqual(rules_hit('    System.out.print("hello")\n'), ["log-api"])

    def test_android_log_import_and_call_are_flagged(self):
        source = 'import android.util.Log\nLog.d("tag", "msg")\n'
        self.assertEqual(rules_hit(source), ["log-api", "log-api"])

    def test_print_inside_raw_string_is_not_flagged(self):
        source = 'val py = """\nprint("hello")\n"""\n'
        self.assertEqual(rules_hit(source), [])


class LogTagTest(unittest.TestCase):
    def test_tag_without_convention_prefix_is_flagged(self):
        self.assertEqual(rules_hit('val TAG = "MyTag"\n'), ["log-tag"])

    def test_convention_prefix_is_allowed(self):
        self.assertEqual(rules_hit('val TAG = "niki914_zafiro_Foo"\n'), [])


class UncancellableRunCatchingTest(unittest.TestCase):
    def test_bare_run_catching_is_flagged(self):
        self.assertEqual(rules_hit("runCatching { doWork() }\n"), ["uncancellable-run-catching"])

    def test_map_catching_is_flagged(self):
        self.assertEqual(rules_hit("result.mapCatching { it.next() }\n"), ["uncancellable-run-catching"])

    def test_cancellable_variants_are_allowed(self):
        self.assertEqual(rules_hit("runCatchingCancellable { doWork() }\n"), [])


class RunCatchingRequireServiceTest(unittest.TestCase):
    def test_run_catching_cancellable_with_require_service_is_flagged(self):
        self.assertEqual(
            rules_hit("val s = runCatchingCancellable { requireService<Foo>() }\n"),
            ["run-catching-require-service"],
        )

    def test_run_catching_with_require_service_is_flagged(self):
        self.assertEqual(
            rules_hit("val s = runCatching { requireService<Foo>() }\n"),
            ["uncancellable-run-catching", "run-catching-require-service"],
        )

    def test_multiline_run_catching_require_service_is_flagged(self):
        source = "val s = runCatchingCancellable {\n    requireService<Foo>()\n}\n"
        self.assertEqual(
            rules_hit(source),
            ["run-catching-require-service", "run-catching-require-service"],
        )

    def test_find_service_is_allowed(self):
        self.assertEqual(rules_hit("val s = findService<Foo>()\n"), [])

    def test_standalone_require_service_is_allowed(self):
        source = "class A {\n    fun f() {\n        val s = requireService<Foo>()\n    }\n}\n"
        self.assertEqual(rules_hit(source), [])

    def test_require_service_before_unrelated_run_catching_is_allowed(self):
        source = "fun f() {\n    val a = runCatchingCancellable { 1 }\n    val b = requireService<Foo>()\n}\n"
        self.assertEqual(rules_hit(source), [])

    def test_comment_is_not_flagged(self):
        self.assertEqual(rules_hit("// val s = runCatchingCancellable { requireService<Foo>() }\n"), [])

    def test_string_literal_is_not_flagged(self):
        self.assertEqual(rules_hit('val s = "runCatchingCancellable { requireService<Foo>() }"\n'), [])

    def test_ignore_marker_allows(self):
        source = "val s = runCatchingCancellable { requireService<Foo>() } // githooks:ignore run-catching-require-service\n"
        findings, ignored = findings_for(source)
        self.assertEqual(findings, [])
        self.assertEqual(ignored, 1)

    def test_try_extensions_with_require_service_are_flagged(self):
        self.assertEqual(
            rules_hit("val s = tryOrDefault(null) { requireService<Foo>() }\n"),
            ["run-catching-require-service"],
        )
        self.assertEqual(
            rules_hit("tryIgnore { requireService<Foo>().ping() }\n"),
            ["run-catching-require-service"],
        )

    def test_native_try_catch_with_require_service_is_flagged(self):
        source = "val s = try {\n    requireService<Foo>()\n} catch (e: IllegalStateException) {\n    null\n}\n"
        self.assertEqual(
            rules_hit(source),
            ["run-catching-require-service", "run-catching-require-service"],
        )

    def test_non_try_lambdas_with_require_service_are_allowed(self):
        self.assertEqual(rules_hit("val provider = { requireService<Foo>() }\n"), [])
        self.assertEqual(rules_hit("launch { requireService<Foo>() }\n"), [])


class BareCatchTest(unittest.TestCase):
    def test_catch_throwable_is_flagged(self):
        self.assertEqual(rules_hit("} catch (t: Throwable) {\n"), ["bare-catch"])

    def test_catch_exception_is_flagged(self):
        self.assertEqual(rules_hit("} catch (e: Exception) {\n"), ["bare-catch"])

    def test_catch_specific_exception_is_allowed(self):
        self.assertEqual(rules_hit("} catch (e: IOException) {\n"), [])


class SettingsItemDividerTest(unittest.TestCase):
    RULE = {"settings-item-divider"}
    BUSINESS = "app/src/main/java/com/niki914/zafiro/app/ui/content/Foo.kt"

    def _hit(self, source: str, path: str):
        lines = list(range(1, len(source.splitlines()) + 1))
        res = check_code_files({path: source}, {path: lines}, self.RULE)
        return [f.rule for f in res.findings], res.ignored

    def test_business_call_is_flagged(self):
        self.assertEqual(self._hit("    SettingsItemDivider()\n", self.BUSINESS)[0], ["settings-item-divider"])

    def test_ui_kit_call_is_not_flagged(self):
        path = "ui-kit/src/main/java/com/niki914/uikit/infra/component/SettingsGroupCard.kt"
        self.assertEqual(self._hit("                    SettingsItemDivider()\n", path)[0], [])

    def test_preview_file_is_not_flagged(self):
        path = "app/src/main/java/com/niki914/zafiro/app/ui/content/SavedConfigDesignPreview.kt"
        self.assertEqual(self._hit("                SettingsItemDivider()\n", path)[0], [])

    def test_preview_directory_is_not_flagged(self):
        path = "app/src/main/java/com/niki914/zafiro/app/ui/preview/theme/TextFieldThemePreview.kt"
        self.assertEqual(self._hit("    SettingsItemDivider()\n", path)[0], [])

    def test_comment_and_string_are_not_flagged(self):
        source = '// SettingsItemDivider()\nval sample = "SettingsItemDivider()"\n'
        self.assertEqual(self._hit(source, self.BUSINESS)[0], [])

    def test_ignore_comment_is_counted(self):
        source = "    SettingsItemDivider() // githooks:ignore settings-item-divider\n"
        findings, ignored = self._hit(source, self.BUSINESS)
        self.assertEqual(findings, [])
        self.assertEqual(ignored, 1)


class RoundedCornerShapeTest(unittest.TestCase):
    def test_rounded_corner_shape_call_is_flagged(self):
        self.assertEqual(rules_hit("val shape = RoundedCornerShape(12.dp)\n"), ["rounded-corner-shape"])

    def test_rounded_corner_shape_import_is_flagged(self):
        self.assertEqual(rules_hit("import androidx.compose.foundation.shape.RoundedCornerShape\n"), ["rounded-corner-shape"])


class ContentDescriptionI18nTest(unittest.TestCase):
    def test_string_resource_call_is_flagged(self):
        self.assertEqual(
            rules_hit('Icon(Icons.Default.Close, contentDescription = stringResource(R.string.exit))\n'),
            ["content-description-i18n"],
        )

    def test_multiline_string_resource_is_flagged(self):
        source = 'Icon(\n    contentDescription =\n        stringResource(R.string.exit)\n)\n'
        self.assertEqual(
            rules_hit(source),
            ["content-description-i18n", "content-description-i18n"],
        )

    def test_non_ascii_literal_is_flagged(self):
        self.assertEqual(
            rules_hit('Icon(Icons.Default.Close, contentDescription = "退出搜索")\n'),
            ["content-description-i18n"],
        )

    def test_english_literal_is_allowed(self):
        self.assertEqual(
            rules_hit('Icon(Icons.Default.Close, contentDescription = "Close")\n'),
            [],
        )

    def test_null_content_description_is_allowed(self):
        self.assertEqual(
            rules_hit('Icon(Icons.Default.Close, contentDescription = null)\n'),
            [],
        )

    def test_variable_assignment_is_allowed(self):
        self.assertEqual(
            rules_hit('Icon(Icons.Default.Close, contentDescription = action.contentDescription)\n'),
            [],
        )

    def test_comment_is_not_flagged(self):
        self.assertEqual(
            rules_hit('// contentDescription = stringResource(R.string.exit)\n'),
            [],
        )

    def test_ignore_marker_allows(self):
        source = 'Icon(Icons.Default.Close, contentDescription = stringResource(R.string.exit)) // githooks:ignore content-description-i18n\n'
        findings, ignored = findings_for(source)
        self.assertEqual(findings, [])
        self.assertEqual(ignored, 1)


class StoreBackupPairingTest(unittest.TestCase):
    def setUp(self):
        from checks.code_rules import set_backup_store_cache_for_testing
        set_backup_store_cache_for_testing(None)

    def tearDown(self):
        from checks.code_rules import set_backup_store_cache_for_testing
        set_backup_store_cache_for_testing(None)

    def test_unpaired_store_descriptor_is_flagged(self):
        from checks.code_rules import set_backup_store_cache_for_testing
        set_backup_store_cache_for_testing("val BackupStore = listOf(OLD_STORE_ID)")
        source = 'val NEW_STORE = StoreDescriptor("new_store_id")\n'
        path = "store/src/main/java/com/niki914/store/StoreDescriptorRegistry.kt"
        lines = [1]
        res = check_code_files({path: source}, {path: lines}, {"store-backup-pairing"})
        self.assertEqual(len(res.findings), 1)
        self.assertEqual(res.findings[0].rule, "store-backup-pairing")

    def test_paired_store_descriptor_passes(self):
        from checks.code_rules import set_backup_store_cache_for_testing
        set_backup_store_cache_for_testing('val BackupStore = listOf("new_store_id")')
        source = 'val NEW_STORE = StoreDescriptor("new_store_id")\n'
        path = "store/src/main/java/com/niki914/store/StoreDescriptorRegistry.kt"
        lines = [1]
        res = check_code_files({path: source}, {path: lines}, {"store-backup-pairing"})
        self.assertEqual(len(res.findings), 0)

    def test_excluded_store_passes(self):
        source = 'val WEB_SETTINGS = StoreDescriptor("web_settings")\n'
        path = "store/src/main/java/com/niki914/store/StoreDescriptorRegistry.kt"
        lines = [1]
        res = check_code_files({path: source}, {path: lines}, {"store-backup-pairing"})
        self.assertEqual(len(res.findings), 0)

    def test_ignored_store_passes(self):
        source = 'val NEW_STORE = StoreDescriptor("new_store_id") // githooks:ignore store-backup-pairing 无需备份\n'
        path = "store/src/main/java/com/niki914/store/StoreDescriptorRegistry.kt"
        lines = [1]
        res = check_code_files({path: source}, {path: lines}, {"store-backup-pairing"})
        self.assertEqual(len(res.findings), 0)
        self.assertEqual(res.ignored, 1)


class IgnoreMarkerTest(unittest.TestCase):
    def test_same_line_marker_allows(self):
        source = "    synchronized(lock) { x() } // githooks:ignore monitor-lock 必须原子交换\n"
        findings, ignored = findings_for(source)
        self.assertEqual(findings, [])
        self.assertEqual(ignored, 1)

    def test_previous_line_marker_allows(self):
        source = "// githooks:ignore monitor-lock\n    synchronized(lock) { x() }\n"
        findings, ignored = findings_for(source)
        self.assertEqual(findings, [])
        self.assertEqual(ignored, 1)

    def test_file_marker_allows_everywhere(self):
        source = "// githooks:ignore-file monitor-lock\nclass A {\n    fun f() { synchronized(this) {} }\n}\n"
        findings, ignored = findings_for(source)
        self.assertEqual(findings, [])
        self.assertEqual(ignored, 1)

    def test_all_keyword_allows_every_rule(self):
        source = "// githooks:ignore all\n    synchronized(lock) { println(1) }\n"
        findings, ignored = findings_for(source)
        self.assertEqual(findings, [])
        self.assertEqual(ignored, 2)


if __name__ == "__main__":
    unittest.main()
