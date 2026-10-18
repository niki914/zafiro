// githooks:ignore-file bare-catch 宿主防御：捕获 hook 执行异常以防止宿主应用闪退
package com.niki914.xposed.runtime.util

import com.niki914.logging.Logger
import kotlinx.coroutines.CancellationException

private const val LOG_TAG = "niki914_zafiro_HookCatching"

internal inline fun <T> hookCatching(
    name: String,
    block: () -> T
): T? {
    return try {
        block()
    } catch (throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
        Logger.e(LOG_TAG, "$name failed", throwable)
        null
    }
}
