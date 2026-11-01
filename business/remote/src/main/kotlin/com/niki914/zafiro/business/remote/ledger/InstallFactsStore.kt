package com.niki914.zafiro.business.remote.ledger

import com.niki914.logging.Logger
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** schema §8：遥测文件放在 `filesDir` 下的这个目录，只有主进程读写。 */
internal const val TELEMETRY_DIRECTORY_NAME = "telemetry"

/**
 * 把 [InstallFacts] 写成 `install.json`。
 * 文件里的安装戳和这次的 `firstInstallTime` 不同时，清空整个遥测目录再写入。
 */
internal class InstallFactsStore(private val directory: File) {
    private val file = File(directory, INSTALL_FILE_NAME)

    fun resolve(
        firstInstallTimeMs: Long,
        lastUpdateTimeMs: Long,
        versionCode: Int,
        isDebug: Boolean,
        stampedChannel: String?,
    ): InstallFacts {
        when (val loaded = read()) {
            ReadInstall.Missing -> {
                val facts = compute(firstInstallTimeMs, lastUpdateTimeMs, versionCode, isDebug, stampedChannel, null)
                write(facts)
                return facts
            }
            ReadInstall.Unreadable -> {
                Logger.w(LOG_TAG, "安装记录无法解析")
                return compute(firstInstallTimeMs, lastUpdateTimeMs, versionCode, isDebug, stampedChannel, null)
            }
            is ReadInstall.Found -> {
                if (loaded.facts.installStampMs != firstInstallTimeMs) {
                    if (!clearDirectory()) return loaded.facts
                    val facts = compute(
                        firstInstallTimeMs,
                        lastUpdateTimeMs,
                        versionCode,
                        isDebug,
                        stampedChannel,
                        null,
                    )
                    write(facts)
                    return facts
                }
                val facts = compute(
                    firstInstallTimeMs,
                    lastUpdateTimeMs,
                    versionCode,
                    isDebug,
                    stampedChannel,
                    loaded.facts,
                )
                if (facts != loaded.facts) write(facts)
                return facts
            }
        }
    }

    private fun compute(
        firstInstallTimeMs: Long,
        lastUpdateTimeMs: Long,
        versionCode: Int,
        isDebug: Boolean,
        stampedChannel: String?,
        stored: InstallFacts?,
    ): InstallFacts = InstallFacts.resolve(
        firstInstallTimeMs = firstInstallTimeMs,
        lastUpdateTimeMs = lastUpdateTimeMs,
        versionCode = versionCode,
        isDebug = isDebug,
        stampedChannel = stampedChannel,
        stored = stored,
    )

    private fun read(): ReadInstall {
        if (!file.exists()) return ReadInstall.Missing
        val text = try {
            AtomicText.read(file)
        } catch (_: FileNotFoundException) {
            return ReadInstall.Missing
        } catch (error: IOException) {
            Logger.w(LOG_TAG, "安装记录读出失败", error)
            return ReadInstall.Unreadable
        }
        return when (val facts = decode(text)) {
            null -> {
                Logger.w(LOG_TAG, "安装记录无法解析")
                ReadInstall.Unreadable
            }
            else -> ReadInstall.Found(facts)
        }
    }

    private fun write(facts: InstallFacts) {
        try {
            AtomicText.write(file, encode(facts))
        } catch (error: IOException) {
            Logger.w(LOG_TAG, "安装记录写入失败", error)
        }
    }

    private fun clearDirectory(): Boolean {
        if (!directory.exists()) return true
        val children = directory.listFiles()
        if (children == null) {
            Logger.w(LOG_TAG, "遥测目录无法列出")
            return false
        }
        val cleared = children.all { child -> child.deleteRecursively() }
        if (!cleared) Logger.w(LOG_TAG, "遥测目录清空未完成")
        return cleared
    }

    private fun encode(facts: InstallFacts): String {
        val version = facts.firstVersionCode
        return JSONObject()
            .put("v", facts.v)
            .put("install_stamp_ms", facts.installStampMs)
            .put("first_channel", facts.firstChannel)
            .put("first_version_code", version ?: JSONObject.NULL)
            .put("install_day", facts.installDay)
            .toString()
    }

    private fun decode(text: String): InstallFacts? {
        return try {
            val obj = JSONObject(text)
            val version = obj.getInt("v")
            if (version != InstallFacts.VERSION) return null
            InstallFacts(
                v = version,
                installStampMs = obj.getLong("install_stamp_ms"),
                firstChannel = obj.getString("first_channel"),
                firstVersionCode = if (obj.isNull("first_version_code")) null else obj.getInt("first_version_code"),
                installDay = obj.getString("install_day"),
            )
        } catch (_: JSONException) {
            null
        }
    }

    private sealed interface ReadInstall {
        data object Missing : ReadInstall
        data object Unreadable : ReadInstall
        data class Found(val facts: InstallFacts) : ReadInstall
    }

    private companion object {
        const val INSTALL_FILE_NAME = "install.json"
        const val LOG_TAG = "niki914_zafiro_InstallFactsStore"
    }
}
