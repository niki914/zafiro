package com.niki914.logging

/** 日志输出后端。默认按平台提供（Android 为 [LogcatBackend]，JVM 为 [PrintBackend]）。 */
interface Backend {
    fun emit(level: Level, tag: String, msg: String, throwable: Throwable?)
}
