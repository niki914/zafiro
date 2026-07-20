package com.niki914.nexus.ipc.store

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream

internal object ConfigPersistence {

    fun fileFor(context: Context, descriptor: StoreDescriptor): File {
        return File(context.filesDir, descriptor.relativePath)
    }

    fun readJson(context: Context, descriptor: StoreDescriptor): String? {
        val file = fileFor(context, descriptor)
        return if (file.exists()) file.readText(Charsets.UTF_8) else null
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
