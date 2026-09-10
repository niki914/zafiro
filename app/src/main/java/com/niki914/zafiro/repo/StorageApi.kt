package com.niki914.zafiro.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class StorageKind {
    ToolOutput,
    ImageCache,
    Downloads,
    OtherCache,
}

data class StorageUsage(
    val kind: StorageKind,
    val bytes: Long,
)

class StorageApi internal constructor(
    private val repo: XRepo,
) {
    suspend fun usage(): List<StorageUsage> {
        return withContext(Dispatchers.IO) {
            val context = repo.context()
            StorageKind.entries.map { kind ->
                StorageUsage(kind, dirSize(resolveDir(context, kind)))
            }
        }
    }

    suspend fun clear(kind: StorageKind) {
        withContext(Dispatchers.IO) {
            val dir = resolveDir(repo.context(), kind)
            if (!dir.exists()) return@withContext
            dir.listFiles()?.forEach { child ->
                runCatching {
                    if (child.isDirectory) child.deleteRecursively() else child.delete()
                }
            }
        }
    }

    private fun resolveDir(context: android.content.Context, kind: StorageKind): File {
        return when (kind) {
            StorageKind.ToolOutput -> File(context.filesDir, TOOL_OUTPUT_DIR_NAME)
            StorageKind.ImageCache -> File(context.filesDir, IMAGE_CACHE_DIR_NAME)
            StorageKind.Downloads -> File(context.filesDir, DOWNLOADS_DIR_NAME)
            StorageKind.OtherCache -> context.cacheDir
        }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }

    private companion object {
        const val TOOL_OUTPUT_DIR_NAME = "tool_output"
        const val IMAGE_CACHE_DIR_NAME = "image_cache"
        const val DOWNLOADS_DIR_NAME = "downloads"
    }
}
