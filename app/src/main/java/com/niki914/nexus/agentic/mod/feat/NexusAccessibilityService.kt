package com.niki914.nexus.agentic.mod.feat

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.niki914.nexus.agentic.chat.agentic.accessibility.AccessibilityController
import com.niki914.nexus.agentic.chat.agentic.accessibility.IAccessibility

class NexusAccessibilityService : AccessibilityService(), IAccessibility {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityController.setService(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // no-op
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onDestroy() {
        AccessibilityController.clearService()
        super.onDestroy()
    }

    // -- IAccessibility implementation --

    override val windowRoot: AccessibilityNodeInfo?
        get() = rootInActiveWindow

    override fun performAction(
        node: AccessibilityNodeInfo,
        action: Int,
        text: String?,
    ): Boolean {
        return if (action == AccessibilityNodeInfo.ACTION_SET_TEXT && text != null) {
            val bundle = Bundle()
            bundle.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
            node.performAction(action, bundle)
        } else {
            node.performAction(action)
        }
    }

    override fun dispatchGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long,
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return super.dispatchGesture(gesture, null, null)
    }
}
