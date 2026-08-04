package com.niki914.kai.net

sealed interface HttpFrame {
    data class Text(val value: String) : HttpFrame
    data class SseData(val value: String, val event: String?) : HttpFrame
}
