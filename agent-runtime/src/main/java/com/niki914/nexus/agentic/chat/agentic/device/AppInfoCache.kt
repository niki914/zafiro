package com.niki914.nexus.agentic.chat.agentic.device

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class AppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
)

sealed interface AppMatchResult {
    data class Found(val app: AppInfo) : AppMatchResult
    data class Candidates(val apps: List<AppInfo>) : AppMatchResult
    data object NotFound : AppMatchResult
}

object AppInfoMatcher {
    fun matchByName(
        apps: List<AppInfo>,
        appName: String,
    ): AppMatchResult {
        val query = appName.trim().lowercase()
        if (query.isBlank()) {
            return AppMatchResult.NotFound
        }

        val exactMatches = apps.filter { it.appName.lowercase() == query }
        if (exactMatches.isNotEmpty()) {
            return when (exactMatches.size) {
                1 -> AppMatchResult.Found(exactMatches.first())
                else -> AppMatchResult.Candidates(exactMatches)
            }
        }

        val candidates = apps.filter { it.appName.lowercase().contains(query) }
        return when (candidates.size) {
            0 -> AppMatchResult.NotFound
            1 -> AppMatchResult.Found(candidates.first())
            else -> AppMatchResult.Candidates(candidates)
        }
    }
}

class AppInfoCache(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val initMutex = Mutex()
    private val appsByPackageName = ConcurrentHashMap<String, AppInfo>()

    @Volatile
    private var initialized = false

    suspend fun findByPackageName(packageName: String): AppInfo? {
        ensureInitialized()
        return appsByPackageName[packageName.trim()]
    }

    suspend fun findByAppName(appName: String): AppMatchResult {
        ensureInitialized()
        return AppInfoMatcher.matchByName(appsByPackageName.values.toList(), appName)
    }

    suspend fun search(
        query: String,
        includeSystem: Boolean,
        limit: Int,
    ): List<AppInfo> {
        ensureInitialized()
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isBlank()) {
            return emptyList()
        }
        return appsByPackageName.values
            .asSequence()
            .filter { includeSystem || !it.isSystemApp }
            .filter {
                it.appName.lowercase().contains(normalizedQuery) ||
                        it.packageName.lowercase().contains(normalizedQuery)
            }
            .sortedWith(compareBy<AppInfo> { it.isSystemApp }.thenBy { it.appName.lowercase() })
            .take(limit.coerceIn(1, MAX_SEARCH_LIMIT))
            .toList()
    }

    suspend fun refresh() {
        initMutex.withLock {
            loadInstalledApps()
            initialized = true
        }
    }

    private suspend fun ensureInitialized() {
        if (initialized) {
            return
        }
        initMutex.withLock {
            if (!initialized) {
                loadInstalledApps()
                initialized = true
            }
        }
    }

    private suspend fun loadInstalledApps() {
        val apps = withContext(Dispatchers.IO) {
            val packageManager = appContext.packageManager
            queryLauncherActivities(packageManager).mapNotNull { resolveInfo ->
                resolveInfo.toAppInfo(packageManager)
            }
        }
        appsByPackageName.clear()
        apps.forEach { appsByPackageName[it.packageName] = it }
    }

    private fun queryLauncherActivities(packageManager: PackageManager): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
    }

    private fun ResolveInfo.toAppInfo(packageManager: PackageManager): AppInfo? {
        return try {
            val appInfo = activityInfo?.applicationInfo ?: return null
            AppInfo(
                packageName = appInfo.packageName,
                appName = loadLabel(packageManager).toString(),
                isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            )
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val MAX_SEARCH_LIMIT = 20
    }
}
