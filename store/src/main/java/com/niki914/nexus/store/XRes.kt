package com.niki914.nexus.store

import android.content.Context

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

    val myPackageName = "com.niki914.nexus.agentic"
    val appList: List<String>
        get() = HostApp.packageNames

    enum class AppType { Me, Host, Unknown }

    fun getAppTypeOf(context: Context): AppType {
        if (context.packageName in appList) return AppType.Host
        if (context.packageName == myPackageName) return AppType.Me
        return AppType.Unknown
    }
}

object IpcContract {
    enum class Method(val wireName: String) {
        GET_STORE("get_store"),
        MUTATE_STORE("mutate_store"),
        POST_NOTIFICATION("post_notification");
    }
}
