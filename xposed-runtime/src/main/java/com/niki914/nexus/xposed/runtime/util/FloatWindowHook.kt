package com.niki914.nexus.xposed.runtime.util

import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import com.niki914.logging.Logger
import com.niki914.nexus.xposed.runtime.core.runtime.Hook
import de.robv.android.xposed.callbacks.XC_LoadPackage

class FloatWindowHook : Hook {
    override val name: String = "FloatWindowHook"

    private companion object {
        const val LOG_TAG = "niki914_nexus_FloatWindow"
    }

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        fun log(obj: Any, lifecycle: String) {
            val name = obj.javaClass.name
            Logger.d(LOG_TAG, "[FloatWindow] $name $lifecycle")
        }

        // 1. Hook WindowManagerImpl (精准捕获所有添加到 WindowManager 的根视图/悬浮窗)
        lpparam.hookMethod(
            "android.view.WindowManagerImpl", "addView",
            View::class.java, ViewGroup.LayoutParams::class.java,
            before = {
                val view = it.args[0] as? View ?: return@hookMethod
                val params = it.args[1] as? android.view.WindowManager.LayoutParams
                val typeInfo = params?.let { p -> " type=${p.type}" } ?: ""
                log(view, "addView (WindowManager)$typeInfo")
            }
        )

        lpparam.hookMethod(
            "android.view.WindowManagerImpl", "removeView",
            View::class.java,
            before = {
                val view = it.args[0] as? View ?: return@hookMethod
                log(view, "removeView (WindowManager)")
            }
        )

        // 2. Hook Dialog (语音助手常使用 Dialog 承载面板)
        lpparam.hookMethod(
            "android.app.Dialog", "show",
            before = {
                val dialog = it.thisObject as? Dialog ?: return@hookMethod
                log(dialog, "show (Dialog)")
            }
        )

        lpparam.hookMethod(
            "android.app.Dialog", "dismiss",
            before = {
                val dialog = it.thisObject as? Dialog ?: return@hookMethod
                log(dialog, "dismiss (Dialog)")
            }
        )
    }
}