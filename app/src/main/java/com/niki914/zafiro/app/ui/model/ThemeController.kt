package com.niki914.zafiro.app.ui.model

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.niki914.logging.Logger
import com.niki914.store.StoreDescriptorRegistry
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.base.UiStyleLookup
import com.niki914.uikit.base.UiStylePart
import com.niki914.uikit.base.resolveThemeColorScheme
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.app.BuildConfig
import com.niki914.zafiro.app.ui.model.ThemeController.load
import com.niki914.zafiro.app.ui.model.ThemeController.setMode
import com.niki914.zafiro.app.ui.model.ThemeController.setSeedColor
import com.niki914.zafiro.repo.UiStylesCodec
import com.niki914.zafiro.repo.XRepo

/** 深浅色模式。 */
enum class ThemeMode(val storageKey: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    companion object {
        fun fromStorageKey(value: String?): ThemeMode =
            entries.firstOrNull { it.storageKey == value } ?: Light
    }
}

/** 主题偏好：深浅色模式 + 种子色（空 = 跟随壁纸动态色）+ 设计语言风格。 */
data class ThemePrefs(
    val mode: ThemeMode = ThemeMode.Light,
    /** ARGB int；null = 壁纸动态色。 */
    val seedColor: Int? = 0xFFFABD00.toInt(),
    val themeStyle: ThemeStyle = ThemeStyle.default(),
) {
    fun resolveDarkTheme(systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
}

/**
 * 全局主题状态单一来源：MainActivity 冷启动时 [load]，主题设置页经 [setMode]/[setSeedColor]/[setThemeStyle]
 * 更新内存并落盘（调用方用组合作用域 launch），ZafiroApp 据此驱动 BaseTheme。
 *
 * TODO(appstate-reactive): 本对象是手搓的第五个模式 A（内存快照 + 写穿 + 手工 load），
 *  待 XRepo 响应式设置注册表稳定后收编为 ReactiveAppStateField，删除手工 load/prefs 双写。
 */
object ThemeController {
    var prefs by mutableStateOf(ThemePrefs())
        private set

    /** 组件覆盖表。空表或某键缺失时，该组件跟随 [ThemePrefs.themeStyle]。 */
    var uiStyleOverrides by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /**
     * 调试构建的部件风格。改这一份即可，只留在内存，不写 settings/ui_styles.json。
     * 正式包不读。删掉某一行后，该部件改跟文件；文件也没有就跟全局风格。
     */
    private val debugPreset = listOf(
        UiStylePart.TopBarPlate to ThemeStyle.LiquidGlass,
        UiStylePart.TopBarButtons to ThemeStyle.Material3Expressive,
        UiStylePart.Button to ThemeStyle.Material3Expressive,
        UiStylePart.Slider to ThemeStyle.Material3Expressive,
        UiStylePart.Switch to ThemeStyle.Material3Expressive,
        UiStylePart.PageScroll to ThemeStyle.LiquidGlass,
        UiStylePart.Dialog to ThemeStyle.Material3Expressive,
        UiStylePart.Menu to ThemeStyle.Material3Expressive,
        UiStylePart.TextField to ThemeStyle.Material3Expressive,
        UiStylePart.Settings to ThemeStyle.Material3Expressive,
        UiStylePart.Composer to ThemeStyle.Material3Expressive,
        UiStylePart.Search to ThemeStyle.Material3Expressive,
        UiStylePart.Loading to ThemeStyle.Material3Expressive,
    )

    fun styleOverride(part: UiStylePart): ThemeStyle? {
        if (BuildConfig.DEBUG) {
            debugPreset.firstOrNull { it.first == part }?.second?.let { return it }
        }
        return UiStylesCodec.overrideOrNull(uiStyleOverrides, part)
    }

    private val styleLookup = UiStyleLookup { styleOverride(it) }

    fun uiStyleLookup(): UiStyleLookup = styleLookup

    suspend fun load() {
        runCatchingCancellable {
            prefs = ThemePrefs(
                mode = ThemeMode.fromStorageKey(XRepo.themeMode()),
                seedColor = XRepo.themeSeedColor().takeIf { it.isNotBlank() }?.toLongOrNull(16)
                    ?.toInt(),
                themeStyle = ThemeStyle.fromStorageKey(XRepo.themeStyle()),
            )
        }.onFailure {
            Logger.w("niki914_zafiro_ThemeController", "load failed ${it.message}")
        }
        runCatchingCancellable {
            uiStyleOverrides = UiStylesCodec.parse(
                XRepo.readJson(StoreDescriptorRegistry.UI_STYLES_ID),
            )
        }.onFailure {
            Logger.w("niki914_zafiro_ThemeController", "load ui styles failed ${it.message}")
        }
    }

    suspend fun setMode(mode: ThemeMode) {
        prefs = prefs.copy(mode = mode)
        runCatching { XRepo.setThemeMode(mode.storageKey) }
            .onFailure {
                Logger.w(
                    "niki914_zafiro_ThemeController",
                    "persist mode failed ${it.message}"
                )
            }
    }

    suspend fun setThemeStyle(style: ThemeStyle) {
        prefs = prefs.copy(themeStyle = style)
        runCatchingCancellable { XRepo.setThemeStyle(style.storageKey) }
            .onFailure {
                Logger.w(
                    "niki914_zafiro_ThemeController",
                    "persist theme style failed ${it.message}"
                )
            }
    }

    suspend fun setSeedColor(argb: Int?) {
        prefs = prefs.copy(seedColor = argb)
        val hex = argb?.let { "%08X".format(it) } ?: ""
        runCatching { XRepo.setThemeSeedColor(hex) }
            .onFailure {
                Logger.w(
                    "niki914_zafiro_ThemeController",
                    "persist seed failed ${it.message}"
                )
            }
    }

    /**
     * 解析当前主题对应的 [androidx.compose.material3.ColorScheme.primaryContainer] ARGB 整数值。
     * 与悬浮球 [FloatingBallCollapsedBall] 底色及 [BaseTheme] 保持完全同口径。
     */
    fun resolvePrimaryContainer(context: Context): Int {
        val isSystemDark =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val isDark = prefs.resolveDarkTheme(isSystemDark)
        val seed = prefs.seedColor?.let { Color(it) }
        val scheme = resolveThemeColorScheme(
            context = context,
            darkTheme = isDark,
            dynamicColor = prefs.seedColor == null,
            seedColor = seed,
            themeStyle = prefs.themeStyle,
        )
        return scheme.primaryContainer.toArgb()
    }
}
