package com.niki914.zafiro.envcontext

fun interface DeviceStateProvider {
    fun getDeviceState(): DeviceStateSnapshot
}
