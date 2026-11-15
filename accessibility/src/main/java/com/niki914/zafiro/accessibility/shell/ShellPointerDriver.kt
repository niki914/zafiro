package com.niki914.zafiro.accessibility.shell

import android.content.Context
import android.view.Display
import com.niki914.libterm.runtime.LibTerm
import com.niki914.libterm.runtime.Term
import com.niki914.libterm.runtime.TermResult
import com.niki914.logging.Logger
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.accessibility.driver.SystemAccessibilityDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 终端底层输入驱动，封装带有显式 displayId（`-d <id>`）的输入命令。
 *
 * 当无障碍手势或节点动作失效时，作为通用的物理注入兜底通道。
 * 注入通道按 Su (Root) -> Shizuku -> User -> Runtime.exec 顺序兜底。
 */
class ShellPointerDriver(
    private val executor: suspend (String) -> Boolean = ::defaultShellExec,
) {
    suspend fun tap(displayId: Int, x: Int, y: Int): Boolean {
        val cmd = if (displayId == Display.DEFAULT_DISPLAY) {
            "input tap $x $y"
        } else {
            "input -d $displayId tap $x $y"
        }
        return executor(cmd)
    }

    suspend fun swipe(
        displayId: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long,
    ): Boolean {
        val cmd = if (displayId == Display.DEFAULT_DISPLAY) {
            "input swipe $x1 $y1 $x2 $y2 $durationMs"
        } else {
            "input -d $displayId swipe $x1 $y1 $x2 $y2 $durationMs"
        }
        return executor(cmd)
    }

    suspend fun key(displayId: Int, keyCode: Int): Boolean {
        val cmd = if (displayId == Display.DEFAULT_DISPLAY) {
            "input keyevent $keyCode"
        } else {
            "input -d $displayId keyevent $keyCode"
        }
        return executor(cmd)
    }

    companion object {
        private const val TAG = "niki914_zafiro_ShellPointerDriver"

        @Volatile
        private var cachedTerm: Term? = null

        private suspend fun defaultShellExec(command: String): Boolean = withContext(Dispatchers.IO) {
            val cached = cachedTerm
            if (cached != null) {
                val ok = runCatchingCancellable { execSucceeded(cached, command) }.getOrDefault(false)
                if (ok) return@withContext true
                cachedTerm = null
                closeQuietly(cached)
            }

            if (tryTerm(LibTerm.openSuTerm(), command)) return@withContext true
            val context: Context? = SystemAccessibilityDriver.serviceContext
            if (context != null && tryTerm(LibTerm.openShizukuTerm(context), command)) {
                return@withContext true
            }
            if (tryTerm(LibTerm.openUserTerm(), command)) return@withContext true
            false
        }

        private suspend fun tryTerm(term: Term, command: String): Boolean {
            val keep = runCatchingCancellable {
                if (term.open() !is TermResult.Success) return@runCatchingCancellable false
                execSucceeded(term, command).also { ok -> if (ok) cachedTerm = term }
            }.getOrDefault(false)
            if (!keep) closeQuietly(term)
            return keep
        }

        private suspend fun execSucceeded(term: Term, command: String): Boolean {
            val result = term.exec(command, timeoutMillis = 5_000L)
            return result is TermResult.Success && result.value.exitCode == 0
        }

        private suspend fun closeQuietly(term: Term) {
            runCatchingCancellable { term.close() }
        }
    }
}
