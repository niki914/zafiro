package com.niki914.zafiro.envcontext

data class DeviceStateSnapshot(
    val model: String = "",
    val battery: String = "",
    val networkType: String = "",
) {
    fun format(): String {
        val parts = mutableListOf<String>()
        if (model.isNotBlank()) parts += model
        if (battery.isNotBlank()) parts += battery
        if (networkType.isNotBlank()) parts += networkType
        return parts.joinToString(" · ")
    }
}
