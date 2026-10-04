package com.niki914.zafiro.business.files

import android.net.Uri
import com.niki914.zafiro.api.model.FileRef
import com.niki914.zafiro.business.application.ApplicationService
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.CancellationException

/** [FilesService.attach] 的结果：四种，各自对应一句 toast。 */
sealed interface FileAttachResult {

    data class Ok(val file: FileRef) : FileAttachResult

    /** 解析不出真实路径（云盘 / SD 卡 / 其它 provider）。 */
    data object Unresolvable : FileAttachResult

    /** 要全局文件访问权，用户没给（或跳页失败 / 拿不到结果）。 */
    data object NoPermission : FileAttachResult

    /** 权限拿到了，路径仍然读不到（文件被删 / 目录进不去）。 */
    data object Unreadable : FileAttachResult
}

/**
 * 用户选中的文件 / 文件夹的落地能力：SAF uri → 真实路径 →（必要时）要权限 → 探一次可达性。
 *
 * 调用方只有 Compose 对话页的附件入口；经 ServiceRegistry 取。
 */
interface FilesService {

    /** 纯解析：拿不到真实路径返回 null。不碰权限、不碰文件系统。 */
    fun resolve(uri: String): FileRef?

    /**
     * 「选完文件之后」的完整流程：解析 → 缺存储权限就申请
     * （默认链可能弹框 / 跳设置页，最长等 60s）→ 复查可达性。**拿到权限才返回 [FileAttachResult.Ok]**。
     *
     * 挂起是必需的：跳设置页那条路要等用户回来。调用点不要用 `runBlocking` 包它。
     */
    suspend fun attach(uri: String): FileAttachResult
}

/**
 * 零构造参数（见 `AppServices`）。协作者自己经注册表取。
 *
 * 只有主进程可用：它经注册表取 [PermissionManager]，而后者只装在主进程组合根
 * （`PermissionManagerImpl` 的类注释说明了原因）。附件入口本身也只存在于 Compose 对话页。
 */
class FilesServiceImpl : FilesService {

    private val permissions: PermissionManager = requireService()
    private val appService: ApplicationService = requireService()

    override fun resolve(uri: String): FileRef? {
        val parsedUri = Uri.parse(uri)
        val resolver = runCatching { appService.getApplication().contentResolver }.getOrNull()
        return DocumentPathResolver.resolve(parsedUri, resolver)?.let(::FileRef)
    }

    override suspend fun attach(uri: String): FileAttachResult {
        // 解析可能依赖存储权限：「最近 / 下载」的 docId 要查 MediaStore，没权限就查不到。
        // 所以解不出来时先要一次权限再解，而不是直接判 Unresolvable。
        val file = resolve(uri) ?: run {
            if (!ensureStorageAccess()) return FileAttachResult.NoPermission
            resolve(uri) ?: return FileAttachResult.Unresolvable
        }
        if (!ensureStorageAccess()) return FileAttachResult.NoPermission
        return when (FileProbe.probe(file.path)) {
            FileReachability.Reachable -> FileAttachResult.Ok(file)
            FileReachability.Missing, FileReachability.PermissionDenied -> FileAttachResult.Unreadable
        }
    }

    /**
     * 已有权限直接放行；否则跑默认链（root / Shizuku 静默，或弹框 / 跳设置页等用户回来）。
     * 「存储」在各 API 上是 all-files 还是运行时权限由权限层判断，这里只管语义。
     * 抛异常与 `UNKNOWN` 都按「没拿到」收尾 —— 上层只有「加卡片 / toast 拒绝」两个动作，
     * 不需要区分「用户拒绝」与「拿不到结果」。
     */
    private suspend fun ensureStorageAccess(): Boolean {
        if (permissions.status(Permission.STORAGE) == PermissionState.GRANTED) return true
        val result = try {
            permissions.request(Permission.STORAGE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return false
        }
        return result.finalState == PermissionState.GRANTED
    }
}
