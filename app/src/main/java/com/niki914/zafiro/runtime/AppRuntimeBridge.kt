package com.niki914.zafiro.runtime

import com.niki914.zafiro.repo.XRepoRuntimeGateway
import com.niki914.zafiro.settings.RuntimeBridge

fun createAppRuntimeBridge(): RuntimeBridge {
    return RuntimeBridge(
        settings = XRepoRuntimeGateway(),
        host = IpcRuntimeHostGateway(),
    )
}
