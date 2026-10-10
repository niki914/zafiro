package com.niki914.logging

import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.locks.ReentrantLock

/**
 * 落盘后端：把每条日志追加到当前日志文件，写满 [maxBytes] 就向 [nextFile] 要下一个文件继续写。
 *
 * - 命名由调用方决定（通常把进程名与启动时刻写进文件名），本类只负责写。
 * - 每条日志（含堆栈）拼成一整段文本、一次 `write` 落盘：无用户态缓冲，进程被 kill 也不丢尾部；
 *   多进程恰好写同一个文件时，O_APPEND 保证整行不会互相插花。
 * - 用锁而不是协程：日志必须在调用线程同步落盘，不能挂起。
 * - IO 异常一律吞掉，日志不能反过来把业务搞崩。
 */
class FileBackend(
    private val maxBytes: Long,
    private val nextFile: () -> File,
) : Backend {

    private val lock = ReentrantLock()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private var stream: FileOutputStream? = null
    private var written = 0L

    override fun emit(level: Level, tag: String, msg: String, throwable: Throwable?) {
        val line = buildString {
            append(LocalDateTime.now().format(timeFormat))
            append(' ')
            append(level.name.first())
            append('/')
            append(tag)
            append(": ")
            append(msg)
            append('\n')
            throwable?.let { append(it.stackTraceToString()).append('\n') }
        }

        lock.lock()
        try {
            val out = stream?.takeIf { written < maxBytes } ?: openNext()
            val bytes = line.toByteArray()
            out.write(bytes)
            written += bytes.size
        } catch (_: Throwable) {
            // 磁盘满 / 无权限等场景放弃写文件，logcat 那条仍在
        } finally {
            lock.unlock()
        }
    }

    private fun openNext(): FileOutputStream {
        stream?.let { runCatching { it.close() } }
        stream = null
        val file = nextFile()
        file.parentFile?.mkdirs()
        val out = FileOutputStream(file, true)
        stream = out
        written = file.length()
        return out
    }
}
