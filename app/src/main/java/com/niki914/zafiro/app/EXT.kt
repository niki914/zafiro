package com.niki914.zafiro.app

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.niki914.store.HostApp
import com.niki914.store.XValues
import com.niki914.xposed.api.util.OsFamily
import com.niki914.xposed.api.util.OsUtils
import com.niki914.zafiro.app.ui.model.StartupAssistantUi

data class InstalledPackageVersion(
    val versionName: String?,
    val versionCode: Long,
)

fun Context.getInstalledPackageVersion(packageName: String): InstalledPackageVersion? {
    return try {
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }
        InstalledPackageVersion(versionName = pi.versionName, versionCode = versionCode)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}

fun preferredHostAppFor(osFamily: OsFamily): HostApp? {
    return when (osFamily) {
        OsFamily.ColorOS -> HostApp.Breeno
        OsFamily.HyperOS -> HostApp.XiaoAi
        OsFamily.Unknown -> null
    }
}

fun Activity.resolveTargetHostPackage(osFamily: OsFamily): String? {
    val preferredPkg = preferredHostAppFor(osFamily)?.packageName
    val candidatePkgs = buildList {
        preferredPkg?.let { add(it) }
        addAll(XValues.appList.filter { it != preferredPkg })
    }
    return candidatePkgs.firstOrNull { pkg ->
        getInstalledPackageVersion(pkg) != null
    }
}

fun Activity.resolveStartupAssistantUi(): StartupAssistantUi {
    return StartupAssistantUi.fromOsFamily(OsUtils.getCurr())
}
