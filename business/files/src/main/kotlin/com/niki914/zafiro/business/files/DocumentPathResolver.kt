package com.niki914.zafiro.business.files

import android.net.Uri
import android.os.Environment

/**
 * SAF 的 `content://` uri → agent 能按路径读的真实路径。
 *
 * ## 只认「系统 ExternalStorageProvider + primary 卷」
 *
 * 其余一律返回 null，由调用方 toast 拒绝：
 * - **SD 卡 / U 盘卷**：docId 形如 `<UUID>:<相对路径>`，映射需要一张
 *   `volume.uuid → root` 表，而 `StorageVolume.getDirectory()` 是 30+，本次不做
 * - **云盘 provider**（Google Drive / OneDrive 等）：本来就没有本地路径
 * - **其它 provider**（MediaStore 等）：不进这条链
 *
 * ## 为什么必须有这一步
 *
 * `content://` 塞进提示词对 agent 毫无用处：它的 shell 跑在本应用 uid 下，读不了 content URI。
 * 而文件又**不拷贝**（D3），所以只能把路径解出来给它。
 *
 * TODO(SD 卡 / 云盘)：要支持非 primary 卷，就在这里按 `StorageManager.storageVolumes`
 *  建表；云盘没有路径可解，只能走「拷进沙箱」那条被 D3 否决的路。
 */
internal object DocumentPathResolver {

    /** 系统 ExternalStorageProvider 的 authority（SAF 文件选择器背后的 provider）。 */
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** 内置（主）存储卷的 docId 前缀。 */
    private const val PRIMARY_VOLUME = "primary"

    /** Android 侧入口：只看 uri，路径规则全在 [toPath]。 */
    fun resolve(uri: Uri): String? {
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        // 目录选择器（OpenDocumentTree）返回的是 tree uri，末尾补 `/` 表达目录（D7）
        val isTree = uri.pathSegments.firstOrNull() == TREE_SEGMENT
        val docId = uri.lastPathSegment ?: return null
        val root = Environment.getExternalStorageDirectory()?.path ?: return null
        return toPath(docId = docId, root = root, isTree = isTree)
    }

    /**
     * 纯字符串部分（单测覆盖这一层）：
     * `primary:adbi/pkg/logs` + `/storage/emulated/0` → `/storage/emulated/0/adbi/pkg/logs`。
     *
     * 非 `primary` 卷、空 docId 返回 null。
     */
    internal fun toPath(docId: String, root: String, isTree: Boolean): String? {
        val volume = docId.substringBefore(':', missingDelimiterValue = "")
        if (volume != PRIMARY_VOLUME) return null
        val relative = docId.substringAfter(':', missingDelimiterValue = "").trim('/')
        val path = if (relative.isEmpty()) root else "$root/$relative"
        return if (isTree) path.trimEnd('/') + "/" else path
    }

    private const val TREE_SEGMENT = "tree"
}
