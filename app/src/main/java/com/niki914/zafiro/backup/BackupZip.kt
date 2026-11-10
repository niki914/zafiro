package com.niki914.zafiro.backup

import com.niki914.zafiro.api.util.tryOrNull
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal fun writeZip(file: File, entries: List<ArchiveEntry>) {
    file.parentFile?.mkdirs()
    ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { zip ->
        entries.forEach { entry ->
            check(zipPathSegments(entry.name).none { it == ".." })
            zip.putNextEntry(ZipEntry(entry.name))
            zip.write(entry.bytes)
            zip.closeEntry()
        }
    }
}

internal fun readZip(file: File): ZipRead? {
    val zip = tryOrNull { ZipFile(file) } ?: return null
    return zip.use { opened ->
        val names = mutableListOf<String>()
        val entries = opened.entries()
        while (entries.hasMoreElements()) {
            names += entries.nextElement().name
        }
        if (names.any { unsafeZipPath(it) }) {
            return@use ZipRead.Unsafe
        }
        val bytes = LinkedHashMap<String, ByteArray>()
        names.forEach { name ->
            val entry = opened.getEntry(name) ?: return@forEach
            bytes[name] = opened.getInputStream(entry).use { it.readBytes() }
        }
        ZipRead.Ok(bytes)
    }
}

internal sealed interface ZipRead {
    data class Ok(val entries: Map<String, ByteArray>) : ZipRead
    data object Unsafe : ZipRead
}

internal fun unsafeZipPath(name: String): Boolean {
    if (name.startsWith("/") || name.startsWith("\\")) return true
    return zipPathSegments(name).any { it == ".." }
}

private fun zipPathSegments(name: String): List<String> {
    return name.split('/', '\\')
}
