package com.niki914.uikit.base

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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

    // 系统栏样式跟随应用主题（唯一来源，运行时切主题即时生效）。
    // enableEdgeToEdge 默认按【系统】昼夜决定纱罩与导航栏图标颜色；应用内主题与系统
    // 不一致时（系统浅色 + 应用深色），返回键导航的设备上会被系统对比度强制渲染成
    // 一条突兀的浅色导航栏底带。这里统一改为透明底 + 按应用主题控制图标亮暗；
    // 26-28 没有对比度开关，透明底会让三键导航键不可见，退回半透明纱罩。
    val view = LocalView.current
    if (!view.isInEditMode) { // 避免在预览模式下执行
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                // 控制状态栏/导航栏图标颜色，根据应用主题亮暗调整
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
            // 页面自身不画背景（透出 window），必须跟随应用主题而非系统 DayNight
            window.decorView.setBackgroundColor(colorScheme.background.toArgb())
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                window.isNavigationBarContrastEnforced = false
            } else {
                window.navigationBarColor =
                    if (darkTheme) DarkSystemBarScrim else LightSystemBarScrim
            }
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 与 androidx.activity EdgeToEdge 默认纱罩一致：26-28 三键导航下代替透明底，保证导航键可见。 */
private const val LightSystemBarScrim = 0xE6FFFFFF.toInt()
private const val DarkSystemBarScrim = 0x801B1B1B.toInt()
