package com.niki914.zafiro.business.files

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/**
 * SAF 的 `content://` uri → agent 能按路径读的真实路径。
 *
 * ## 支持的来源
 *
 * 1. **ExternalStorageProvider (`primary:`)**：内置主存储卷，直接映射 `/storage/emulated/0/...`。
 * 2. **ExternalStorageProvider (`<UUID>:`)**：外置 SD 卡 / U 盘卷，映射 `/storage/<UUID>/...`。
 * 3. **DownloadsProvider (`msf:<id>`)**：Android 10+ 下载项，通过 ContentResolver 查询 MediaStore 真实路径。
 * 4. **MediaProvider (`image:`, `video:`, `audio:`, `document:`)**：通过 ContentResolver 查询 MediaStore 真实路径。
 * 5. **`raw:<绝对路径>`**：provider 把真实位置写进 docId 的形态。
 *
 * 云盘等无本地物理路径的 provider 返回 null（由上层提示用户）。
 */
internal object DocumentPathResolver {

    /** 系统 ExternalStorageProvider 的 authority。 */
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"
    private const val MEDIA_AUTHORITY = "com.android.providers.media.documents"

    private val PATH_AUTHORITIES = setOf(
        EXTERNAL_STORAGE_AUTHORITY,
        DOWNLOADS_AUTHORITY,
        MEDIA_AUTHORITY,
    )

    private const val PRIMARY_VOLUME = "primary"
    private const val RAW_PREFIX = "raw:"
    private const val MSF_PREFIX = "msf:"
    private const val TREE_SEGMENT = "tree"

    fun resolve(uri: Uri, contentResolver: ContentResolver? = null): String? {
        val authority = uri.authority
        val docId = uri.lastPathSegment
        val isTree = uri.pathSegments.firstOrNull() == TREE_SEGMENT

        if (authority !in PATH_AUTHORITIES || docId == null) {
            return null
        }

        // 1. raw: 绝对路径前缀（provider 主动给出）
        if (docId.startsWith(RAW_PREFIX)) {
            return rawPath(docId, isTree)
        }

        // 2. Android 10+ 下载项 (msf:1000316915)
        if (authority == DOWNLOADS_AUTHORITY && docId.startsWith(MSF_PREFIX)) {
            val mediaId = docId.removePrefix(MSF_PREFIX).toLongOrNull()
            if (mediaId != null && contentResolver != null) {
                val path = queryMediaStoreData(contentResolver, MediaStore.Files.getContentUri("external"), mediaId)
                if (path != null) {
                    return if (isTree) path.trimEnd('/') + "/" else path
                }
            }
        }

        // 3. 媒体库 (image:123, video:123, audio:123, document:123)
        if (authority == MEDIA_AUTHORITY) {
            val type = docId.substringBefore(':', missingDelimiterValue = "")
            val mediaId = docId.substringAfter(':', missingDelimiterValue = "").toLongOrNull()
            if (mediaId != null && contentResolver != null) {
                val baseUri = when (type) {
                    "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Files.getContentUri("external")
                }
                val path = queryMediaStoreData(contentResolver, baseUri, mediaId)
                if (path != null) {
                    return if (isTree) path.trimEnd('/') + "/" else path
                }
            }
        }

        // 4. 下载模块纯数字 ID
        if (authority == DOWNLOADS_AUTHORITY) {
            val downloadId = docId.toLongOrNull()
            if (downloadId != null && contentResolver != null) {
                val path = queryMediaStoreData(contentResolver, MediaStore.Files.getContentUri("external"), downloadId)
                    ?: runCatching {
                        val legacyUri = ContentUris.withAppendedId(
                            Uri.parse("content://downloads/public_downloads"),
                            downloadId,
                        )
                        queryDataColumn(contentResolver, legacyUri)
                    }.getOrNull()
                if (path != null) {
                    return if (isTree) path.trimEnd('/') + "/" else path
                }
            }
        }

        // 5. 外部存储卷：primary:path 或 <UUID>:path (外置 SD 卡)
        val root = Environment.getExternalStorageDirectory()?.path
        return toPath(docId = docId, root = root, isTree = isTree)
    }

    internal fun toPath(docId: String, root: String?, isTree: Boolean): String? {
        if (docId.startsWith(RAW_PREFIX)) return rawPath(docId, isTree)
        val volume = docId.substringBefore(':', missingDelimiterValue = "")
        val relative = docId.substringAfter(':', missingDelimiterValue = "").trim('/')

        val basePath = when {
            volume == PRIMARY_VOLUME && root != null -> {
                if (relative.isEmpty()) root else "$root/$relative"
            }
            volume.isNotEmpty() && volume != PRIMARY_VOLUME && volume.matches(UUID_PATTERN) -> {
                // 外置 SD 卡 / U 盘卷：Android 恒定挂载在 /storage/<UUID>
                val sdRoot = "/storage/$volume"
                if (relative.isEmpty()) sdRoot else "$sdRoot/$relative"
            }
            else -> null
        } ?: return null

        return if (isTree) basePath.trimEnd('/') + "/" else basePath
    }

    private fun rawPath(docId: String, isTree: Boolean): String? {
        val path = docId.removePrefix(RAW_PREFIX).trimEnd('/')
        if (!path.startsWith("/") || path == "/") return null
        return if (isTree) "$path/" else path
    }

    private fun queryMediaStoreData(
        contentResolver: ContentResolver,
        baseUri: Uri,
        id: Long,
    ): String? = runCatching {
        val uri = ContentUris.withAppendedId(baseUri, id)
        queryDataColumn(contentResolver, uri)
    }.getOrNull()

    private fun queryDataColumn(contentResolver: ContentResolver, uri: Uri): String? = runCatching {
        contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DATA),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (idx != -1) cursor.getString(idx)?.takeIf { it.isNotEmpty() } else null
            } else null
        }
    }.getOrNull()

    /** 标准外置 SD 卡 UUID 格式（如 1234-5678 或 36 位 UUID）。 */
    private val UUID_PATTERN = Regex("^[0-9a-fA-F]{4}-[0-9a-fA-F]{4}$|^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
}
