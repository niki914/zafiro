package com.niki914.zafiro.app.ui.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.niki914.logging.Logger
import com.niki914.zafiro.repo.XRepo

/** 深浅色模式。 */
enum class ThemeMode(val storageKey: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    companion object {
        fun fromStorageKey(value: String?): ThemeMode =
            entries.firstOrNull { it.storageKey == value } ?: System
    }
}

/** 主题偏好：深浅色模式 + 种子色（空 = 跟随壁纸动态色）。 */
data class ThemePrefs(
    val mode: ThemeMode = ThemeMode.Dark,
    /** ARGB int；null = 壁纸动态色。 */
    val seedColor: Int? = 0xFF52DBC9.toInt(),
) {
    fun resolveDarkTheme(systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
}

/**
 * 全局主题状态单一来源：MainActivity 冷启动时 [load]，主题设置页经 [setMode]/[setSeedColor]
 * 更新内存并落盘（调用方用组合作用域 launch），ZafiroApp 据此驱动 BaseTheme。
 */
object ThemeController {
    var prefs by mutableStateOf(ThemePrefs())
        private set

    suspend fun load() {
        runCatching {
            prefs = ThemePrefs(
                mode = ThemeMode.fromStorageKey(XRepo.themeMode()),
                seedColor = XRepo.themeSeedColor().takeIf { it.isNotBlank() }?.toLongOrNull(16)?.toInt(),
            )
        }.onFailure {
            Logger.w("niki914_nexus_ThemeController", "load failed ${it.message}")
        }
    }

    suspend fun setMode(mode: ThemeMode) {
        prefs = prefs.copy(mode = mode)
        runCatching { XRepo.setThemeMode(mode.storageKey) }
            .onFailure { Logger.w("niki914_nexus_ThemeController", "persist mode failed ${it.message}") }
    }

    suspend fun setSeedColor(argb: Int?) {
        prefs = prefs.copy(seedColor = argb)
        val hex = argb?.let { "%08X".format(it) } ?: ""
        runCatching { XRepo.setThemeSeedColor(hex) }
            .onFailure { Logger.w("niki914_nexus_ThemeController", "persist seed failed ${it.message}") }
    }
}
