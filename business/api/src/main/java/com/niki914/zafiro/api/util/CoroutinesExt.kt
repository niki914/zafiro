// githooks:ignore-file bare-catch 基础设施层：在此实现取消安全异常分发
package com.niki914.zafiro.api.util

import kotlinx.coroutines.CancellationException

/**
 * 协程与并发安全的 [runCatching] 替代品。
 *
 * 核心机制：
 * 1. 遇到 [CancellationException]（含 [kotlinx.coroutines.TimeoutCancellationException]）一律重新抛出，维护结构化并发；
 * 2. 遇到 [InterruptedException] 时恢复当前线程中断位并重新抛出，维护线程中断状态；
 * 3. 其余 [Throwable] 正常捕获进入 [Result.failure]。
 *
 * 作为 inline 函数，其 block 可无缝执行挂起代码（suspend call），且零对象分配开销。
 */
public inline fun <R> runCatchingCancellable(block: () -> R): Result<R> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

/**
 * 接收者形式的 [runCatchingCancellable]。
 */
public inline fun <T, R> T.runCatchingCancellable(block: T.() -> R): Result<R> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

/**
 * 协程安全的 [Result.mapCatching]。
 */
public inline fun <T, R> Result<T>.mapCatchingCancellable(transform: (value: T) -> R): Result<R> {
    return when {
        isSuccess -> runCatchingCancellable { transform(getOrThrow()) }
        else -> Result.failure(exceptionOrNull()!!)
    }
}

/**
 * 表达式风格的容错获取。
 *
 * 遇 [CancellationException] 或 [InterruptedException] 重新抛出；其余异常捕获并返回 null。
 * 相比 [runCatchingCancellable] 避免了 [Result] 包装与 `.getOrNull()` 的链式开销。
 */
public inline fun <R> tryOrNull(block: () -> R): R? {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw e
    } catch (_: Throwable) {
        null
    }
}

/**
 * 表达式风格的带默认值容错获取。
 */
public inline fun <R> tryOrDefault(default: R, block: () -> R): R {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw e
    } catch (_: Throwable) {
        default
    }
}

/**
 * 尽力而为（Best-effort / Fire-and-forget）副作用执行。
 *
 * 专用于清理、关流、通知等无需返回值的场景。
 * 遇 [CancellationException] 或 [InterruptedException] 重新抛出；其余异常静默吞掉，返回 [Unit]。
 */
public inline fun tryIgnore(block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw e
    } catch (_: Throwable) {
        // ignore
    }
}
