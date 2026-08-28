package com.niki914.uikit.base

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.materialkolor.rememberDynamicColorScheme
import com.niki914.uikit.base.theme.DarkColorScheme
import com.niki914.uikit.base.theme.LightColorScheme
import com.niki914.uikit.base.theme.Typography

/** 全局深色模式单一来源，由 BaseTheme 提供；所有深色判断必须读它而非 isSystemInDarkTheme。 */
val LocalAppDarkTheme = staticCompositionLocalOf { true }

@Composable
fun BaseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(), // 判断系统是否为深色模式
    // 动态颜色仅在 Android 12+ (SDK 31+) 可用
    dynamicColor: Boolean = true, // 启用动态颜色
    /** 非空时从种子色生成 Material 色板，忽略 dynamicColor。 */
    seedColor: Color? = null,
    content: @Composable () -> Unit, // 实际要应用主题的 Composable 内容
) {
    // 根据条件选择颜色方案
    val colorScheme = when {
        // 种子色优先：用户主动选定的主题颜色
        seedColor != null ->
            rememberDynamicColorScheme(seedColor = seedColor, isDark = darkTheme, isAmoled = false)

        // 如果启用动态颜色且 Android 版本 >= S (Android 12)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current // 获取当前上下文
            // 根据深色模式选择动态深色或浅色方案
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme // 如果是深色模式但不支持动态颜色，使用预定义深色方案
        else -> LightColorScheme // 否则，使用预定义浅色方案
    }

    // 设置系统状态栏颜色
    val view = LocalView.current
    if (!view.isInEditMode) { // 避免在预览模式下执行
        SideEffect {
            val window = (view.context as Activity).window
//            window.statusBarColor = colorScheme.primary.toArgb() // 将状态栏颜色设置为主题主色
            // 控制状态栏图标颜色，根据主题亮暗调整
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            // 页面自身不画背景（透出 window），必须跟随应用主题而非系统 DayNight
            window.decorView.setBackgroundColor(colorScheme.background.toArgb())
        }
    }

    CompositionLocalProvider(LocalAppDarkTheme provides darkTheme) {
        // 应用 MaterialTheme
        MaterialTheme(
            colorScheme = colorScheme, // 使用选择的颜色方案
            typography = Typography, // 你的字体排版定义（通常在 Type.kt 中）
            content = content // 渲染传入的内容
        )
    }
}
