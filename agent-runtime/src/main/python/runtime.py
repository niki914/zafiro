import io
import os
import sys
import threading
import uuid

# Worker 进程自算 cacheDir 子目录（Java 侧无需传路径）。
# 传输语义：Java 读到内容后即消费（截断时 move 到 filesDir/tool_output
# 作为导出，否则删除）。cacheDir 系统可随时回收，进程崩溃残留有兜底。


class _ThreadRouter:
    """sys.stdout/stderr stand-in that routes writes per-thread.

    exec_code never swaps the global stream (concurrent Binder threads
    calling exec_code would otherwise overwrite each other's stdout and
    cross-wire results). Each exec thread binds its own file handle; any
    other thread — including threads spawned by the exec'd code — falls
    back to the original stream.
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


class _InlineBuffer:
    """写盘失败时的内存降级缓冲：尾部 1MB 滚动窗口 + StringIO 累计 stderr。"""

    MAX_BYTES = 1024 * 1024

    def __init__(self):
        self._buf = io.StringIO()
        self._tail = ""

    def write(self, s: str) -> int:
        self._buf.write(s)
        self._tail = (self._tail + s).encode("utf-8")[-self.MAX_BYTES:].decode(
            "utf-8", errors="ignore"
        )
        return len(s)

    def flush(self):
        pass

    def getvalue(self) -> str:
        return self._buf.getvalue()

    def get_tail(self) -> str:
        return self._tail


_real_stdout = sys.stdout
_real_stderr = sys.stderr
sys.stdout = _ThreadRouter(_real_stdout)
sys.stderr = _ThreadRouter(_real_stderr)


def _new_output_file() -> str:
    out_dir = os.path.join(os.environ.get("ZAFIRO_CACHE_DIR", "/tmp"), "py_output")
    os.makedirs(out_dir, exist_ok=True)
    return os.path.join(out_dir, uuid.uuid4().hex + ".log")


def _combine(out: str, err: str) -> str:
    """stdout + stderr 拼接，与旧 inline 语义一致：stderr 有内容才加分隔符。"""
    return out + ("\n--- stderr ---\n" + err if err else "")


def _exec_result(status: str, file_path: str = "", inline_text: str = "") -> dict:
    return {"status": status, "file_path": file_path, "inline_text": inline_text}


def exec_code(code: str, timeout: float = 30.0) -> dict:
    """Execute Python code in a daemon thread with a timeout.

    stdout + stderr are streamed in full into a per-call file under
    cacheDir/py_output — no output budget, no Binder payload for content.
    Safe under concurrent invocations: output capture is thread-local.

    Args:
        code: Python source code to execute.
        timeout: Maximum seconds to wait (float, default 30.0).

    Returns:
        dict: {"status", "file_path", "inline_text"}.
        - status "ok": normal run, output in file_path
        - status "timeout": partial output stays in file_path
        - inline_text is the degraded inline payload used only when writing
          the file failed (tail-clipped at 1 MB, stdout and stderr separated
          by a --- stderr --- marker).
    """
    out_path = None
    out_f = err_f = None
    inline_out = inline_err = None
    try:
        out_path = _new_output_file()
        out_f = open(out_path, "w", encoding="utf-8")
        err_f = open(out_path, "a", encoding="utf-8")
    except OSError:
        # ponytail: 写盘失败降级 inline（1MB clip tail），目录不可写属极罕见；
        # 若降级也高频出现，应把输出改走 Binder 直接回传并砍掉文件链路
        inline_out = _InlineBuffer()
        inline_err = _InlineBuffer()

    def run():
        sys.stdout.bind(inline_out if inline_out is not None else out_f)
        sys.stderr.bind(inline_err if inline_err is not None else err_f)
        try:
            exec(code, {"__builtins__": __builtins__})
        except Exception as e:
            print(f"{type(e).__name__}: {e}", file=sys.stderr)
        finally:
            for f in (out_f, err_f):
                if f is not None:
                    try:
                        f.flush()
                    except Exception:
                        pass
            sys.stdout.unbind()
            sys.stderr.unbind()

    t = threading.Thread(target=run)
    t.daemon = True
    t.start()
    t.join(timeout=timeout)

    if out_f is not None:
        # 不 close：worker 线程可能仍在写（超时场景），flush 即可，
        # 文件随 worker 结束自然完整；残留由 cacheDir 兜底回收。
        for f in (out_f, err_f):
            try:
                f.flush()
            except Exception:
                pass

    if t.is_alive():
        return _exec_result("timeout", file_path=out_path or "")

    if out_path is not None:
        return _exec_result("ok", file_path=out_path)

    # 写盘失败降级：inline 返回尾部 1MB（stdout+stderr 顺序拼接）
    return _exec_result(
        "ok", inline_text=_combine(inline_out.get_tail(), inline_err.get_tail())
    )
