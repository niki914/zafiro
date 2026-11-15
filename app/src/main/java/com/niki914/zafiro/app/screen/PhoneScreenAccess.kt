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
 * 屏幕控制、截图、应用启动与虚拟屏创建之前的知情同意与权限门。
 *
 * 无障碍驱动保持无状态，不认识同意书，也不申请权限。
 *
 * 门禁策略：
 * 1. 知情同意 [ensureConsent]：未获得 Phone Use 知情同意时，任何动作前置向用户弹窗审批。
 * 2. 权限与就绪：
 *    - [Need.Control]：读、点需要无障碍与悬浮窗权限，并等待驱动连接；
 *    - [Need.Capture]：截图需要无障碍权限，并等待驱动连接；
 *    - [Need.VirtualDisplay]：创建虚拟屏需要无障碍与悬浮窗权限，并等待驱动连接；
 *    - [Need.OpenApp]：应用拉起仅需知情同意。
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
    enum class Need { Control, Capture, OpenApp, VirtualDisplay }

    open suspend fun prepare(need: Need): PhoneScreenDenial? {
        ensureConsent()?.let { return it }
        if (need == Need.OpenApp) return null
        if (connected()) return null

        if (need == Need.Control || need == Need.VirtualDisplay) {
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
