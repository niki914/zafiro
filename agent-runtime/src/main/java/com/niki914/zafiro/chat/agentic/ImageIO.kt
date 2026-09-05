package com.niki914.zafiro.chat.agentic

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.niki914.okia.ImageLoader
import com.niki914.okia.ImageSaver
import java.io.File
import java.security.MessageDigest

/**
 * Android ImageLoader 实现：从文件系统读取图片字节。
 * 返回 null = 文件不存在或不可读（外部存储被用户删除等场景）。
 */
class AndroidImageLoader : ImageLoader {
    override fun load(path: String): ByteArray? {
        return try {
            val file = File(path)
            if (!file.exists() || !file.isFile) return null
            file.readBytes()
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Android ImageSaver 实现：将 base64 图片保存到 App 私有目录 filesDir/Zafiro/images/。
 * SHA-256 内容哈希命名，天然去重。返回文件路径，null = 保存失败。
 * 后续需要文件访问权限逻辑时再改存储位置（当前与 py 工具一致走私有目录，零权限负担）。
 */
class AndroidImageSaver(context: Context) : ImageSaver {
    private val imagesDir: File = File(File(context.filesDir, "Zafiro"), "images")

    override suspend fun save(base64: String, mimeType: String): String? {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            if (bytes.isEmpty()) return null
            val hash = sha256(bytes)
            val ext = mimeType.substringAfterLast("/").lowercase().takeIf { it.isNotEmpty() } ?: "jpg"
            val file = File(imagesDir, "$hash.$ext")
            if (!file.exists()) {
                if (!imagesDir.exists()) imagesDir.mkdirs()
                file.writeBytes(bytes)
            }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/**
 * 用户分享图片（content URI）→ 保存到 App 私有目录 images 目录 → 返回路径。
 * 供分享入口调用。
 */
class UserImageSaver(private val context: Context) {
    private val imagesDir: File = File(File(context.filesDir, "Zafiro"), "images")

    fun saveFromUri(uri: Uri): String? {
        return try {
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri) ?: "image/jpeg"
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            if (bytes.isEmpty()) return null
            val hash = sha256(bytes)
            val ext = mimeType.substringAfterLast("/").lowercase().takeIf { it.isNotEmpty() } ?: "jpg"
            val file = File(imagesDir, "$hash.$ext")
            if (!file.exists()) {
                if (!imagesDir.exists()) imagesDir.mkdirs()
                file.writeBytes(bytes)
            }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
