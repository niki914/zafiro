package android.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class AtomicFile(
    private val baseName: File,
) {
    fun startWrite(): FileOutputStream {
        baseName.parentFile?.mkdirs()
        return FileOutputStream(baseName)
    }

    fun finishWrite(stream: FileOutputStream) {
        stream.close()
    }

    fun failWrite(stream: FileOutputStream) {
        stream.close()
    }

    fun openRead(): FileInputStream {
        return FileInputStream(baseName)
    }
}
