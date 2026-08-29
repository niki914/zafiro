package com.niki914.zafiro.chat.agentic.device

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class AppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,

    /**
     * 多语言标签全集（系统默认 + zh-CN/zh-TW/en/ja/es），匹配层用；
     * appName 仍是系统语言标签，用于展示与排序。
     */
    val labels: Set<String> = setOf(appName),
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

        val exactMatches = apps.filter { it.matches { label -> label == query } }
        if (exactMatches.isNotEmpty()) {
            return when (exactMatches.size) {
                1 -> AppMatchResult.Found(exactMatches.first())
                else -> AppMatchResult.Candidates(exactMatches)
            }
        }

        val prefixMatches = apps.filter { it.matches { label -> label.startsWith(query) } }
        if (prefixMatches.isNotEmpty()) {
            return when (prefixMatches.size) {
                1 -> AppMatchResult.Found(prefixMatches.first())
                else -> AppMatchResult.Candidates(prefixMatches)
            }
        }

        // 仅包含命中属于弱匹配：即使唯一也返回候选，由模型裁决，避免“微信”误启动“微信输入法”类产品族子串。
        val candidates = apps.filter { it.matches { label -> label.contains(query) } }
        return when (candidates.size) {
            0 -> AppMatchResult.NotFound
            else -> AppMatchResult.Candidates(candidates)
        }
    }

    private fun AppInfo.matches(predicate: (String) -> Boolean): Boolean {
        return labels.any { predicate(it.lowercase()) }
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
                it.labels.any { label -> label.lowercase().contains(normalizedQuery) } ||
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
                labels = loadLabels(appInfo),
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** 按固定语言槽位加载标签；无对应语言资源时回退该应用默认语言的字符串，去重后入集。 */
    private fun loadLabels(appInfo: ApplicationInfo): Set<String> {
        val labels = LinkedHashSet<String>()
        appInfo.nonLocalizedLabel?.let { labels += it.toString() }
        if (appInfo.labelRes == 0) {
            return labels
        }
        val pkgContext = try {
            appContext.createPackageContext(appInfo.packageName, Context.CONTEXT_IGNORE_SECURITY)
        } catch (_: Throwable) {
            return labels
        }
        LABEL_LOCALES.distinctBy { it.toLanguageTag() }.forEach { locale ->
            try {
                val config = Configuration().apply { setLocale(locale) }
                labels += pkgContext.createConfigurationContext(config).getString(appInfo.labelRes)
            } catch (_: Throwable) {
                // 单个语言资源异常不影响其余槽位
            }
        }
        return labels
    }

    companion object {
        private const val MAX_SEARCH_LIMIT = 20

        // 中文（简/繁）、英文、日语、西语覆盖主流应用命名语言；系统默认语言始终参与。
        private val LABEL_LOCALES = listOf(
            Locale.getDefault(),
            Locale.SIMPLIFIED_CHINESE,
            Locale.TRADITIONAL_CHINESE,
            Locale.ENGLISH,
            Locale.JAPAN,
            Locale("es"),
        )
    }
}
