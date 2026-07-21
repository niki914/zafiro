package com.niki914.nexus.xposed.api.util

import android.util.Log

fun xtlog(tag: String, msg: String) = xlog("[$tag] $msg")

fun xlog(msg: String) = try {
    Log.e("nexus-x-log", msg)
} catch (_: Throwable) {
    System.err.println("[nexus-x-log] $msg")
}
