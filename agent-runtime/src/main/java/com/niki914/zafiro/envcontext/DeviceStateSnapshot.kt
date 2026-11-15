package com.niki914.zafiro.envcontext

data class DeviceStateSnapshot(
    val model: String = "",
    val batteryLevel: Int? = null,
    val networkType: String = "",
) {
    fun format(): String {
        val parts = mutableListOf<String>()
        if (model.isNotBlank()) parts += model
        if (batteryLevel != null) parts += "$batteryLevel%"
        if (networkType.isNotBlank()) parts += networkType
        return parts.joinToString(" · ")
    }
}
