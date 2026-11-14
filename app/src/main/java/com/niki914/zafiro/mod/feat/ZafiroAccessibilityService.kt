package com.niki914.zafiro.mod.feat

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.niki914.zafiro.accessibility.driver.SystemAccessibilityDriver
import com.niki914.zafiro.app.screen.PhonePointer

class ZafiroAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        SystemAccessibilityDriver.attach(this)
        PhonePointer.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 无状态驱动，不需要在每次系统 UI 事件时做全局标记
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onDestroy() {
        PhonePointer.detach()
        SystemAccessibilityDriver.detach()
        super.onDestroy()
    }
}
