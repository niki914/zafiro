"""Unit tests for runtime.py — executable with plain unittest (no pytest)."""

import os
import shutil
import sys
import tempfile
import unittest

# Ensure the runtime module is importable from the source tree.
# The Gradle task sets PYTHONPATH; when running locally the repo root
# should already be on sys.path.
try:
    import runtime
except ImportError:
    sys.path.insert(0, "agent-runtime/src/main/python")
    import runtime

_CACHE_DIR = tempfile.mkdtemp(prefix="runtime_test_cache_")
os.environ["ZAFIRO_CACHE_DIR"] = _CACHE_DIR
import importlib

importlib.reload(runtime)


class ExecCodeTest(unittest.TestCase):
    """Tests for runtime.exec_code() — file-based output capture."""

    def tearDown(self):
        py_output = os.path.join(_CACHE_DIR, "py_output")
        if os.path.isdir(py_output):
            shutil.rmtree(py_output)

    def test_returns_file_path_and_file_holds_stdout(self):
        result = runtime.exec_code("print('hello')")
        self.assertEqual("ok", result["status"])
        path = result["file_path"]
        self.assertTrue(os.path.isfile(path))
        with open(path, encoding="utf-8") as f:
            self.assertIn("hello", f.read())

    def test_file_holds_stderr_after_stdout(self):
        result = runtime.exec_code(
            'import sys; print("err", file=sys.stderr)'
        )
        self.assertEqual("ok", result["status"])
        with open(result["file_path"], encoding="utf-8") as f:
            content = f.read()
        self.assertIn("err", content)

    def test_empty_output(self):
        result = runtime.exec_code("x = 1")
        self.assertEqual("ok", result["status"])
        with open(result["file_path"], encoding="utf-8") as f:
            self.assertEqual("", f.read())
        self.assertEqual("", result["inline_text"])

    def test_exception_writes_to_stderr(self):
        result = runtime.exec_code('raise ValueError("bad")')
        with open(result["file_path"], encoding="utf-8") as f:
            content = f.read()
        self.assertIn("ValueError", content)
        self.assertIn("bad", content)

    def test_large_output_not_truncated(self):
        """50KB+ 输出必须全量落盘（新方案的核心承诺）。"""
        result = runtime.exec_code('print("x" * 60000)')
        with open(result["file_path"], encoding="utf-8") as f:
            content = f.read()
        self.assertEqual(60001, len(content))

    def test_timeout_returns_status_timeout_with_partial_output(self):
        """超时不再抛异常：status=timeout，部分输出留在文件里。"""
        result = runtime.exec_code(
            'import time; print("line1", flush=True); time.sleep(2)', timeout=0.2
        )
        self.assertEqual("timeout", result["status"])
        self.assertNotEqual("", result["file_path"])
        with open(result["file_path"], encoding="utf-8") as f:
            self.assertIn("line1", f.read())

    def test_consecutive_calls_are_independent(self):
        """Variables from a previous call must not leak into the next."""
        runtime.exec_code("x = 42")
        result = runtime.exec_code("print(x)")
        with open(result["file_path"], encoding="utf-8") as f:
            content = f.read()
        self.assertNotIn("42", content)
        self.assertIn("NameError", content)

    def test_stdout_restored_after_timeout(self):
        """stdout must point to the original stream even after timeout."""
        orig = sys.stdout
        result = runtime.exec_code("import time; time.sleep(2)", timeout=0.1)
        self.assertEqual("timeout", result["status"])
        self.assertIs(sys.stdout, orig)

    def test_stdout_restored_after_exception(self):
        """stdout must point to the original stream after a Python error."""
        orig = sys.stdout
        runtime.exec_code("raise ValueError('x')")
        self.assertIs(sys.stdout, orig)

    def test_print_flush_true(self):
        """print(..., flush=True) must not crash the file writer."""
        result = runtime.exec_code('print("hello", flush=True)')
        with open(result["file_path"], encoding="utf-8") as f:
            self.assertIn("hello", f.read())

    def test_degraded_inline_when_write_fails(self):
        """cacheDir 不可写时降级 inline（尾部 1MB）。"""
        cache = tempfile.mkdtemp(prefix="runtime_degraded_")
        os.chmod(cache, 0o500)  # 不可写 → makedirs(py_output) 失败
        old = os.environ["ZAFIRO_CACHE_DIR"]
        try:
            os.environ["ZAFIRO_CACHE_DIR"] = cache
            importlib.reload(runtime)
            result = runtime.exec_code(
                'print("inline hello"); import sys; print("e1", file=sys.stderr)'
            )
            self.assertEqual("ok", result["status"])
            self.assertEqual("", result["file_path"])
            self.assertIn("inline hello", result["inline_text"])
            self.assertIn("e1", result["inline_text"])
        finally:
            os.environ["ZAFIRO_CACHE_DIR"] = old
            os.chmod(cache, 0o755)
            shutil.rmtree(cache)
            importlib.reload(runtime)

    def test_concurrent_calls_write_separate_files(self):
        """并发 exec 的输出各自独立成文件（thread-local 路由）。"""
        results = [None, None]
        import threading

        def run(i, code):
            results[i] = runtime.exec_code(code)

        t1 = threading.Thread(target=run, args=(0, "print('from-t1')"))
        t2 = threading.Thread(target=run, args=(1, "print('from-t2')"))
        t1.start()
        t2.start()
        t1.join()
        t2.join()
        for result in results:
            self.assertEqual("ok", result["status"])
        with open(results[0]["file_path"], encoding="utf-8") as f:
            self.assertIn("from-t1", f.read())
        with open(results[1]["file_path"], encoding="utf-8") as f:
            self.assertIn("from-t2", f.read())


class InlineBufferTest(unittest.TestCase):
    """Tests for runtime._InlineBuffer (degraded in-memory capture)."""

    def test_getvalue_and_get_tail(self):
        buf = runtime._InlineBuffer()
        buf.write("hello")
        self.assertIn("hello", buf.getvalue())
        self.assertIn("hello", buf.get_tail())

    def test_tail_is_clipped(self):
        buf = runtime._InlineBuffer()
        buf.write("x" * (runtime._InlineBuffer.MAX_BYTES + 100))
        self.assertLessEqual(len(buf.get_tail().encode("utf-8")), runtime._InlineBuffer.MAX_BYTES)


if __name__ == "__main__":
    unittest.main()
