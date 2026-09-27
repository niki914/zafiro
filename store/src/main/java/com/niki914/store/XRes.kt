package com.niki914.store

import android.content.Context

// TODO: Once ServiceManager lands, expose its unified package-name API from app; remove this module BuildConfig.

enum class HostApp(val packageName: String, val displayNameRes: Int) {
    Breeno("com.heytap.speechassist", R.string.host_breeno_display_name),
    XiaoAi("com.miui.voiceassist", R.string.host_xiaoai_display_name);

    companion object {
        fun fromPackageName(packageName: String?): HostApp? {
            return entries.firstOrNull { it.packageName == packageName }
        }

        val packageNames: List<String>
            get() = entries.map(HostApp::packageName)
    }
}

fun Context.displayNameFor(host: HostApp): String = getString(host.displayNameRes)

object XValues {

    val appList: List<String>
        get() = HostApp.packageNames

    enum class AppType { Me, Host, Unknown }

    fun getAppTypeOf(context: Context): AppType = when {
        context.packageName in appList -> AppType.Host
        context.packageName == BuildConfig.APPLICATION_ID -> AppType.Me
        else -> AppType.Unknown
    }
}

object IpcContract {
    enum class Method(val wireName: String) {
        GET_STORE("get_store"),
        MUTATE_STORE("mutate_store"),
        POST_NOTIFICATION("post_notification");
    }
}
