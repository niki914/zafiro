package com.niki914.nexus.xposed.runtime.util

import com.niki914.nexus.xposed.runtime.core.runtime.Hook
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.CoroutineScope

object HookSideLoader {

    fun load(
        scope: CoroutineScope,
        hook: Hook,
        lpparam: XC_LoadPackage.LoadPackageParam
    ) {
        hook.onHook(lpparam)
    }
}