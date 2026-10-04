package com.niki914.zafiro.app.ui.model.home

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.ui.graphics.vector.ImageVector
import com.niki914.zafiro.app.R

/**
 * 文件的粗粒度分类（带实心图标与多语言类型说明）。
 */
enum class HomeChatFileCategory(
    val icon: ImageVector,
    @param:StringRes val labelRes: Int,
) {
    Folder(Icons.Filled.Folder, R.string.ui_home_file_category_folder),
    Apk(Icons.Filled.Android, R.string.ui_home_file_category_apk),
    Code(Icons.Filled.Code, R.string.ui_home_file_category_code),
    Document(Icons.Filled.Description, R.string.ui_home_file_category_document),
    Audio(Icons.Filled.AudioFile, R.string.ui_home_file_category_audio),
    Video(Icons.Filled.VideoFile, R.string.ui_home_file_category_video),
    Image(Icons.Filled.Image, R.string.ui_home_file_category_image),
    Archive(Icons.Filled.FolderZip, R.string.ui_home_file_category_archive),
    Generic(Icons.AutoMirrored.Filled.InsertDriveFile, R.string.ui_home_file_category_file);

    companion object {
        private val CODE_EXTENSIONS = setOf(
            "xml", "json", "yaml", "yml", "toml", "properties", "ini", "conf", "gradle",
            "html", "htm", "css", "scss", "js", "jsx", "ts", "tsx", "vue",
            "kt", "kts", "java", "py", "sh", "bash", "zsh", "c", "cpp", "h", "cs", "go",
            "rs", "sql", "rb", "php", "swift", "dart", "lua",
        )
        private val DOCUMENT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "log", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "csv", "tsv", "rtf",
        )
        private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "flac", "m4a", "aac", "ogg", "opus", "mid", "midi")
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "3gp", "wmv")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "svg", "ico", "heic", "heif", "tiff")
        private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "jar", "iso")

        fun fromPath(path: String): HomeChatFileCategory {
            if (path.endsWith('/')) return Folder
            val ext = path.substringAfterLast('.', "").lowercase()
            return when {
                ext == "apk" -> Apk
                ext in CODE_EXTENSIONS -> Code
                ext in DOCUMENT_EXTENSIONS -> Document
                ext in AUDIO_EXTENSIONS -> Audio
                ext in VIDEO_EXTENSIONS -> Video
                ext in IMAGE_EXTENSIONS -> Image
                ext in ARCHIVE_EXTENSIONS -> Archive
                else -> Generic
            }
        }
    }
}

/**
 * 文件名中间截断函数，确保无论文件名多长，文件扩展名永远可见。
 */
fun formatFileNameMiddleTruncated(name: String, maxChars: Int): String {
    val clean = name.trimEnd('/')
    if (clean.length <= maxChars) return clean
    val dotIndex = clean.lastIndexOf('.')
    if (dotIndex in 1 until clean.length - 1 && clean.length - dotIndex <= 6) {
        val ext = clean.substring(dotIndex)
        val stem = clean.substring(0, dotIndex)
        val available = maxChars - ext.length - 1
        if (available >= 3) {
            val front = (available + 1) / 2
            val back = available - front
            return "${stem.take(front)}…${stem.takeLast(back)}$ext"
        }
    }
    val front = (maxChars - 1) / 2
    val back = maxChars - 1 - front
    return "${clean.take(front)}…${clean.takeLast(back)}"
}
