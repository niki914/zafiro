package com.niki914.zafiro.chat.agentic.python

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs Chaquopy in the dedicated `:python` process (declared in
 * `agent-runtime/src/main/AndroidManifest.xml`).
 *
 * Timeout model (see [IPythonWorkerService]):
 * - Normal timeout: `runtime.exec_code` joins its worker thread for
 *   `timeoutMs` and returns a `TimeoutError` text — no process kill, the
 *   interpreter stays healthy and reusable.
 * - Hard-stuck interpreter (native code holding the GIL): the Binder call
 *   never returns; the client's `withTimeout` fires and it invokes [kill],
 *   which destroys this process — the only reliable way to reclaim a stuck
 *   Python interpreter.
 *
 * Python is initialized on a dedicated [HandlerThread] so the interpreter's
 * "main thread" is stable for the process lifetime. Binder threads may call
 * `callAttr` from anywhere (GIL serializes entry), same as the previous
 * in-process implementation.
 */
class PythonWorkerService : Service() {

    private val pythonThread = HandlerThread("python-main").apply { start() }
    private val pythonHandler = Handler(pythonThread.looper)
    private val ready = CountDownLatch(1)

    @Volatile
    private var initFailure: Throwable? = null

    override fun onCreate() {
        super.onCreate()
        pythonHandler.post {
            try {
                Python.start(AndroidPlatform(applicationContext))
                // runtime.py 从这里拿传输文件目录（cacheDir/py_output）；
                // 不设则缺省 /tmp，Android 上不可写 → 写盘降级全量走 inline
                Python.getInstance()
                    .getModule("os")
                    .get("environ")!!
                    .callAttr("__setitem__", "ZAFIRO_CACHE_DIR", applicationContext.cacheDir.absolutePath)
            } catch (t: Throwable) {
                initFailure = t
            } finally {
                ready.countDown()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = Stub()

    override fun onDestroy() {
        pythonThread.quitSafely()
        super.onDestroy()
    }

    private fun awaitReady() {
        if (!ready.await(30, TimeUnit.SECONDS)) {
            throw IllegalStateException("Python interpreter failed to start within 30s")
        }
        initFailure?.let { throw it }
    }

    private inner class Stub : IPythonWorkerService.Stub() {
        override fun exec(code: String?, timeoutMs: Long): PyExecResult {
            awaitReady()
            val py = Python.getInstance()
            val runtime = py.getModule("runtime")
            val result = runtime.callAttr(
                "exec_code",
                code ?: "",
                timeoutMs / 1000.0
            )
            // runtime.exec_code 返回 {status, file_path, inline_text}
            val status = when (result.callAttr("get", "status").toString()) {
                "timeout" -> PyExecResult.Status.TIMEOUT
                "exec_error" -> PyExecResult.Status.EXEC_ERROR
                else -> PyExecResult.Status.OK
            }
            val filePath = result.callAttr("get", "file_path").toString().ifEmpty { null }
            val inlineText = result.callAttr("get", "inline_text").toString().ifEmpty { null }
            return PyExecResult(status, filePath, inlineText)
        }

        override fun ping(): String? {
            awaitReady()
            val py = Python.getInstance()
            return py.getModule("time").callAttr("time").toString()
        }

        override fun kill() {
            // Never touches the interpreter — always executable while any
            // Binder thread is free. The process dies, reclaiming everything.
            Process.killProcess(Process.myPid())
        }
    }
}
