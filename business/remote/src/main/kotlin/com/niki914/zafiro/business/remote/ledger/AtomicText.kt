package com.niki914.zafiro.business.remote.ledger

import android.util.AtomicFile
import java.io.File

internal object AtomicText {
    fun read(file: File): String {
        return AtomicFile(file).openRead().use { input ->
            input.readBytes().decodeToString()
        }
    }

    fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        var committed = false
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            committed = true
            atomic.finishWrite(stream)
        } finally {
            if (!committed) atomic.failWrite(stream)
        }
    }
}
