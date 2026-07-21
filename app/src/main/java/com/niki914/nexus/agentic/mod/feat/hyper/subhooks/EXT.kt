package com.niki914.nexus.agentic.mod.feat.hyper.subhooks

import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiConfigProvider
import com.niki914.nexus.xposed.runtime.util.call

fun injectedFlagKey(): String = XiaoaiConfigProvider.RenderTextStreamCard.injectedFlagKey

fun resolveDialogId(instruction: Any, target: Any?): String? {
    return resolveDialogId(
        instruction = instruction,
        target = target,
        instructionDialogIdGetter = XiaoaiConfigProvider.BlockNativeTextStream.instructionDialogIdGetter,
        optionalHasValueMethod = XiaoaiConfigProvider.BlockNativeTextStream.optionalHasValueMethod,
        optionalValueGetter = XiaoaiConfigProvider.BlockNativeTextStream.optionalValueGetter,
        targetDialogIdGetter = XiaoaiConfigProvider.BlockNativeTextStream.targetDialogIdGetter,
    )
}

fun resolveDialogId(
    instruction: Any,
    target: Any?,
    instructionDialogIdGetter: String,
    optionalHasValueMethod: String,
    optionalValueGetter: String,
    targetDialogIdGetter: String,
): String? {
    val dialogIdOptional = instruction.call<Any>(instructionDialogIdGetter)
    val dialogId = dialogIdOptional
        ?.takeIf { it.call<Boolean>(optionalHasValueMethod) == true }
        ?.call<String>(optionalValueGetter)
    if (!dialogId.isNullOrBlank()) {
        return dialogId
    }
    return target?.call<String>(targetDialogIdGetter)
}
