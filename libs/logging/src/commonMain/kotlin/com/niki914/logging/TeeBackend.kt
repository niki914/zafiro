package com.niki914.logging

/** 把同一条日志同时交给多个后端（例如 logcat + 文件）。 */
class TeeBackend(private vararg val backends: Backend) : Backend {
    override fun emit(level: Level, tag: String, msg: String, throwable: Throwable?) {
        backends.forEach { it.emit(level, tag, msg, throwable) }
    }
}
