package com.niki914.zafiro.chat.agentic.buildin.impl

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult

/** Locks the screen. Needs Zafiro to be a device administrator (Settings > General > Device administrator). Off by default. */
class LockScreenBuiltin : BuiltinTool() {
    override val name: String = "lock_screen"
    override val description: String = "Lock the phone screen now. Only works when the owner made Zafiro a device administrator."
    override val defaultEnabled: Boolean = false
    override val inputSchemaJson: String = """{"type":"object","properties":{}}"""

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val context = ContextProvider.await().applicationContext
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return BuiltinToolResult.failure(code = "UNAVAILABLE", message = "Device policy service is not available.")
        val admin = ComponentName(context.packageName, ADMIN_RECEIVER_CLASS)
        if (!dpm.isAdminActive(admin)) {
            return BuiltinToolResult.failure(
                code = "NOT_ADMIN", message = "Zafiro is not a device administrator.",
                hint = "Ask the owner to enable Settings > General > Device administrator.",
            )
        }
        return runCatching { dpm.lockNow() }.fold(
            onSuccess = { BuiltinToolResult.success(message = "Screen locked.") },
            onFailure = { BuiltinToolResult.failure(code = "LOCK_FAILED", message = it.message ?: "Could not lock the screen.") },
        )
    }

    companion object {
        const val ADMIN_RECEIVER_CLASS = "com.niki914.zafiro.app.admin.ZafiroDeviceAdminReceiver"
    }
}
