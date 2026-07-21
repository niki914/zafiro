package com.niki914.nexus.xposed.api.util

import android.os.Build

enum class OsFamily {
    ColorOS,
    HyperOS,
    Unknown
}

object OsUtils {

    fun getCurr(): OsFamily {
        val manufacturer = Build.MANUFACTURER.trim().lowercase()
        val o = setOf("oppo", "oneplus", "realme")
        val result = when {
            manufacturer in o -> OsFamily.ColorOS
            manufacturer.contains("xiaomi") -> OsFamily.HyperOS
            else -> OsFamily.Unknown
        }

        return result
    }
}