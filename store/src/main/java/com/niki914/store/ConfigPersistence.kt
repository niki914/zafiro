package com.niki914.store

import android.content.Context
import android.util.AtomicFile
import com.niki914.logging.Logger
import com.niki914.zafiro.api.util.runCatchingCancellable
import org.json.JSONException
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 配置文件读写。
 *
 * 写入走 [AtomicFile]（写临时文件再替换），保证不会读到半个文件。
 * 读取额外做两件事：
 * 1. 内容必须可解析为 JSON，否则视为损坏；
 * 2. 损坏时回落到最近一次成功写入的快照（`<name>.good`）并写回主文件，
 *    用户无需清应用数据即可恢复该配置。
 */
internal object ConfigPersistence {

    private const val LOG_TAG = "niki914_zafiro_ConfigPersistence"

    /** 最近一次成功写入的可解析快照后缀。 */
    private const val SNAPSHOT_SUFFIX = ".good"

    fun fileFor(context: Context, descriptor: StoreDescriptor): File =
        File(context.filesDir, descriptor.relativePath)

    fun readJson(context: Context, descriptor: StoreDescriptor): String? =
        readJsonFrom(fileFor(context, descriptor), descriptor.id)

    fun writeJson(context: Context, descriptor: StoreDescriptor, json: String) {
        writeJsonTo(fileFor(context, descriptor), descriptor.id, json)
    }

    /** 与 Context 解耦的读入口：便于用临时目录做单测。 */
    internal fun readJsonFrom(file: File, storeId: String): String? {
        val text = readTextOrNull(file, storeId) ?: return null
        if (isWellFormedJson(text)) return text

        Logger.w(LOG_TAG, "malformed json storeId=$storeId path=${file.path} length=${text.length}")
        val snapshot = readTextOrNull(snapshotFileFor(file), storeId)
        if (snapshot == null || !isWellFormedJson(snapshot)) {
            // 没有可用快照：保持历史行为，原样交给上层 codec 决定如何处理
            Logger.w(LOG_TAG, "no usable snapshot storeId=$storeId")
            return text
        }
        Logger.i(LOG_TAG, "restore storeId=$storeId from snapshot length=${snapshot.length}")
        runCatchingCancellable { writeTextAtomically(file, snapshot) }
        return snapshot
    }

    /** 与 Context 解耦的写入口：主文件写成功后，尽力维护快照。 */
    internal fun writeJsonTo(file: File, storeId: String, json: String) {
        writeTextAtomically(file, json)
        if (!isWellFormedJson(json)) {
            // 不让坏内容覆盖掉上一份好快照，否则损坏就真的无从恢复
            Logger.w(LOG_TAG, "skip snapshot storeId=$storeId reason=malformed-json")
            return
        }
        val snapshotResult = runCatchingCancellable {
            writeTextAtomically(snapshotFileFor(file), json)
        }
        if (snapshotResult.isFailure) {
            Logger.w(
                LOG_TAG,
                "snapshot write failed storeId=$storeId reason=${snapshotResult.exceptionOrNull()?.message}"
            )
        }
    }

    internal fun snapshotFileFor(file: File): File = File(file.path + SNAPSHOT_SUFFIX)

    /** 只判形态、不判 schema：能解析成任意 JSON 值即视为未损坏。 */
    internal fun isWellFormedJson(text: String): Boolean {
        if (text.isBlank()) return false
        return try {
            val tokener = JSONTokener(text)
            tokener.nextValue()
            // nextValue 只吃前缀：`{"a":1} trailing` 同样会“成功”，因此必须读到结尾
            tokener.nextClean() == '\u0000'
        } catch (_: JSONException) {
            false
        }
    }

    private fun readTextOrNull(file: File, storeId: String): String? {
        return try {
            if (file.exists()) file.readText(Charsets.UTF_8) else null
        } catch (e: Exception) {
            when (e) {
                is IOException, is SecurityException -> {
                    Logger.w(LOG_TAG, "read failed storeId=$storeId path=${file.path} reason=${e.message}")
                    null
                }

                else -> throw e
            }
        }
    }

    private fun writeTextAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val atomicFile = AtomicFile(target)
        var stream: FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(text.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (t: Throwable) {
            stream?.let { atomicFile.failWrite(it) }
            throw t
        }
    }
}
