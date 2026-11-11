package com.niki914.zafiro.chat.agentic.buildin.impl

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId
import java.time.ZonedDateTime

/** Read-only phone facts (battery, network, storage, local time) so tasks can react to the state of the phone. */
class DeviceInfoBuiltin : BuiltinTool() {
    override val name: String = "device_info"
    override val description: String =
        "Read basic phone state: battery level and charging, network type, free storage, local date/time and time zone. Read-only."
    override val defaultEnabled: Boolean = true
    override val inputSchemaJson: String = """{"type":"object","properties":{}}"""

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val context = ContextProvider.await().applicationContext
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val battery = runCatching { battery(context) }.getOrNull()
        val network = runCatching { network(context) }.getOrDefault("unknown")
        val freeMb = runCatching { StatFs(Environment.getDataDirectory().path).availableBytes / (1024 * 1024) }.getOrNull()
        return BuiltinToolResult.success(
            message = "Device info read.",
            data = buildJsonObject {
                put("localTime", now.toString())
                put("timeZone", now.zone.id)
                battery?.let { (level, charging) ->
                    put("batteryPercent", level)
                    put("charging", charging)
                }
                put("network", network)
                freeMb?.let { put("freeStorageMb", it) }
            },
        )
    }

    private fun battery(context: Context): Pair<Int, Boolean>? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return (level * 100 / scale) to charging
    }

    private fun network(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "unknown"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return "offline") ?: return "offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }
}
