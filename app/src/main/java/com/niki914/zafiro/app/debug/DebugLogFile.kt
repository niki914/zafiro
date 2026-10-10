package com.niki914.zafiro.app.debug

import android.app.Application
import android.os.Process
import com.niki914.logging.FileBackend
import com.niki914.logging.LogcatBackend
import com.niki914.logging.Logger
import com.niki914.logging.TeeBackend
import com.niki914.zafiro.app.BuildConfig
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 调试构建把日志同时落到沙箱：`filesDir/logs/<进程>-<MMdd-HHmmss>.log`。
 *
 * 每个进程写自己的文件（主进程 `main`、Chaquopy `:python`），文件名带启动时刻，
 * 所以一次运行一个文件，多进程之间不需要任何协调，也不会互相覆盖。
 * release 构建不装文件后端，一行都不写。
 *
 * 读法（debug 包名带 `.debug` 后缀，见 `app/build.gradle.kts`）：
 * `adb exec-out run-as com.niki914.zafiro.debug cat files/logs/main-1010-143005.log > log.txt`
 * 文件也在 agent 的沙箱根 `filesDir` 下，可以直接让 agent 用 terminal 读。
 *
 * 宿主进程（Xposed 注入）写不进这个沙箱，它的日志只在 logcat。
 */
object DebugLogFile {

    private const val LOG_TAG = "niki914_zafiro_DebugLogFile"
    private const val DIR_NAME = "logs"
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024

    /** 只保留最新的若干个日志文件：调试跑久了不至于把沙箱堆满。 */
    private const val MAX_FILES = 10

    private val stampFormat = DateTimeFormatter.ofPattern("MMdd-HHmmss")

    fun install(application: Application) {
        if (!BuildConfig.DEBUG) return
        val dir = File(application.filesDir, DIR_NAME)
        runCatching { dir.mkdirs() }
        prune(dir)
        Logger.install(TeeBackend(LogcatBackend, FileBackend(MAX_FILE_BYTES) { newFile(dir) }))
        Logger.i(
            LOG_TAG,
            "log file enabled dir=${dir.absolutePath} proc=${processLabel()} pid=${Process.myPid()} v=${BuildConfig.VERSION_NAME}",
        )
    }

    private fun newFile(dir: File): File {
        return File(dir, "${processLabel()}-${LocalDateTime.now().format(stampFormat)}.log")
    }

    private fun prune(dir: File) {
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".log") } ?: return
        files.sortedByDescending { it.lastModified() }.drop(MAX_FILES).forEach { it.delete() }
    }

    /** 进程名取 `/proc/self/cmdline` 的 `:` 后缀；读不到时退回 pid，避免两个进程抢同一个文件。 */
    private fun processLabel(): String {
        val name = runCatching {
            File("/proc/self/cmdline").readBytes()
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .decodeToString()
        }.getOrNull()
        return name?.takeIf { it.isNotBlank() }?.substringAfter(':', "main") ?: "proc${Process.myPid()}"
    }
}
