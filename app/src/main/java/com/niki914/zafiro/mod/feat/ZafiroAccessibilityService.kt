package com.niki914.zafiro.mod.feat

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import com.niki914.zafiro.accessibility.driver.SystemAccessibilityDriver
import com.niki914.zafiro.app.screen.AppScreens
import com.niki914.zafiro.app.screen.PhonePointer
import com.niki914.zafiro.app.screen.VirtualDisplayCard

class ZafiroAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        SystemAccessibilityDriver.attach(this)
        PhonePointer.attach(this)
        AppScreens.restoreCard()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 无状态驱动，不需要在每次系统 UI 事件时做全局标记
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onDestroy() {
        VirtualDisplayCard.hideOnMain()
        PhonePointer.detach()
        SystemAccessibilityDriver.detach()
        super.onDestroy()
    }
}
