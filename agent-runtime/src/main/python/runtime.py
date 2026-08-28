import sys
import threading
from io import StringIO

_CHUNK = 4096


class OutputBudget:
    """Shared byte budget consumed by one or more :class:`BoundedWriter`."""

    def __init__(self, max_bytes: int = 50000):
        self.max_bytes = max_bytes
        self.remaining = max_bytes


class BoundedWriter:
    """Write-only buffer that draws from a shared :class:`OutputBudget`.

    Once the budget is exhausted every subsequent write is silently
    discarded.  A truncation marker is appended by :meth:`getvalue`.
    """

    def __init__(self, budget: OutputBudget):
        self._budget = budget
        self._buf = StringIO()
        self._truncated = False

    def write(self, s: str) -> int:
        if self._budget.remaining <= 0:
            if s:
                self._truncated = True
            return len(s)
        for i in range(0, len(s), _CHUNK):
            if self._budget.remaining <= 0:
                break
            chunk = s[i:i + _CHUNK]
            b = chunk.encode("utf-8")
            if len(b) <= self._budget.remaining:
                self._buf.write(chunk)
                self._budget.remaining -= len(b)
            else:
                # Take as many complete UTF-8 bytes as fit.
                keep = b[:self._budget.remaining].decode("utf-8", errors="ignore")
                self._buf.write(keep)
                self._budget.remaining = 0
                self._truncated = True
                break
        return len(s)

    def flush(self):
        """No-op — satisfies the TextIO flush() contract (e.g. print(..., flush=True))."""
        pass

    def getvalue(self) -> str:
        val = self._buf.getvalue()
        if self._truncated:
            val += f"\n\n[output truncated at {self._budget.max_bytes} bytes]"
        return val


class _ThreadRouter:
    """sys.stdout/stderr stand-in that routes writes per-thread.

    exec_code never swaps the global stream (concurrent Binder threads
    calling exec_code would otherwise overwrite each other's stdout and
    cross-wire results). Each exec thread binds its own writer; any other
    thread — including threads spawned by the exec'd code — falls back to
    the original stream.
    """

    def __init__(self, fallback):
        self._fallback = fallback
        self._local = threading.local()

    def bind(self, writer):
        self._local.writer = writer

    def unbind(self):
        self._local.writer = None

    def _writer(self):
        return getattr(self._local, "writer", None) or self._fallback

    def write(self, s: str) -> int:
        return self._writer().write(s)

    def flush(self):
        self._writer().flush()

    def isatty(self) -> bool:
        return False


_real_stdout = sys.stdout
_real_stderr = sys.stderr
sys.stdout = _ThreadRouter(_real_stdout)
sys.stderr = _ThreadRouter(_real_stderr)


def exec_code(code: str, timeout: float = 30.0) -> str:
    """Execute Python code in a daemon thread with a timeout.

    Captures stdout and stderr with a shared 50 KB budget. Safe under
    concurrent invocations: output capture is thread-local.
    Raises *TimeoutError* if the thread is still alive after *timeout*
    seconds.

    Args:
        code: Python source code to execute.
        timeout: Maximum seconds to wait (float, default 30.0).

    Returns:
        Captured stdout + optional stderr.
    """
    budget = OutputBudget()
    out_buf = BoundedWriter(budget)
    err_buf = BoundedWriter(budget)

    def run():
        sys.stdout.bind(out_buf)
        sys.stderr.bind(err_buf)
        try:
            exec(code, {"__builtins__": __builtins__})
        except Exception as e:
            print(f"{type(e).__name__}: {e}", file=sys.stderr)

    t = threading.Thread(target=run)
    t.daemon = True
    t.start()
    t.join(timeout=timeout)

    output = out_buf.getvalue()
    err_text = err_buf.getvalue()
    if err_text:
        output += "\n--- stderr ---\n" + err_text
    if t.is_alive():
        raise TimeoutError(
            f"Execution timed out after {timeout}s\n\n"
            f"Partial output:\n{output}"
        )
    return output
