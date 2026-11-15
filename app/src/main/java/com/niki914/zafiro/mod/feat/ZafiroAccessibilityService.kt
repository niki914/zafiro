package com.niki914.zafiro.mod.feat

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.view.Display
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
        val type = event?.eventType ?: return
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) {
            val displayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                event.displayId
            } else {
                Display.DEFAULT_DISPLAY
            }
            SystemAccessibilityDriver.recordUiEvent(displayId)
        }
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
