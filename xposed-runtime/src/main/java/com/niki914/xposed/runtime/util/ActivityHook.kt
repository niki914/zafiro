package com.niki914.xposed.runtime.util

import android.app.Activity
import android.os.Bundle
import com.niki914.logging.Logger
import com.niki914.xposed.runtime.runtime.Hook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class ActivityHook : Hook {
    override val name: String = "ActivityHook"

    private companion object {
        const val LOG_TAG = "niki914_nexus_ActivityHook"
    }

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        fun log(activity: Activity, lifecycle: String, useFullName: Boolean = false) {
            val name = if (useFullName) activity.javaClass.name else activity.javaClass.simpleName
            val msg = "[$name] ${name} $lifecycle"
            Logger.d(LOG_TAG, msg)
            XposedBridge.log(msg)
        }

        lpparam.hookMethod(
            "android.app.Activity", "onCreate", Bundle::class.java,
            before = { log(it.thisObject as Activity, "onCreate", true) }
        )
        lpparam.hookMethod(
            "android.app.Activity", "onStart",
            before = { log(it.thisObject as Activity, "onStart") }
        )
        lpparam.hookMethod(
            "android.app.Activity", "onResume",
            before = { log(it.thisObject as Activity, "onResume") }
        )
        lpparam.hookMethod(
            "android.app.Activity", "onPause",
            before = { log(it.thisObject as Activity, "onPause") }
        )
        lpparam.hookMethod(
            "android.app.Activity", "onStop",
            before = { log(it.thisObject as Activity, "onStop") }
        )
        lpparam.hookMethod(
            "android.app.Activity", "onDestroy",
            before = { log(it.thisObject as Activity, "onDestroy") }
        )
        lpparam.hookMethod(
            "android.app.Activity", "onRestart",
            before = { log(it.thisObject as Activity, "onRestart") }
        )
    }
}
