"""Unit tests for runtime.py — executable with plain unittest (no pytest)."""

import sys
import unittest
import time
from io import StringIO

# Ensure the runtime module is importable from the source tree.
# The Gradle task sets PYTHONPATH; when running locally the repo root
# should already be on sys.path.
try:
    import runtime
except ImportError:
    sys.path.insert(0, "agent-runtime/src/main/python")
    import runtime


class ExecCodeTest(unittest.TestCase):
    """Tests for runtime.exec_code()."""

    def test_captures_stdout(self):
        output = runtime.exec_code("print('hello')")
        self.assertIn("hello", output)

    def test_captures_stderr(self):
        output = runtime.exec_code(
            "import sys; print('err', file=sys.stderr)"
        )
        self.assertIn("--- stderr ---", output)
        self.assertIn("err", output)

    def test_empty_output(self):
        output = runtime.exec_code("x = 1")
        self.assertEqual("", output.strip())

    def test_exception_writes_to_stderr(self):
        output = runtime.exec_code('raise ValueError("bad")')
        self.assertIn("--- stderr ---", output)
        self.assertIn("ValueError", output)
        self.assertIn("bad", output)

    def test_returns_output_including_stderr(self):
        output = runtime.exec_code(
            'print("ok"); import sys; print("err", file=sys.stderr)'
        )
        self.assertIn("ok", output)
        self.assertIn("err", output)
        # stdout should appear before stderr separator
        self.assertLess(
            output.index("ok"),
            output.index("--- stderr ---"),
        )

    def test_timeout_raises_timeouterror(self):
        with self.assertRaises(TimeoutError) as ctx:
            runtime.exec_code("import time; time.sleep(2)", timeout=0.1)
        self.assertIn("timed out after", str(ctx.exception))
        self.assertIn("Partial output", str(ctx.exception))

    def test_consecutive_calls_are_independent(self):
        """Variables from a previous call must not leak into the next."""
        runtime.exec_code("x = 42")
        output = runtime.exec_code("print(x)")
        self.assertNotIn("42", output)
        self.assertIn("NameError", output)

    def test_stdout_restored_after_timeout(self):
        """stdout must point to the original stream even after timeout."""
        orig = sys.stdout
        try:
            runtime.exec_code("import time; time.sleep(2)", timeout=0.1)
        except TimeoutError:
            pass
        self.assertIs(sys.stdout, orig)

    def test_stdout_restored_after_exception(self):
        """stdout must point to the original stream after a Python error."""
        orig = sys.stdout
        runtime.exec_code("raise ValueError('x')")
        self.assertIs(sys.stdout, orig)

    def test_print_flush_true(self):
        """print(..., flush=True) must not crash BoundedWriter."""
        output = runtime.exec_code('print("hello", flush=True)')
        self.assertIn("hello", output)


class OutputBudgetTest(unittest.TestCase):
    """Tests for runtime.OutputBudget."""

    def test_initial_remaining(self):
        b = runtime.OutputBudget(max_bytes=1000)
        self.assertEqual(b.remaining, 1000)
        self.assertEqual(b.max_bytes, 1000)


class BoundedWriterTest(unittest.TestCase):
    """Tests for runtime.BoundedWriter."""

    def _budget(self, max_bytes: int) -> runtime.OutputBudget:
        return runtime.OutputBudget(max_bytes=max_bytes)

    def test_writes_within_limit(self):
        w = runtime.BoundedWriter(self._budget(100))
        w.write("hello")
        self.assertIn("hello", w.getvalue())
        self.assertNotIn("truncated", w.getvalue())

    def test_truncation_marker(self):
        budget = self._budget(10)
        w = runtime.BoundedWriter(budget)
        w.write("1234567890")  # exactly 10 bytes, fits
        w.write("abc")          # exceeds → truncated
        val = w.getvalue()
        self.assertIn("1234567890", val)
        self.assertIn("truncated", val)

    def test_write_after_truncation_discarded(self):
        budget = self._budget(5)
        w = runtime.BoundedWriter(budget)
        w.write("12")
        w.write("34")
        w.write("abc")  # should be discarded
        self.assertNotIn("abc", w.getvalue())

    def test_multi_byte_boundary(self):
        """Truncation on a multi-byte boundary must decode cleanly."""
        budget = self._budget(6)
        w = runtime.BoundedWriter(budget)
        w.write("你好世界")  # 四个中文字符 = 12 bytes
        val = w.getvalue()
        self.assertIsInstance(val, str)
        self.assertIn("truncated", val)

    def test_empty_writer(self):
        w = runtime.BoundedWriter(self._budget(50))
        self.assertEqual("", w.getvalue())

    def test_large_single_write_keeps_prefix(self):
        """A single oversized write keeps as many chars as fit."""
        budget = self._budget(10)
        w = runtime.BoundedWriter(budget)
        w.write("1234567890abc")  # "1234567890" = 10 bytes, "abc" exceeds
        val = w.getvalue()
        self.assertIn("1234567890", val)
        self.assertIn("truncated", val)
        self.assertNotIn("abc", val)

    def test_shared_budget_stdout_stderr(self):
        """stdout and stderr share the same budget."""
        budget = self._budget(20)
        out = runtime.BoundedWriter(budget)
        err = runtime.BoundedWriter(budget)
        out.write("A" * 15)  # 15 bytes
        err.write("B" * 10)  # 10 bytes requested, only 5 remain
        self.assertIn("A" * 15, out.getvalue())
        self.assertIn("B" * 5, err.getvalue())  # only first 5 B's
        # Total should not exceed 20 + overhead
        total = len(out.getvalue().encode("utf-8")) + len(err.getvalue().encode("utf-8"))
        self.assertLessEqual(total, 120)  # generous margin for truncation markers

    def test_flush_is_noop(self):
        """flush() must exist so print(..., flush=True) doesn't crash."""
        w = runtime.BoundedWriter(self._budget(100))
        w.flush()  # must not raise
        w.write("hello")
        w.flush()
        self.assertIn("hello", w.getvalue())


class ExecCodeOutputCappingTest(unittest.TestCase):
    """Verify that BoundedWriter caps stdout during exec_code()."""

    def test_large_output_is_capped(self):
        """50 KB of output should be capped at ~50 KB."""
        output = runtime.exec_code(
            "print('x' * 60_000)",
            timeout=10.0,
        )
        self.assertIn("truncated", output)
        self.assertLessEqual(len(output.encode("utf-8")), 55_000)  # small margin

    def test_small_output_not_capped(self):
        output = runtime.exec_code(
            "print('hello')",
            timeout=10.0,
        )
        self.assertNotIn("truncated", output)

    def test_stderr_also_draws_from_budget(self):
        """Writing to stderr consumes the same cap as stdout."""
        # Fill budget with stdout, then write to stderr.
        code = (
            "import sys\n"
            "print('x' * 45_000)\n"       # stdout takes ~45 KB
            "print('y' * 20_000, file=sys.stderr)"  # stderr barely fits
        )
        output = runtime.exec_code(code, timeout=10.0)
        self.assertIn("truncated", output)


if __name__ == "__main__":
    unittest.main()
