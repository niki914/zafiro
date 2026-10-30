package com.niki914.zafiro.app.ui.home.helper

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.FileProvider
import com.niki914.logging.Logger
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.api.util.tryOrNull
import java.io.File

private const val ATTACH_LOG_TAG = "niki914_zafiro_HomePageContent_Attach"

/**
 * 相机输出目标：FileProvider 的 uri（给相机写）+ 它背后的临时文件（ingest 完成后删）。
 * 文件落在 `cacheDir/capture/`（见 `res/xml/file_paths.xml`）。
 */
internal data class CameraTarget(val uri: Uri, val file: File)

/** cache 下専给相机输出的子目录（与 `file_paths.xml` 的 `path` 一致）。 */
private const val CAMERA_CAPTURE_DIR = "capture"

/**
 * 文件入口分流：这个 uri 能不能走图片管线。
 *
 * 判据优先看后缀（docId 通常就带着文件名），没有后缀才回落到 provider 报的 MIME——
 * 「下载」抽屉里在 DownloadManager 数据库中的文件就是裸数字 docId，只能靠 MIME。
 */
internal fun isImportableImage(context: Context, uri: Uri, declaredType: String?): Boolean {
    val extension = uri.lastPathSegment.orEmpty()
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
    val isImage = if (extension.isNotEmpty()) {
        extension in IMPORTABLE_IMAGE_EXTENSIONS
    } else {
        declaredType?.startsWith("image/") == true
    }
    if (!isImage) return false
    // 超过图片管线上限的图会被 ingest 拒，而拒了之后草稿项是**静默消失**的，
    // 比「当一个文件附件」更糟。体积未知（-1）时放行，交给一致的上限去判。
    val size = tryOrNull {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    }
    return size == null || size <= IMAGE_MAX_BYTES
}

/**
 * 分流时认作图片的后缀。
 *
 * 不含 HEIC / HEIF：26/27 的 `BitmapFactory` 解不了，进来会在图片管线里静默失败，
 * 所以让它们继续走文件路径（= 今天的形态）。
 */
private val IMPORTABLE_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

/** 与图片管线的 `ImageFormat.MAX_IMAGE_BYTES` 同口径（那个常数在 agent-runtime 内部）。 */
private const val IMAGE_MAX_BYTES = 12 * 1024 * 1024L

/**
 * 造一个相机可以写的输出目标。路径与 provider 都对不上时返回 null（理论上不会）。
 */
internal fun createCameraTarget(context: Context): CameraTarget? = runCatchingCancellable {
    val dir = File(context.cacheDir, CAMERA_CAPTURE_DIR).apply { mkdirs() }
    val file = File(dir, "zafiro_capture_${System.currentTimeMillis()}.jpg")
    CameraTarget(
        uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file),
        file = file,
    )
}.onFailure {
    Logger.w(ATTACH_LOG_TAG, "camera target create failed error=${it.message}")
}.getOrNull()

/**
 * 带写授权的 [ActivityResultContract]。
 *
 * 官方 `TakePicture` 只塞 `EXTRA_OUTPUT`，不带 clipData、不带 grant 标志；FileProvider 的
 * uri 必须显式授权给相机（另一个 uid），否则相机打开它会被拒。所以这里照官方「Take photos」
 * 文档补上 clipData + `FLAG_GRANT_READ/WRITE_URI_PERMISSION`。
 *
 * 回传原始 [resultCode] 而不是 Boolean：排障时必须区分 CANCELED 与其它码，
 * 官方 contract 把它丢了。
 */
internal class GrantingTakePicture : ActivityResultContract<Uri, ActivityResult>() {

    override fun createIntent(context: Context, input: Uri): Intent {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, input)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        // setClipData 返回 void，不能链式；grant 标志要配 clipData 才会落到被授的 uri 上
        intent.clipData = ClipData.newUri(context.contentResolver, "camera_output", input)
        return intent
    }

    override fun parseResult(resultCode: Int, intent: Intent?): ActivityResult =
        ActivityResult(resultCode, intent)
}
