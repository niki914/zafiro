import sys
import threading
from io import StringIO


class BoundedWriter:
    """Write-only buffer capped at *max_bytes* (UTF-8).

    Once the cap is reached every subsequent write is silently discarded
    and a truncation marker is appended by :meth:`getvalue`.
    """

    def __init__(self, max_bytes: int = 50000):
        self._buf = StringIO()
        self._max = max_bytes
        self._n = 0
        self._truncated = False

    def write(self, s: str) -> int:
        if self._truncated:
            return 0
        b = s.encode("utf-8")
        if self._n + len(b) > self._max:
            self._truncated = True
            return 0
        self._buf.write(s)
        self._n += len(b)
        return len(s)

    def getvalue(self) -> str:
        val = self._buf.getvalue()
        if self._truncated:
            val += f"\n\n[output truncated at {self._max} bytes]"
        return val


def exec_code(code: str, timeout: float = 30.0) -> str:
    """Execute Python code in a daemon thread with a timeout.

    Captures stdout (capped at 50 KB) and stderr separately.
    Raises *TimeoutError* if the thread is still alive after *timeout*
    seconds.

    Args:
        code: Python source code to execute.
        timeout: Maximum seconds to wait (float, default 30.0).

    Returns:
        Captured stdout + optional stderr.
    """
    out_buf = BoundedWriter()
    err_buf = StringIO()
    old_out = sys.stdout
    old_err = sys.stderr
    sys.stdout = out_buf
    sys.stderr = err_buf

    def run():
        try:
            exec(code, {"__builtins__": __builtins__})
        except Exception as e:
            print(f"{type(e).__name__}: {e}", file=sys.stderr)

    t = threading.Thread(target=run)
    t.daemon = True
    t.start()
    t.join(timeout=timeout)

    sys.stdout = old_out
    sys.stderr = old_err

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
