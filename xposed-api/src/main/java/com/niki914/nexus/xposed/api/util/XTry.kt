package com.niki914.nexus.xposed.api.util

fun <T> xTry(
    name: String = "xTry",
    block: () -> T
): T? = runCatching(block).onFailure {
    xtlog("xTry", "$name failed: ${it.message}")
}.getOrNull()
