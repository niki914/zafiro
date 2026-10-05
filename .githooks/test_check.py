#!/usr/bin/env python3
"""check.py 的测试：全部是纯函数，不碰 git、不碰 Gradle。

    python3 .githooks/test_check.py
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check import RULE_NAMES, SEVERITY, collect_findings, excluded, parse_diff  # noqa: E402


def findings_for(source: str, rules: tuple[str, ...] = RULE_NAMES):
    lines = list(range(1, len(source.splitlines()) + 1))
    return collect_findings({"f.kt": source}, {"f.kt": lines}, rules)


def rules_hit(source: str, rules: tuple[str, ...] = RULE_NAMES) -> list[str]:
    return [f.rule for f in findings_for(source, rules)[0]]


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
        self.assertEqual(parse_diff(self.DIFF), {"a.kt": [1, 3]})

    def test_non_kotlin_files_are_skipped(self):
        self.assertNotIn("b.md", parse_diff(self.DIFF))


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
        source = "// githooks:ignore-file monitor-lock\nsynchronized(a) {}\nsynchronized(b) {}\n"
        findings, ignored = findings_for(source)
        self.assertEqual(findings, [])
        self.assertEqual(ignored, 2)

    def test_all_keyword_allows_every_rule(self):
        source = "// githooks:ignore all\n// 曾经的老路径\n"
        findings, ignored = findings_for(source)
        self.assertEqual(findings, [])
        self.assertEqual(ignored, 1)

    def test_marker_for_other_rule_does_not_leak(self):
        source = "// githooks:ignore inline-fqn\nsynchronized(lock) {}\n"
        findings, ignored = findings_for(source)
        self.assertEqual([f.rule for f in findings], ["monitor-lock"])
        self.assertEqual(ignored, 0)

    def test_rule_filter_limits_findings(self):
        source = "synchronized(lock) {}\n// 不再走旧路径\n"
        self.assertEqual(rules_hit(source, ("legacy-comment",)), ["legacy-comment"])


class LogApiTest(unittest.TestCase):
    def test_android_log_import_and_call_are_flagged(self):
        source = 'import android.util.Log\nfun f() { Log.d("t", "m") }\n'
        self.assertEqual(rules_hit(source), ["log-api", "log-api"])

    def test_unrelated_log_symbol_is_not_flagged(self):
        self.assertEqual(rules_hit('fun f() { myLog.d("t", "m") }\n'), [])

    def test_println_is_flagged(self):
        self.assertEqual(rules_hit("    println(answer)\n"), ["log-api"])

    def test_system_out_is_flagged(self):
        self.assertEqual(rules_hit("System.out.println(1)\n"), ["log-api"])

    def test_print_inside_raw_string_is_not_flagged(self):
        self.assertEqual(rules_hit('val md = """\n    print(json.dumps(x))\n"""\n'), [])

    def test_print_inside_single_line_raw_string_is_not_flagged(self):
        self.assertEqual(rules_hit('val c = """{"code":"print(1)"}"""\n'), [])


class LogTagTest(unittest.TestCase):
    def test_tag_without_convention_prefix_is_flagged(self):
        self.assertEqual(rules_hit('private const val LOG_TAG = "old_tag"\n'), ["log-tag"])

    def test_convention_prefix_is_allowed(self):
        self.assertEqual(rules_hit('private const val LOG_TAG = "niki914_zafiro_Foo"\n'), [])

    def test_camel_case_name_is_recognized(self):
        self.assertEqual(rules_hit('private val logTag = "nope"\n'), ["log-tag"])

    def test_inline_literal_tag_is_flagged(self):
        self.assertEqual(rules_hit('Logger.w("foo", "m")\n'), ["log-tag"])

    def test_tag_reference_is_allowed(self):
        self.assertEqual(rules_hit('Logger.w(LOG_TAG, "m")\n'), [])

    def test_unrelated_tag_constant_is_not_flagged(self):
        self.assertEqual(rules_hit('const val TAG_PREFIX = "zfr-"\n'), [])

    def test_code_block_tag_is_not_flagged(self):
        self.assertEqual(rules_hit('const val TAG = "files"\n'), ["log-tag"])


class RawStringTest(unittest.TestCase):
    def test_lines_inside_raw_string_are_skipped(self):
        source = 'val md = """\ncom.niki914.Foo.bar()\n// 不再走旧路径\n"""\n'
        self.assertEqual(rules_hit(source), [])

    def test_single_line_raw_string_does_not_hide_following_lines(self):
        self.assertEqual(rules_hit('val a = """x"""\n// 不再走\n'), ["legacy-comment"])


class ScopeTest(unittest.TestCase):
    def test_libs_is_excluded(self):
        self.assertTrue(excluded("libs/libterm/Foo.kt"))
        self.assertFalse(excluded("app/Foo.kt"))

    def test_severity_policy(self):
        self.assertEqual(SEVERITY["monitor-lock"], "block")
        self.assertEqual(SEVERITY["legacy-comment"], "warn")
        self.assertEqual(SEVERITY["inline-fqn"], "warn")
        self.assertEqual(SEVERITY["log-api"], "block")
        self.assertEqual(SEVERITY["log-tag"], "warn")


if __name__ == "__main__":
    unittest.main(verbosity=2)
