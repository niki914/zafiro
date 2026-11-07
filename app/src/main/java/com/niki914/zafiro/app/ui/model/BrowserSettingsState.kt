package com.niki914.zafiro.app.ui.model

import android.net.Uri
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.chat.agentic.iab.IabManager
import com.niki914.zafiro.repo.XRepo
import java.io.File

/** 浏览器设置页 UI 状态（IAB 全局开关的可视化；工具本身的启停走 builtin-tools 设置）。 */
data class BrowserSettingsUiState(
    val autoGrantMedia: Boolean = IabManager.autoGrantMedia,
    /** 标签上限（r15 点 5：用户可配 1..8；默认进程值 [IabManager.maxTabs]）。 */
    val maxTabs: Int = IabManager.maxTabs,
    /** r23 Item 3：IAB 下载目录（绝对路径；空串 = 默认内部目录 filesDir/downloads）。 */
    val downloadDir: String = IabManager.downloadDir?.absolutePath.orEmpty(),
    /** r24 Item 5：IAB 下载文件夹（SAF OpenDocumentTree 树 URI 字符串；空串 = 未选，回落 [downloadDir]/默认内部目录）。 */
    val downloadDirTree: String = IabManager.downloadDirTree?.toString().orEmpty(),
    /** r24 Item 5：SAF 树顶层文件夹显示名（设置页行尾预览用；空串 = 未选）。 */
    val downloadDirTreeName: String = IabManager.downloadDirTreeName.orEmpty(),
)

sealed interface BrowserSettingsIntent {
    data object Load : BrowserSettingsIntent
    data class ToggleMediaGrant(val enabled: Boolean) : BrowserSettingsIntent
    /** r15 点 5：滑杆提交后热更新 [IabManager.maxTabs] 并持久化（XRepo 1..8）。 */
    data class SetMaxTabs(val value: Int) : BrowserSettingsIntent
    /** r23 Item 3：提交下载目录（trim；空串 = 默认内部目录）。热更新 [IabManager.downloadDir] 并持久化。 */
    data class SetDownloadDir(val path: String) : BrowserSettingsIntent
    /**
     * r24 Item 5：提交 SAF 下载文件夹。[treeUri] 是 OpenDocumentTree 选中的树 URI 字符串
     * （空串 = 清除选择，回落 [IabManager.downloadDir]/默认内部目录）；[treeName] 是顶层文件夹
     * 显示名（设置页行尾预览用；未选/清除时传 null）。热更新 [IabManager] 并持久化 XRepo。
     */
    data class SetDownloadDirTree(val treeUri: String, val treeName: String?) : BrowserSettingsIntent
}

sealed interface BrowserSettingsEffect {
    data object None : BrowserSettingsEffect
}

/**
 * 浏览器设置 MVI ViewModel。
 * 注意：autoGrantMedia 目前是进程级开关（[IabManager] 的 var），不持久化——
 * 如实呈现：重启 App 后恢复默认。持久化版本需要走 XRepo 设置管线（后续扩展点）。
 */
class BrowserSettingsViewModel :
    ComposeMVIViewModel<BrowserSettingsIntent, BrowserSettingsUiState, BrowserSettingsEffect>() {

    override fun initUiState(): BrowserSettingsUiState = BrowserSettingsUiState()

    override suspend fun handleIntent(intent: BrowserSettingsIntent) {
        when (intent) {
            BrowserSettingsIntent.Load -> {
                // 恢复持久化的标签上限（r15 点 5）；进程值由 MainActivity 启动时已同步。
                val persisted = try {
                    XRepo.iabMaxTabs()
                } catch (e: Exception) {
                    IabManager.maxTabs
                }
                // r23 Item 3：恢复持久化的下载目录（空串 = 默认内部目录）。
                val persistedDir = try {
                    XRepo.iabDownloadDir()
                } catch (e: Exception) {
                    IabManager.downloadDir?.absolutePath.orEmpty()
                }
                // r24 Item 5：恢复持久化的 SAF 下载文件夹（空串 = 未选；进程值由 MainActivity 启动时已同步）。
                val persistedTree = try {
                    XRepo.iabDownloadDirTree()
                } catch (e: Exception) {
                    IabManager.downloadDirTree?.toString().orEmpty()
                }
                val persistedTreeName = try {
                    XRepo.iabDownloadDirTreeName()
                } catch (e: Exception) {
                    IabManager.downloadDirTreeName.orEmpty()
                }
                updateState {
                    copy(
                        maxTabs = persisted,
                        downloadDir = persistedDir,
                        downloadDirTree = persistedTree,
                        downloadDirTreeName = persistedTreeName,
                    )
                }
            }

            is BrowserSettingsIntent.ToggleMediaGrant -> {
                IabManager.autoGrantMedia = intent.enabled
                updateState { copy(autoGrantMedia = intent.enabled) }
            }

            is BrowserSettingsIntent.SetMaxTabs -> {
                val value = intent.value.coerceIn(1, 8)
                IabManager.maxTabs = value
                XRepo.setIabMaxTabs(value)
                updateState { copy(maxTabs = value) }
            }

            is BrowserSettingsIntent.SetDownloadDir -> {
                // r23 Item 3：trim 后落盘；空串 = 恢复默认内部目录。目录是否存在/可写由
                // IabManager.handleDownloadStart 在真正下载时处理（失败回默认，不中断下载）。
                val trimmed = intent.path.trim()
                IabManager.downloadDir = if (trimmed.isEmpty()) null else File(trimmed)
                XRepo.setIabDownloadDir(trimmed)
                updateState { copy(downloadDir = trimmed) }
            }

            is BrowserSettingsIntent.SetDownloadDirTree -> {
                // r24 Item 5：SAF 树 URI 字符串落盘；空串 = 清除选择（IabManager 自动回落
                // downloadDir/默认内部目录）。持久 URI 权限由调用方（设置页 launcher）在
                // 选中时就 takePersistableUriPermission，这里只存 URI 本身。
                val trimmed = intent.treeUri.trim()
                val name = intent.treeName?.trim().orEmpty()
                IabManager.downloadDirTree = if (trimmed.isEmpty()) null else Uri.parse(trimmed)
                IabManager.downloadDirTreeName = name.ifEmpty { null }
                XRepo.setIabDownloadDirTree(trimmed)
                XRepo.setIabDownloadDirTreeName(name)
                updateState {
                    copy(
                        downloadDirTree = trimmed,
                        downloadDirTreeName = name,
                    )
                }
            }
        }
    }
}
