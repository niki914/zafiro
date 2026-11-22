package com.niki914.uikit.base

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * 全局中低端设备渲染档位标志，由 [BaseTheme] 提供。
 * 为 true 时高开销视觉效果（如多阶渐进模糊背景板）应强制降级。
 */
val LocalLowEndDevice = staticCompositionLocalOf { false }

/**
 * 6 GiB（约 6.44 GB）：Android `totalMem` 会扣除内核与硬件保留内存，
 * 6 GB 物理内存机型实测约 5.4~5.7 GB（命中降级），8 GB 机型实测约 7.3+ GB（放行）。
 */
private const val LOW_END_RAM_THRESHOLD_BYTES = 6L * 1024L * 1024L * 1024L

@Volatile
private var cachedLowEndDevice: Boolean? = null

/**
 * 基于系统只读硬件指标判定是否为中低端渲染设备，结果按进程缓存一次。
 */
fun isLowEndDevice(context: Context): Boolean {
    cachedLowEndDevice?.let { return it }

    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val isLowRam = activityManager?.isLowRamDevice == true
    val totalMemBelowThreshold = if (activityManager != null) {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        memInfo.totalMem in 1 until LOW_END_RAM_THRESHOLD_BYTES
    } else {
        false
    }

    // 国产 ROM 常见未申报 MPC（返回 0），仅将显式声明低于 Android 12 等级（1..30）视为弱机信号
    val lowPerformanceClass = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        Build.VERSION.MEDIA_PERFORMANCE_CLASS in 1 until Build.VERSION_CODES.S

    return (isLowRam || totalMemBelowThreshold || lowPerformanceClass).also {
        cachedLowEndDevice = it
    }
}

@Composable
internal fun rememberIsLowEndDevice(): Boolean {
    val view = LocalView.current
    if (view.isInEditMode) return false
    val context = LocalContext.current
    return remember(context) { isLowEndDevice(context) }
}
