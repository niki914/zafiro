package com.niki914.nexus.agentic.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import com.niki914.nexus.agentic.app.ui.nexus.model.StartupAssistantUi
import com.niki914.nexus.store.HostApp
import com.niki914.nexus.store.XValues
import com.niki914.nexus.xposed.api.util.OsFamily
import com.niki914.nexus.xposed.api.util.OsUtils

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

object NotificationPermissionGate {
    private var launcher: ActivityResultLauncher<String>? = null

    fun init(launcher: ActivityResultLauncher<String>) {
        this.launcher = launcher
    }

    fun isGranted(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
    }

    /** Request the permission dialog. No-op if already granted or launcher not ready. */
    fun requestIfNeeded(context: Context) {
        if (isGranted(context)) return
        try {
            launcher?.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (_: Exception) {
            // Activity not in resumed state — skip, caller will retry next time
        }
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
