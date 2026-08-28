package com.niki914.uikit.infra.nav

import androidx.compose.runtime.compositionLocalOf

interface Page {
    val routeKey: String
}

/**
 * 当前导航 entry 解析后的页面标题字符串，由页面宿主按 entry 提供。
 * 供需要复用页面标题的容器（如设置页大标题）消费，避免二次维护文案。
 */
val LocalPageTitle = compositionLocalOf { "" }
