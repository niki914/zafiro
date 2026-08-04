package com.niki914.s3ss10n

import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * 模块内唯一允许直接使用 Log 与 try/catch 的位置。
 */
internal fun xLog(tag: String, str: String) {
    Log.e(tag, str)
}

internal fun xLog(tag: String, str: String, t: Throwable) {
    Log.e(tag, str, t)
}

internal fun xLog(str: String) {
    Log.e("X", str)
}

internal fun xLog(str: String, t: Throwable) {
    Log.e("X", str, t)
}

internal inline fun <T> xTry(name: String, log: Boolean = true, block: () -> T): T? = try {
    block()
} catch (ce: CancellationException) {
    throw ce
} catch (t: Throwable) {
    if (log) xLog(tagFrom(name), "xTry($name) failed", t)
    null
}

internal inline fun <T> xTry(
    name: String,
    onError: (Throwable) -> T,
    block: () -> T
): T = try {
    block()
} catch (ce: CancellationException) {
    throw ce
} catch (t: Throwable) {
    xLog(tagFrom(name), "xTry($name) failed", t)
    onError(t)
}

internal suspend inline fun <T> xTrySuspend(name: String, crossinline block: suspend () -> T): T? = try {
    block()
} catch (ce: CancellationException) {
    throw ce
} catch (t: Throwable) {
    xLog(tagFrom(name), "xTrySuspend($name) failed", t)
    null
}

internal suspend inline fun <T> xTrySuspend(
    name: String,
    crossinline onError: suspend (Throwable) -> T,
    crossinline block: suspend () -> T
): T = try {
    block()
} catch (ce: CancellationException) {
    throw ce
} catch (t: Throwable) {
    xLog(tagFrom(name), "xTrySuspend($name) failed", t)
    onError(t)
}

private fun tagFrom(name: String): String {
    return name.substringBefore('.').ifBlank { "X" }
}
