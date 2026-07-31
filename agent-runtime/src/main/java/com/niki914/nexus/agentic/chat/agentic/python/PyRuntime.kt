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
 * Call [warmUp] during [Application.onCreate] to start the Python
 * interpreter on a background thread before the first tool invocation.
 *
 * Call [exec] from a coroutine context to run Python code and
 * collect its stdout as a plain string.
 */
object PyRuntime {

    private var started = false
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Start the Chaquopy Python interpreter on a background thread.
     *
     * Safe to call multiple times — subsequent calls are no-ops once
     * the interpreter is running.
     */
    fun warmUp(context: Context) {
        scope.launch {
            startIfNeeded(context.applicationContext)
        }
    }

    /**
     * Execute Python code and return the captured stdout.
     *
     * @param code       Python 3.11 source code. Print the final result
     *                   to stdout; stderr is appended after stdout.
     * @param timeoutSec Kotlin-side hard timeout in seconds (default 30).
     * @return Captured stdout/stderr string, or a timeout message.
     */
    suspend fun exec(code: String, timeoutSec: Long = 30): String =
        withTimeout((timeoutSec + 2) * 1000L) {
            withContext(Dispatchers.Default) {
                startIfNeeded(null)
                val py = Python.getInstance()
                val runtime = py.getModule("runtime")
                runtime.callAttr("exec_code", code, timeoutSec.toInt()).toString()
            }
        }

    // ---- internals ----

    @Synchronized
    private fun startIfNeeded(context: Context?) {
        if (started) return
        val ctx = context ?: throw IllegalStateException(
            "Python runtime not started and no context available. " +
                "Call PyRuntime.warmUp(context) first."
        )
        Python.start(AndroidPlatform(ctx))
        started = true
    }
}
