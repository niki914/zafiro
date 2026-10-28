package com.niki914.zafiro.app.ui.helper

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.niki914.zafiro.api.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 磁盘图片异步解码状态。
 */
sealed interface PathBitmapState {
    data object Loading : PathBitmapState
    data class Success(val bitmap: ImageBitmap) : PathBitmapState
    data object Error : PathBitmapState
}

/**
 * 落盘路径 → 异步解码 ImageBitmap（IO dispatcher，主线程零阻塞）。
 * 区分加载中、成功与失败/失效兜底态。
 */
@Composable
fun rememberPathBitmapState(path: String): PathBitmapState =
    produceState<PathBitmapState>(initialValue = PathBitmapState.Loading, path) {
        value = withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val file = File(path)
                if (!file.exists() || !file.isFile || file.length() == 0L) {
                    PathBitmapState.Error
                } else {
                    val bitmap = BitmapFactory.decodeFile(path)?.asImageBitmap()
                    if (bitmap != null) PathBitmapState.Success(bitmap) else PathBitmapState.Error
                }
            }.getOrElse { PathBitmapState.Error }
        }
    }.value
