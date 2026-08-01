package com.niki914.nexus.agentic.chat.agentic.python

import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.niki914.nexus.xposed.api.util.ContextProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Singleton that wraps Chaquopy's embedded Python runtime.
 *
 * Call [warmUp] during [Application.onCreate] (inside a coroutine) to
 * start the Python interpreter on a background thread before the first
 * tool invocation.  [warmUp] is idempotent — subsequent calls are no-ops
 * once the interpreter is running.
 *
 * Call [exec] from a coroutine context to run Python code and collect
 * its stdout as a plain string.
 */
object PyRuntime {

    @Volatile
    private var started = false

    /**
     * Start the Chaquopy Python interpreter.
     *
     * Waits for [ContextProvider] to be available (synchronous in practice —
     * [ContextProvider.provide] is called during [Application.onCreate]).
     * Safe to call multiple times; only the first call has any effect.
     */
    suspend fun warmUp() {
        if (started) return
        val ctx = ContextProvider.await().applicationContext
        Python.start(AndroidPlatform(ctx))
        started = true
    }

    /**
     * Execute Python code and return the captured stdout.
     *
     * If the interpreter has not been started yet (e.g. the warm-up
     * coroutine has not run), [warmUp] is called inline.
     *
     * @param code      Python 3.11 source code. Print the final result
     *                  to stdout; stderr is appended after stdout.
     * @param timeoutMs Kotlin-side hard timeout in milliseconds (default 30000).
     *                  Python receives timeoutMs / 1000.0 as a float so
     *                  sub-second precision is preserved.
     * @return Captured stdout/stderr string.
     */
    suspend fun exec(code: String, timeoutMs: Long = 30_000): String =
        withTimeout(timeoutMs + 2_000L) {
            withContext(Dispatchers.Default) {
                if (!started) warmUp()
                val py = Python.getInstance()
                val runtime = py.getModule("runtime")
                runtime.callAttr("exec_code", code, timeoutMs / 1000.0).toString()
            }
        }
}
