package com.niki914.zafiro.business.files

import java.io.File

/**
 * 一个路径对**本应用**的可达性。
 *
 * 只反映 app 自己的读权：agent 的 shell 可以跑 root / shizuku
 * （见 `TerminalBuiltin` 的 identity 枚举），那时候它读得到而这里可能报
 * [PermissionDenied]。所以这是保守信号，不是权威结论。
 */
enum class FileReachability {
    /** 存在且能读（文件可读 / 目录可列举）。 */
    Reachable,

    /** 确实不存在。 */
    Missing,

    /** 存在但读不了，或父目录都进不去（app 没有全局文件访问权）。 */
    PermissionDenied,
}

/**
 * 路径可达性探测。同步、无 Context（`java.io.File` 足够）。
 *
 * ## 为什么需要「父目录能否列举」这一步
 *
 * `java.io.File` 把 ENOENT 与 EACCES 都压成 `false`：`exists()` 分不出
 * 「文件没了」和「app 读不了」。而父目录 `list()` 返回 `null` 只在读不了时发生
 * （空目录返回的是空数组），拿它当权限判据，区分就成立了。
 *
 * > 注：真正下结论前不能只信 `exists()` 的，但本项目的策略是「先要权限、要到了再探一次」，
 * > 所以这里够用；不做到「试着真读一个字节」那种程度。
 */
object FileProbe {

    fun probe(path: String): FileReachability {
        val file = File(path)
        if (file.exists()) {
            val usable = if (file.isDirectory) file.list() != null else file.canRead()
            return if (usable) FileReachability.Reachable else FileReachability.PermissionDenied
        }
        return if (file.parentFile?.list() == null) {
            FileReachability.PermissionDenied
        } else {
            FileReachability.Missing
        }
    }
}
