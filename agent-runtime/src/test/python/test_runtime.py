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
        # The second exec should see a NameError in stderr.
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


class BoundedWriterTest(unittest.TestCase):
    """Tests for runtime.BoundedWriter."""

    def test_writes_within_limit(self):
        w = runtime.BoundedWriter(max_bytes=100)
        w.write("hello")
        self.assertIn("hello", w.getvalue())
        self.assertNotIn("truncated", w.getvalue())

    def test_truncation_marker(self):
        w = runtime.BoundedWriter(max_bytes=10)
        w.write("1234567890")  # exactly 10 bytes, fits
        w.write("abc")          # exceeds → truncated
        val = w.getvalue()
        self.assertIn("1234567890", val)
        self.assertIn("truncated", val)

    def test_write_after_truncation_discarded(self):
        w = runtime.BoundedWriter(max_bytes=5)
        w.write("12345")
        w.write("67890")  # should be discarded
        self.assertNotIn("67890", w.getvalue())

    def test_multi_byte_boundary(self):
        """Truncation on a multi-byte boundary must decode cleanly."""
        w = runtime.BoundedWriter(max_bytes=6)
        w.write("你好世界")  # 你好世界 = 12 bytes
        val = w.getvalue()
        # Must not crash with UnicodeDecodeError
        self.assertIsInstance(val, str)
        self.assertIn("truncated", val)

    def test_empty_writer(self):
        w = runtime.BoundedWriter(max_bytes=50)
        self.assertEqual("", w.getvalue())


class ExecCodeOutputCappingTest(unittest.TestCase):
    """Verify that BoundedWriter caps stdout during exec_code()."""

    def test_large_output_is_capped(self):
        """50 KB of output should be capped at 50 KB."""
        output = runtime.exec_code(
            "print('x' * 60_000)",
            timeout=10.0,
        )
        self.assertIn("truncated", output)
        self.assertLessEqual(len(output.encode("utf-8")), 51_000)  # small margin

    def test_small_output_not_capped(self):
        output = runtime.exec_code(
            "print('hello')",
            timeout=10.0,
        )
        self.assertNotIn("truncated", output)


if __name__ == "__main__":
    unittest.main()
