package com.niki914.zafiro.app.screen

import com.niki914.logging.Logger
import com.niki914.zafiro.api.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 虚拟屏最近启动应用列表的本地持久化存储（独立沙箱文件，不参与应用备份）。
 * 按最近使用倒序去重，最多保留 [MAX_RECENT_APPS] 条包名记录。
 */
internal object RecentVirtualDisplayAppsStore {
    private const val TAG = "niki914_zafiro_RecentVdApps"
    private const val FILE_NAME = "virtual_display_recent_apps.json"
    private const val MAX_RECENT_APPS = 20

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val _recentPackages = MutableStateFlow<List<String>>(emptyList())
    val recentPackages: StateFlow<List<String>> = _recentPackages.asStateFlow()

    @Volatile
    private var loaded = false

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            val persisted = readFromDisk()
            _recentPackages.value = persisted
            loaded = true
        }
    }

    suspend fun record(packageName: String) {
        val normalized = packageName.trim()
        if (normalized.isEmpty()) return
        mutex.withLock {
            if (!loaded) {
                _recentPackages.value = readFromDisk()
                loaded = true
            }
            val updated = buildList {
                add(normalized)
                _recentPackages.value.forEach { existing ->
                    if (existing != normalized && size < MAX_RECENT_APPS) {
                        add(existing)
                    }
                }
            }
            if (updated == _recentPackages.value) return
            _recentPackages.value = updated
            writeToDisk(updated)
        }
    }

    private suspend fun readFromDisk(): List<String> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val file = storageFile()
            if (!file.exists()) return@withContext emptyList()
            val raw = file.readText().trim()
            if (raw.isEmpty()) return@withContext emptyList()
            json.decodeFromString<List<String>>(raw)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .take(MAX_RECENT_APPS)
        }.onFailure { error ->
            Logger.w(TAG, "read recent virtual display apps failed: ${error.message}")
        }.getOrDefault(emptyList())
    }

    private suspend fun writeToDisk(packages: List<String>) = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val file = storageFile()
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(packages))
        }.onFailure { error ->
            Logger.w(TAG, "write recent virtual display apps failed: ${error.message}")
        }
    }

    private fun storageFile(): File = File(applicationContext().filesDir, FILE_NAME)
}
