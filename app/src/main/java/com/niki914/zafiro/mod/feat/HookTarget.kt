package com.niki914.zafiro.mod.feat

data class HookTarget(
    val ownerClass: String,
    val methodName: String,
    val methodParams: List<String>,
    val hookTiming: String? = null,
    val returnType: String? = null
)
