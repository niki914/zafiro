package com.niki914.store

import android.content.Context
import android.util.AtomicFile
import com.niki914.logging.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal object ConfigPersistence {

    private const val LOG_TAG = "niki914_zafiro_ConfigPersistence"

    fun fileFor(context: Context, descriptor: StoreDescriptor): File {
        return File(context.filesDir, descriptor.relativePath)
    }

    fun readJson(context: Context, descriptor: StoreDescriptor): String? {
        val file = fileFor(context, descriptor)
        return try {
            if (file.exists()) file.readText(Charsets.UTF_8) else null
        } catch (e: Exception) {
            when (e) {
                is IOException, is SecurityException -> {
                    Logger.w(
                        LOG_TAG,
                        "readJson failed for ${descriptor.id} (${file.path}): ${e.message}"
                    )
                    null
                }
                else -> throw e
            }
        }
    }

    fun writeJson(context: Context, descriptor: StoreDescriptor, json: String) {
        writeTextAtomically(fileFor(context, descriptor), json)
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
