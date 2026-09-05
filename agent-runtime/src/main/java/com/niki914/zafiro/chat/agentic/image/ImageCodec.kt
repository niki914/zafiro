package com.niki914.zafiro.chat.agentic.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import com.hashsequence.coilresvg.SvgRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Android 侧图片 ingest 管线：读源 → 校验 → 收缩 → 重编码 → 落盘。
 * 产出统一为 JPEG q80、长边 ≤1600px、像素 ≤1.5MP。
 * 纯逻辑（魔数/尺寸计算等）在 [ImageFormat]，本类只做 Bitmap 编解码与 IO。
 */
internal class ImageCodec(private val context: Context) {

    /** 落盘图片的统一存储目录：filesDir/Zafiro/images/（私有，零权限）。 */
    private val imagesDir: File
        get() = File(File(context.filesDir, "Zafiro"), "images").apply {
            if (!exists()) mkdirs()
        }

    // ── 公共入口 ─────────────────────────────────────────────────────────

    suspend fun ingestFile(path: String): IngestResult = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists() || !file.isFile) return@withContext IngestResult.Err(IngestError.FileNotFound)
        if (file.length() == 0L) return@withContext IngestResult.Err(IngestError.EmptyContent)
        if (file.length() > ImageFormat.MAX_IMAGE_BYTES.toLong()) {
            return@withContext IngestResult.Err(
                IngestError.TooLarge(file.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            )
        }
        try {
            val bytes = file.readBytes()
            ingestBytes(bytes, ImageFormat.normalizeMime(mimeForExtension(file.extension)))
        } catch (e: Exception) {
            IngestResult.Err(IngestError.IoFailed(e))
        }
    }

    suspend fun ingestUri(uri: Uri): IngestResult = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val mimeHint = resolver.getType(uri)?.let { ImageFormat.normalizeMime(it) } ?: "image/jpeg"
            val read = readBytesFromUri(uri)
            val bytes = when {
                read.tooLarge -> return@withContext IngestResult.Err(
                    IngestError.TooLarge(ImageFormat.MAX_IMAGE_BYTES)
                )
                read.bytes == null -> return@withContext IngestResult.Err(IngestError.FileNotFound)
                else -> read.bytes
            }
            if (bytes.isEmpty()) return@withContext IngestResult.Err(IngestError.EmptyContent)
            ingestBytes(bytes, mimeHint)
        } catch (e: Exception) {
            IngestResult.Err(IngestError.IoFailed(e))
        }
    }

    suspend fun ingestBase64(raw: String): IngestResult = withContext(Dispatchers.IO) {
        val encoded = ImageFormat.stripDataUrlPrefix(raw)
        val mimeHint = ImageFormat.extractMimeFromDataUrl(raw)?.let { ImageFormat.normalizeMime(it) } ?: "image/jpeg"
        val bytes = try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (e: Exception) {
            return@withContext IngestResult.Err(IngestError.DecodeFailed(e))
        }
        if (bytes.isEmpty()) return@withContext IngestResult.Err(IngestError.EmptyContent)
        ingestBytes(bytes, mimeHint)
    }

    // ── 核心管线 ─────────────────────────────────────────────────────────

    private fun ingestBytes(bytes: ByteArray, mimeHint: String): IngestResult {
        if (bytes.size > ImageFormat.MAX_IMAGE_BYTES) {
            return IngestResult.Err(IngestError.TooLarge(bytes.size))
        }

        if (mimeHint == "image/svg+xml") return ingestSvg(bytes)
        if (!ImageFormat.hasImageMagic(bytes)) {
            return IngestResult.Err(IngestError.UnsupportedFormat)
        }

        // 1. 读真实尺寸（不分配像素）
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val srcWidth = bounds.outWidth
        val srcHeight = bounds.outHeight
        if (srcWidth <= 0 || srcHeight <= 0) return IngestResult.Err(IngestError.DecodeFailed(null))

        val mime = bounds.outMimeType?.takeIf { it.isNotBlank() }?.let { ImageFormat.normalizeMime(it) } ?: mimeHint

        // 2. 计算目标尺寸 + 降采样
        val (targetW, targetH) = ImageFormat.targetSize(srcWidth, srcHeight)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = ImageFormat.sampleSize(srcWidth, srcHeight, targetW, targetH)
        }

        // 3. 解码
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return IngestResult.Err(IngestError.DecodeFailed(null))

        // 4-6 统一 try/finally：单一所有者 work，任何一步抛异常都在 finally 回收，
        // 不泄漏中间位图（缩放 / 拍底各产生一个新 bitmap，旧图立即交接）
        var work: Bitmap = decoded
        var result: IngestResult
        try {
            // 4. 精确缩放（inSampleSize 只保证粗缩）
            if (work.width != targetW || work.height != targetH) {
                val scaled = Bitmap.createScaledBitmap(work, targetW, targetH, true)
                if (scaled !== work) work.recycle()
                work = scaled
            }

            // 5. JPEG q80 编码（alpha 拍白底：JPEG 无透明通道，直接压缩会按黑底合成）
            if (work.hasAlpha()) {
                val flattened = Bitmap.createBitmap(work.width, work.height, Bitmap.Config.ARGB_8888).also { out ->
                    Canvas(out).apply {
                        drawColor(Color.WHITE)
                        drawBitmap(work, 0f, 0f, null)
                    }
                }
                work.recycle()
                work = flattened
            }
            val outBytes = ByteArrayOutputStream(minOf(work.width * work.height / 4, 2 * 1024 * 1024).coerceAtLeast(32 * 1024)).use { bos ->
                work.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                bos.toByteArray()
            }

            // 6. 落盘
            result = IngestResult.Ok(persistJpeg(outBytes, targetW, targetH))
        } catch (e: Exception) {
            result = IngestResult.Err(IngestError.DecodeFailed(e))
        } finally {
            if (!work.isRecycled) work.recycle()
        }
        return result
    }

    /**
     * SVG 光栅化：resvg（coil-resvg-android 经 Rust FFI），mask/filter/clipPath
     * 全支持。显式宽高缺失时按 viewBox 纵横比推算，避免方形 fallback 压扁原图。
     * 输出与其他 ingest 源一致：JPEG q80 落盘。
     */
    private fun ingestSvg(raw: ByteArray): IngestResult {
        return try {
            SvgRenderer.fromData(raw).use { renderer ->
                val size = renderer.getSize()
                val aspect = if (size.height > 0f) size.width / size.height else 1f
                val srcW = size.width.takeIf { it > 0f }
                    ?: (if (aspect >= 1f) ImageFormat.MAX_LONG_EDGE.toFloat() else ImageFormat.MAX_LONG_EDGE * aspect)
                val srcH = size.height.takeIf { it > 0f }
                    ?: (if (aspect >= 1f) ImageFormat.MAX_LONG_EDGE / aspect else ImageFormat.MAX_LONG_EDGE.toFloat())
                val (targetW, targetH) = ImageFormat.targetSize(srcW.toInt(), srcH.toInt())

                val rendered = renderer.render(targetW.toUInt(), targetH.toUInt())
                val bitmap = rgbaToBitmap(rendered.pixels, targetW, targetH)
                try {
                    val outBytes = ByteArrayOutputStream(targetW * targetH / 4).use { bos ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                        bos.toByteArray()
                    }
                    IngestResult.Ok(persistJpeg(outBytes, targetW, targetH))
                } finally {
                    bitmap.recycle()
                }
            }
        } catch (e: Exception) {
            IngestResult.Err(IngestError.SvgRenderFailed(e))
        }
    }

    /** resvg 输出 RGBA（非预乘）→ ARGB_8888 Bitmap（照抄库内 premultiply 模板）。 */
    private fun rgbaToBitmap(rgba: ByteArray, width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val base = i * 4
            val r = rgba[base].toInt() and 0xFF
            val g = rgba[base + 1].toInt() and 0xFF
            val b = rgba[base + 2].toInt() and 0xFF
            val a = rgba[base + 3].toInt() and 0xFF
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.isPremultiplied = false
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.isPremultiplied = true
        return bitmap
    }

    /** sha256 命名落盘（tmp+rename，rename 失败回退直接写）→ StoredImage。 */
    private fun persistJpeg(bytes: ByteArray, width: Int, height: Int): StoredImage {
        val hash = sha256(bytes)
        val file = File(imagesDir, "$hash.jpg")
        if (!file.exists()) {
            val tmp = File(imagesDir, "$hash.tmp")
            FileOutputStream(tmp).use { it.write(bytes) }
            if (!tmp.renameTo(file)) {
                file.writeBytes(bytes)
                tmp.delete()
            }
        }
        return StoredImage(file.absolutePath, "image/jpeg", width, height, bytes.size)
    }

    // ── URI 读取 ─────────────────────────────────────────────────────────

    private class UriReadResult(val bytes: ByteArray?, val tooLarge: Boolean = false) {
        fun hasContent(): Boolean = bytes != null && bytes.isNotEmpty()
    }

    private fun readBytesFromUri(uri: Uri): UriReadResult {
        val resolver = context.contentResolver
        // 尝试 1：标准流
        resolver.openInputStream(uri)?.use { input ->
            val read = input.readBytesLimited()
            if (read.tooLarge) return UriReadResult(null, tooLarge = true)
            if (read.hasContent()) return read
        }
        // 尝试 2：文件描述符（部分 ROM 只支持）
        resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                val read = input.readBytesLimited()
                if (read.tooLarge) return UriReadResult(null, tooLarge = true)
                if (read.hasContent()) return read
            }
        }
        return UriReadResult(null)
    }

    /** 读满流；超 12MB → (null, tooLarge=true)；读失败 → (null, false)。 */
    private fun java.io.InputStream.readBytesLimited(): UriReadResult {
        return try {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = read(buffer)
                if (read < 0) break
                total += read
                if (total > ImageFormat.MAX_IMAGE_BYTES) return UriReadResult(null, tooLarge = true)
                output.write(buffer, 0, read)
            }
            UriReadResult(output.toByteArray())
        } catch (e: Exception) {
            UriReadResult(null)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun mimeForExtension(ext: String): String = when (ext.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "heic", "heif" -> "image/heic"
        "avif" -> "image/avif"
        else -> "image/jpeg"
    }
}

// ── 产出模型 ─────────────────────────────────────────────────────────────

/** ingest 后的落盘图片元数据。 */
internal data class StoredImage(
    val path: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val bytes: Int,
)

/** ingest 结果：成功 / 失败（[IngestError] 分类）。 */
internal sealed interface IngestResult {
    data class Ok(val image: StoredImage) : IngestResult
    data class Err(val error: IngestError) : IngestResult
}
