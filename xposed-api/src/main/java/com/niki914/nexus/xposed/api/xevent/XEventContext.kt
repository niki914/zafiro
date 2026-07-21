package com.niki914.nexus.xposed.api.xevent

data class XEventContext(
    val roomId: String? = null,
    val turnId: Long? = null,
    val fields: Map<String, Any?> = emptyMap()
)
