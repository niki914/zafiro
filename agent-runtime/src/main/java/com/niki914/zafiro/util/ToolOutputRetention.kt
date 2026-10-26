package com.niki914.zafiro.util

import com.niki914.logging.Logger
import java.io.File

/**
 * 截断导出目录（`filesDir/tool_output`）的保留策略。
 *
 * [ToolOutputTruncator] 把超限工具输出的全文导出到该目录供 agent 回读。
 * 导出文件是纯追加的，本对象负责回收：按「年龄 → 数量 → 体积」三重上限，
 * 从最旧的文件开始删。三条上限传入 0 或负数表示该维度不限制。
 */
object ToolOutputRetention {

    private const val LOG_TAG = "niki914_zafiro_ToolOutputRetention"

    /** 保留的最多文件数。 */
    const val DEFAULT_MAX_FILES = 200

    /** 保留的最大总体积（256 MB）。 */
    const val DEFAULT_MAX_BYTES = 256L * 1024 * 1024

    /** 保留的最长保留期（7 天）。 */
    const val DEFAULT_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    data class Result(
        val deletedFiles: Int,
        val freedBytes: Long,
    ) {
        val changed: Boolean get() = deletedFiles > 0
    }

    private data class FileItem(
        val file: File,
        val lastModified: Long,
        val length: Long,
    )

    /**
     * 回收 [dir] 下的过期与超量文件。
     *
     * 「最旧」按 `lastModified` 判定（导出文件名是随机十六进制，不含时间信息）。
     * 单个文件删不掉不抛异常：不应因为一个坏文件阻断工具主链路，只记一次告警。
     * 子目录不参与回收。
     */
    fun prune(
        dir: File?,
        maxFiles: Int = DEFAULT_MAX_FILES,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
        nowMs: Long = System.currentTimeMillis(),
    ): Result {
        if (dir == null || !dir.isDirectory) return Result(0, 0)
        val all = dir.listFiles() ?: return Result(0, 0)
        val candidates = all.filter { it.isFile }
        if (candidates.isEmpty()) return Result(0, 0)

        val ageLimit = if (maxAgeMs > 0) maxAgeMs else Long.MAX_VALUE
        val fileLimit = if (maxFiles > 0) maxFiles else Int.MAX_VALUE
        val byteLimit = if (maxBytes > 0) maxBytes else Long.MAX_VALUE

        var deletedFiles = 0
        var freedBytes = 0L

        // 第一轮：读取元数据并直接回收超过保留期的文件
        val survivors = ArrayList<FileItem>(candidates.size)
        for (file in candidates) {
            val lastModified = file.lastModified()
            val size = file.length()
            if (nowMs - lastModified > ageLimit) {
                if (deleteQuietly(file)) {
                    deletedFiles++
                    freedBytes += size
                }
            } else {
                survivors.add(FileItem(file, lastModified, size))
            }
        }

        // 第二轮：仍然超量/超体积时，基于内存缓存时间排序，从最旧的开始删
        survivors.sortBy { it.lastModified }
        var remainingFiles = survivors.size
        var remainingBytes = survivors.sumOf { it.length }
        for (item in survivors) {
            if (remainingFiles <= fileLimit && remainingBytes <= byteLimit) break
            if (deleteQuietly(item.file)) {
                deletedFiles++
                freedBytes += item.length
                remainingFiles--
                remainingBytes -= item.length
            }
        }

        if (deletedFiles > 0) {
            Logger.i(
                LOG_TAG,
                "pruned dir=${dir.name} deleted=$deletedFiles freedBytes=$freedBytes " +
                        "remainingFiles=$remainingFiles remainingBytes=$remainingBytes"
            )
        }
        return Result(deletedFiles, freedBytes)
    }

    private fun deleteQuietly(file: File): Boolean {
        if (file.delete()) return true
        if (file.exists()) {
            Logger.w(LOG_TAG, "delete failed name=${file.name} bytes=${file.length()}")
        }
        return false
    }
}
