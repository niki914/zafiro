package com.niki914.shizuku.privileged

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.annotation.RequiresApi
import com.niki914.shizuku.internal.runCatchingCancellable

/**
 * 特权子进程身份伪装：将当前 Context 伪装为 com.android.shell (UID 2000)。
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class ShellContext private constructor(base: Context) : ContextWrapper(base) {
    override fun getPackageName(): String = SHELL_PACKAGE
    override fun getOpPackageName(): String = SHELL_PACKAGE

    @RequiresApi(Build.VERSION_CODES.S)
    override fun getAttributionSource(): AttributionSource =
        AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()

    override fun getApplicationContext(): Context = this

    companion object {
        const val SHELL_PACKAGE = "com.android.shell"
        const val SHELL_UID = 2000

        fun create(): Context {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val thread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
                ?: createActivityThread(activityThreadClass)
            val system = activityThreadClass.getDeclaredMethod("getSystemContext").invoke(thread) as Context
            val context = ShellContext(system)
            val initialApp = activityThreadClass.getDeclaredField("mInitialApplication").apply {
                isAccessible = true
            }
            if (initialApp.get(thread) == null) {
                runCatchingCancellable {
                    val app = Instrumentation.newApplication(Application::class.java, context)
                    initialApp.set(thread, app)
                }
            }
            return context
        }

        private fun createActivityThread(activityThreadClass: Class<*>): Any {
            val thread = activityThreadClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            activityThreadClass.getDeclaredField("sCurrentActivityThread").apply {
                isAccessible = true
            }.set(null, thread)
            activityThreadClass.getDeclaredField("mSystemThread").apply {
                isAccessible = true
            }.setBoolean(thread, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatchingCancellable {
                    val controllerClass = Class.forName("android.app.ConfigurationController")
                    val internalClass = Class.forName("android.app.ActivityThreadInternal")
                    val controller = controllerClass.getDeclaredConstructor(internalClass)
                        .apply { isAccessible = true }
                        .newInstance(thread)
                    activityThreadClass.getDeclaredField("mConfigurationController").apply {
                        isAccessible = true
                    }.set(thread, controller)
                }
            }
            runCatchingCancellable {
                val bindDataClass = Class.forName("android.app.ActivityThread\$AppBindData")
                val bindData = bindDataClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                bindDataClass.getDeclaredField("appInfo").apply { isAccessible = true }
                    .set(bindData, ApplicationInfo().apply { packageName = SHELL_PACKAGE })
                activityThreadClass.getDeclaredField("mBoundApplication").apply { isAccessible = true }
                    .set(thread, bindData)
            }
            return thread
        }
    }
}
