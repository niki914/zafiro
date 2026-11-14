package com.niki914.zafiro.accessibility.port

import android.view.Display
import com.niki914.zafiro.accessibility.driver.AccessibilityDriver
import com.niki914.zafiro.accessibility.driver.SystemAccessibilityDriver
import com.niki914.zafiro.accessibility.shell.ShellPointerDriver
import com.niki914.zafiro.api.screen.DefaultDisplayPort as IDefaultDisplayPort

/**
 * 物理主屏幕的显示端口实现（固定 [displayId] 为 [Display.DEFAULT_DISPLAY]）。
 */
open class DefaultDisplayPort(
    driver: AccessibilityDriver = SystemAccessibilityDriver,
    shell: ShellPointerDriver = ShellPointerDriver(),
) : A11yDisplayPort(Display.DEFAULT_DISPLAY, driver, shell), IDefaultDisplayPort
