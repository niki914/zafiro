package com.niki914.zafiro.app.screen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import com.niki914.logging.Logger
import com.niki914.shizuku.display.VirtualDisplayController
import com.niki914.zafiro.api.util.runCatchingCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.Buffer
import java.nio.ByteBuffer

/**
 * 应用进程里的画面缓冲。模型截图不读这里，悬浮卡片读这里。
 */
internal class VirtualDisplayFrames private constructor(
    val width: Int,
    val height: Int,
    private val reader: ImageReader,
    private val thread: HandlerThread,
    private val handler: Handler,
) {
    var displayId: Int = -1
        private set

    @Volatile
    private var highQuality: Boolean = false

    @Volatile
    private var paused: Boolean = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val previews = MutableStateFlow<Bitmap?>(null)
    val frames: StateFlow<Bitmap?> = previews.asStateFlow()

    private val backdrops = MutableStateFlow<Bitmap?>(null)
    val backdropFrames: StateFlow<Bitmap?> = backdrops.asStateFlow()

    private var fullFrame: Bitmap? = null
    private var lastProcessedAt = 0L
    private var lastBackdropAt = 0L
    private var pending = false

    @Volatile
    private var released = false

    fun setHighQuality(enabled: Boolean) {
        if (highQuality != enabled) {
            highQuality = enabled
        }
    }

    fun setPaused(isPaused: Boolean) {
        if (released) return
        handler.post {
            if (released || paused == isPaused) return@post
            paused = isPaused
            if (!isPaused) {
                scheduleNext()
            }
        }
    }

    init {
        reader.setOnImageAvailableListener({ scheduleNext() }, handler)
    }

    fun release() {
        if (released) return
        released = true
        reader.setOnImageAvailableListener(null, null)
        handler.removeCallbacksAndMessages(null)
        reader.close()
        thread.quitSafely()
        fullFrame = null
        previews.value = null
        backdrops.value = null
    }

    private fun scheduleNext() {
        if (pending || released || paused) return
        pending = true
        val interval = if (highQuality) FRAME_INTERVAL_HIGH_QUALITY_MS else FRAME_INTERVAL_LOW_POWER_MS
        val wait = lastProcessedAt + interval - SystemClock.uptimeMillis()
        val task = Runnable {
            pending = false
            lastProcessedAt = SystemClock.uptimeMillis()
            drawLatest()
        }
        if (wait > 0) handler.postDelayed(task, wait) else handler.post(task)
    }

    private fun drawLatest() {
        if (released || paused) return
        val image = runCatchingCancellable {
            reader.acquireLatestImage()
        }.onFailure { error ->
            Logger.w(TAG, "acquireLatestImage failed", error)
        }.getOrNull() ?: return

        try {
            val plane = image.planes[0]
            val strideWidth = plane.rowStride / plane.pixelStride
            var bitmap = fullFrame
            if (bitmap == null || bitmap.width != strideWidth || bitmap.height != height) {
                bitmap = createBitmap(strideWidth, height)
                fullFrame = bitmap
            }
            val buffer = plane.buffer
            (buffer as Buffer).rewind()
            if (buffer.remaining() >= bitmap.byteCount) {
                bitmap.copyPixelsFromBuffer(buffer)
            } else {
                val tempBuffer = ByteBuffer.allocateDirect(bitmap.byteCount)
                tempBuffer.put(buffer)
                (tempBuffer as Buffer).rewind()
                bitmap.copyPixelsFromBuffer(tempBuffer)
            }
            val targetMaxWidth = if (highQuality) width else minOf(PREVIEW_MINI_MAX_WIDTH, width)
            val previewH = maxOf(1, height * targetMaxWidth / width)
            val preview = createBitmap(targetMaxWidth, previewH)
            Canvas(preview).drawBitmap(
                bitmap,
                Rect(0, 0, width, height),
                Rect(0, 0, targetMaxWidth, previewH),
                paint,
            )
            previews.value = preview

            val now = SystemClock.uptimeMillis()
            if (backdrops.value == null || now - lastBackdropAt >= BACKDROP_INTERVAL_MS) {
                lastBackdropAt = now
                val backdropW = minOf(BACKDROP_MAX_WIDTH, width)
                val backdropH = maxOf(1, height * backdropW / width)
                val backdrop = createBitmap(backdropW, backdropH)
                Canvas(backdrop).drawBitmap(
                    bitmap,
                    Rect(0, 0, width, height),
                    Rect(0, 0, backdropW, backdropH),
                    paint,
                )
                backdrops.value = backdrop
            }
        } catch (error: Exception) { // githooks:ignore bare-catch 保护渲染线程不被底层图形异常击垮
            Logger.w(TAG, "drawLatest failed", error)
        } finally {
            image.close()
        }
    }

    companion object {
        private const val TAG = "niki914_zafiro_VirtualDisplayFrames"
        private const val FRAME_INTERVAL_LOW_POWER_MS = 80L
        private const val FRAME_INTERVAL_HIGH_QUALITY_MS = 16L
        private const val PREVIEW_MINI_MAX_WIDTH = 360
        private const val BACKDROP_MAX_WIDTH = 48
        private const val BACKDROP_INTERVAL_MS = 120L

        suspend fun open(
            controller: VirtualDisplayController,
            width: Int,
            height: Int,
            dpi: Int,
        ): VirtualDisplayFrames? {
            val thread = HandlerThread("zafiro-vd-render").apply { start() }
            val handler = Handler(thread.looper)
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val frames = VirtualDisplayFrames(width, height, reader, thread, handler)
            val id = controller.create(reader.surface, width, height, dpi)
            if (id <= 0) {
                frames.release()
                return null
            }
            frames.displayId = id
            return frames
        }
    }
}
