package com.niki914.xposed.api.util

import com.niki914.logging.Logger

fun <T> xTry(
    name: String = "xTry",
    block: () -> T
): T? = runCatching(block).onFailure {
    Logger.w(LOG_TAG, "$name failed: ${it.message}")
}.getOrNull()

private const val LOG_TAG = "niki914_nexus_xTry"
