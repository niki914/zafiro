package com.niki914.zafiro.app.screen

import com.niki914.xsettings.XSettings
import com.niki914.zafiro.accessibility.driver.SystemAccessibilityDriver
import com.niki914.zafiro.api.AgentControl
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.service.findService
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.delay

/**
 * 主屏读、点、截图之前的权限门。
 *
 * 无障碍驱动保持无状态，不认识同意书，也不申请权限。
 * 打开应用不经过这里。
 *
 * 读和点走 [Need.Control]：缺无障碍或悬浮窗时先问一次知情同意，再逐个向
 * [PermissionManager] 申请。截图走 [Need.Capture]：只要无障碍，不问同意、不要悬浮窗。
 */
open class PhoneScreenAccess(
    private val permissions: () -> PermissionManager = { requireService() },
    private val settings: () -> XSettings? = { findService() },
    private val askConsent: suspend () -> ApprovalDecision = {
        findService<AgentControl>()?.decideApproval(ApprovalRequest.ScreenControlConsent)
            ?: ApprovalDecision.Deny
    },
    private val connected: () -> Boolean = { SystemAccessibilityDriver.isConnected },
    private val waitUntilConnected: suspend () -> Boolean = { waitForDriverConnected(connected) },
) {
    enum class Need { Control, Capture }

    open suspend fun prepare(need: Need): PhoneScreenDenial? {
        if (connected()) return null
        if (need == Need.Control) {
            ensureConsent()?.let { return it }
            ensureOne(Permission.ACCESSIBILITY)
            ensureOne(Permission.OVERLAY)
        } else {
            ensureOne(Permission.ACCESSIBILITY)
        }
        if (waitUntilConnected()) return null
        if (permissions().status(Permission.ACCESSIBILITY) != PermissionState.GRANTED) {
            return PhoneScreenDenial(
                code = "ACCESSIBILITY_UNAVAILABLE",
                message = "The Zafiro accessibility service is not available. " +
                    "Enable Zafiro in Settings > Accessibility, then retry.",
            )
        }
        return PhoneScreenDenial(
            code = "SERVICE_NOT_CONNECTED",
            message = "Accessibility is granted, but the service did not connect. Retry in a moment.",
        )
    }

    private suspend fun ensureConsent(): PhoneScreenDenial? {
        val manager = permissions()
        val missing = manager.status(Permission.ACCESSIBILITY) != PermissionState.GRANTED ||
            manager.status(Permission.OVERLAY) != PermissionState.GRANTED
        if (!missing) return null
        if (settings()?.phoneUseConsentGranted?.value == true) return null
        if (askConsent() != ApprovalDecision.Allow) {
            return PhoneScreenDenial(
                code = "CONSENT_DENIED",
                message = "User declined screen-control consent (accessibility + overlay). " +
                    "Tell the user these permissions are required for screen control; " +
                    "they can retry or grant them manually in Settings.",
            )
        }
        settings()?.setPhoneUseConsentGranted(true)
        return null
    }

    private suspend fun ensureOne(permission: Permission) {
        val manager = permissions()
        if (manager.status(permission) == PermissionState.GRANTED) return
        manager.request(permission)
    }
}

data class PhoneScreenDenial(
    val code: String,
    val message: String,
)

/** 授权后系统绑定无障碍服务是异步的。300ms 起步，最多再等 9 次。 */
internal suspend fun waitForDriverConnected(connected: () -> Boolean): Boolean {
    if (connected()) return true
    delay(300)
    repeat(9) {
        if (connected()) return true
        delay(300)
    }
    return connected()
}
