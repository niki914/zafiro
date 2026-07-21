package com.niki914.nexus.xposed.runtime.util

import de.robv.android.xposed.callbacks.XC_LoadPackage

fun <T> XC_LoadPackage.LoadPackageParam.xTry(
    name: String = "xTry",
    onError: ((Throwable?) -> Unit)? = null,
    block: () -> T
): T? = runCatching(block).onFailure {
    val className = name.substringBefore('#').substringAfter(':')
    if (className.isNotBlank()) inspectClass(className)
    onError?.invoke(it)
}.getOrNull()
