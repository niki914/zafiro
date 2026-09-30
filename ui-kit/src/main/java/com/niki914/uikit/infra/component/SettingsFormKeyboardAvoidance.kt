package com.niki914.uikit.infra.component

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

/**
 * 设置表单页的键盘避让通道。
 *
 * 由 [SettingsDetailFormScaffold] 下发，[SettingExpandableTextItem] 使用：
 * 输入框展开并获焦时登记自己为 [activeKey]，并把未裁剪的真实矩形交给 [activeBounds]；
 * 脚手架据此把内容滚到键盘之上（见脚手架内的避让循环）。
 *
 * 只服务设置表单，故意不挂在公共输入框容器上——那里是全 app 输入框的收口，
 * 会波及主对话输入框与各弹窗。
 */
@Stable
internal class SettingsFormKeyboardAvoidance {
    /** 当前展开且获焦的输入框标识；null 表示无。值变化即代表一次新的避让请求。 */
    var activeKey: Any? by mutableStateOf(null)
        private set

    /**
     * 该输入框未裁剪的真实矩形（root 坐标）。
     *
     * 故意不做成 Compose 状态：它每个布局帧都会变（滚动、展开动画都会改），
     * 做成状态会让登记方与脚手架逐帧重组。读取方在帧循环里直接取最新值。
     */
    internal var activeBounds: Rect? = null

    internal fun request(key: Any) {
        activeKey = key
    }

    internal fun release(key: Any) {
        if (activeKey === key) {
            activeKey = null
            activeBounds = null
        }
    }
}

internal val LocalSettingsFormKeyboardAvoidance =
    compositionLocalOf<SettingsFormKeyboardAvoidance?> { null }
