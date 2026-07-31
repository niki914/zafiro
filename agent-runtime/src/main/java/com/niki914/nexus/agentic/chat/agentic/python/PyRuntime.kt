package com.niki914.nexus.agentic.chat.agentic.python

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Singleton that wraps Chaquopy's embedded Python runtime.
 *
 * Call [warmUp] during [Application.onCreate] to save the application
 * context and start the Python interpreter on a background thread.
 *
 * Call [exec] from a coroutine context to run Python code and
 * collect its stdout as a plain string.
 */
object PyRuntime {

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var started = false
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Save the application context and start the Chaquopy Python
     * interpreter on a background thread.
     *
     * The context is saved synchronously so that [exec] can initialize
     * the runtime inline if the background warm-up has not completed yet.
     *
     * Safe to call multiple times — subsequent calls are no-ops once
     * the interpreter is running.
     */
    fun warmUp(context: Context) {
        appContext = context.applicationContext
        scope.launch {
            startIfNeeded()
        }
    }

    /**
     * Execute Python code and return the captured stdout.
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
                startIfNeeded()
                val py = Python.getInstance()
                val runtime = py.getModule("runtime")
                runtime.callAttr("exec_code", code, timeoutMs / 1000.0).toString()
            }
        }

    // ---- internals ----

    @Synchronized
    private fun startIfNeeded() {
        if (started) return
        val ctx = appContext ?: throw IllegalStateException(
            "Python runtime not started and no context available. " +
                "Call PyRuntime.warmUp(context) first."
        )
        Python.start(AndroidPlatform(ctx))
        started = true
    }
}
