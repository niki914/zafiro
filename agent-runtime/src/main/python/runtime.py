import sys
import threading
from io import StringIO


def exec_code(code: str, timeout: int = 30) -> str:
    """Execute a Python code string in a daemon thread with a timeout.

    Captures stdout and stderr separately, appends stderr after stdout,
    and appends a timeout marker if the thread is still alive after *timeout*
    seconds.

    Args:
        code: Python source code to execute.
        timeout: Maximum seconds to wait (default 30).

    Returns:
        Captured stdout + optional stderr + optional timeout marker.
    """
    out_buf = StringIO()
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
        output += "\n[execution timed out after {}s]".format(timeout)
    return output
