package com.niki914.zafiro.app

import android.content.Context
import android.content.Intent
import android.os.Process

/**
 * 先让系统记下重新打开本应用，再杀掉当前进程。
 */
internal object AppRelaunch {
    fun schedule(context: Context) {
        val component = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.component
            ?: return
        context.startActivity(Intent.makeRestartActivityTask(component))
        Process.killProcess(Process.myPid())
    }
}
