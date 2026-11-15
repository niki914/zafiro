package com.niki914.zafiro.app.env

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import com.niki914.zafiro.envcontext.DeviceStateProvider
import com.niki914.zafiro.envcontext.DeviceStateSnapshot

class AndroidDeviceStateProvider(
    private val application: Application,
) : DeviceStateProvider {

    override fun getDeviceState(): DeviceStateSnapshot {
        val model = Build.MODEL.orEmpty()
        val battery = resolveBattery()
        val networkType = resolveNetworkType()
        return DeviceStateSnapshot(
            model = model,
            battery = battery,
            networkType = networkType,
        )
    }

    private fun resolveBattery(): String {
        return try {
            val bm = application.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val capacity = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val level = if (capacity != null && capacity in 0..100) {
                capacity
            } else {
                val intent = application.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val rawLevel = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (rawLevel >= 0 && scale > 0) {
                    rawLevel * 100 / scale
                } else {
                    null
                }
            }
            if (level != null) "Battery: $level%" else ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun resolveNetworkType(): String {
        return try {
            val cm = application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return ""
            val activeNetwork = cm.activeNetwork ?: return "Offline"
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return "Offline"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "Online"
            }
        } catch (_: Throwable) {
            ""
        }
    }
}
