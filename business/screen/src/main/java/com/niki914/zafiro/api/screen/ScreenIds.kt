package com.niki914.zafiro.api.screen

@JvmInline
value class ScreenId(val value: String)

enum class ScreenKind { Phone, VirtualDisplay, Browser }

enum class OpenKind { VirtualDisplay, Browser }

data class ScreenInfo(
    val id: ScreenId,
    val kind: ScreenKind,
    val width: Int,
    val height: Int,
)

val PhoneScreenId: ScreenId = ScreenId("phone")
