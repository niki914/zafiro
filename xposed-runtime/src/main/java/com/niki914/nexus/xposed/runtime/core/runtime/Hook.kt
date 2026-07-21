package com.niki914.nexus.xposed.runtime.core.runtime

import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge

/**
 * A unit of hook logic executed by `Runtime` during package load.
 * 在包加载期间由 `Runtime` 执行的一段 hook 逻辑单元。
 */
interface Hook {
    val name: String

    val useDexkit: Boolean
        get() = false

    fun onHook(
        lpparam: XC_LoadPackage.LoadPackageParam
    ) = Unit

    fun onHookWithDexkit(
        lpparam: XC_LoadPackage.LoadPackageParam,
        bridge: DexKitBridge
    ) = Unit
}
