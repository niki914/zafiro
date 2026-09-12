package com.niki914.zafiro.app.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.niki914.logging.Logger
import com.niki914.zafiro.app.R
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionRequest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 后台工具权限确认弹窗。
 *
 * 应用不在前台时，Compose 对话框不可见，此时通过 TYPE_APPLICATION_OVERLAY
 * 窗口渲染确认界面。窗口管理与 root 授权复用 [PointerOverlay] 的既有模式。
 *
 * 布局像素级复刻 Shizuku 的 RequestPermissionActivity（confirmation_dialog.xml
 * + GrantPermissionsButtons 样式）：居中图标 24dp → 居中标题 20sp → 全宽纵向
 * 堆叠按钮（允许在上、拒绝在下，56dp 高，2dp 缝，圆角 12/4 内收）。内容
 * （文案与 icon）沿用本应用既有资源。
 */
object ToolPermissionOverlay {

    private const val TAG = "niki914_nexus_ToolPermOverlay"

    // ---- Shizuku confirmation_dialog.xml / GrantPermissionsButtons 对应值 ----
    private const val CARD_MAX_WIDTH_DP = 320      // M3 对话框宽度
    private const val CARD_SIDE_MARGIN_DP = 36f    // 小屏兜底边距
    private const val CARD_CORNER_DP = 28f         // M3 dialog corner radius
    private const val ROOT_PAD_TOP_DP = 24f        // 根容器上下 padding
    private const val ROOT_PAD_BOTTOM_DP = 24f
    private const val ICON_SIZE_DP = 24            // 图标 24dp 居中
    private const val TITLE_PAD_H_DP = 24f         // 标题左右 padding
    private const val TITLE_PAD_TOP_DP = 16f
    private const val TITLE_PAD_BOTTOM_DP = 24f
    private const val TITLE_TEXT_SIZE_SP = 20f     // Shizuku 标题 20sp
    private const val BUTTON_MIN_HEIGHT_DP = 56    // GrantPermissionsButtons
    private const val BUTTON_PAD_DP = 16f          //   padding 16dp
    private const val BUTTON_MARGIN_H_DP = 24f     //   左右 margin 24dp
    private const val BUTTON_GAP_DP = 2f           //   两按钮间 2dp 缝
    private const val BUTTON_TEXT_SIZE_SP = 14f    //   textSize 14sp
    private const val BUTTON_CORNER_OUT_DP = 12f   // 外侧圆角 12dp
    private const val BUTTON_CORNER_IN_DP = 4f     // 内侧圆角 4dp
    private const val DIM_ALPHA = 153              // dialog backgroundDim 相当值

    // ---- M3 baseline 色板（overlay 无 Compose 主题，取 Shizuku 同款默认紫） ----
    private const val PRIMARY_CONTAINER = 0xFFEADDFF.toInt()
    private const val ON_PRIMARY_CONTAINER = 0xFF21005D.toInt()
    private const val SURFACE_LIGHT = 0xFFFEF7FF.toInt()   // M3 dialog surface
    private const val SURFACE_DARK = 0xFF141218.toInt()
    private const val SURFACE_CONTAINER_HIGHEST_LIGHT = 0xFFE6E0E9.toInt()
    private const val ON_SURFACE_LIGHT = 0xFF1D1B20.toInt()
    private const val ON_SURFACE_VARIANT_LIGHT = 0xFF49454F.toInt()
    private const val SURFACE_CONTAINER_HIGHEST_DARK = 0xFF35363F.toInt()
    private const val ON_SURFACE_DARK = 0xFFE6E1E5.toInt()
    private const val ON_SURFACE_VARIANT_DARK = 0xFFCAC4D0.toInt()
    private const val ERROR_LIGHT = 0xFFB3261E.toInt()
    private const val ERROR_DARK = 0xFFF2B8B5.toInt()

    /**
     * 当前后台确认窗口的原子绑定（Group8 race fix）。
     *
     * 同一窗口/等待器与全部已并入的 requestId 由单个对象持有，Runtime 响应线程只需一次
     * [current] 读取即可获得一致身份（不是两个无法同步的 volatile 字段）；
     * requestId 集合仅在短锁内增删，窗口/UI 操作不在锁内执行。
     */
    private class Waiter(
        val wm: WindowManager,
        val view: View,
        val deferred: CompletableDeferred<Boolean> = CompletableDeferred(),
    ) {
        private val lock = Any()
        private val requestIds = mutableSetOf<String>()

        fun bind(requestId: String) = synchronized(lock) { requestIds.add(requestId) }
        fun unbind(requestId: String) = synchronized(lock) { requestIds.remove(requestId) }
        fun holds(requestId: String) = synchronized(lock) { requestId in requestIds }
    }

    private val current = AtomicReference<Waiter?>(null)

    suspend fun show(context: Context, request: ToolPermissionRequest): Boolean =
        withContext(Dispatchers.Main) {
            // 已有窗口在显示时并入同一等待器：本次 requestId 也绑定到同一 waiter，
            // 任一身份都能完成它（禁止叠两层，双重黑幕的来源）。本次调用 finally 只解除
            // 自己的绑定，不关闭共享窗口（由首个调用方 finally 按捕获身份释放）。
            val merged = current.get()
            if (merged != null && merged.deferred.isActive) {
                merged.bind(request.id)
                return@withContext try {
                    merged.deferred.await()
                } finally {
                    merged.unbind(request.id)
                }
            }

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            lp.gravity = Gravity.TOP or Gravity.START

            // 捕获本次的 WindowManager/view/waiter；getSystemService、buildRoot、addView
            // 任一失败都按捕获身份清理，失败原样上抛（宿主映射 DENIED_UNAVAILABLE）。
            var waiter: Waiter? = null
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val view = buildRoot(context, request) { allowed ->
                    waiter?.deferred?.let { if (it.isActive) it.complete(allowed) }
                }
                val bound = Waiter(wm, view)
                waiter = bound
                bound.bind(request.id)
                current.set(bound)
                wm.addView(view, lp)
                bound.deferred.await()
            } finally {
                waiter?.let { release(it) }
            }
        }

    /**
     * 后台授权响应接缝（Group8 §9.11）：按 requestId 完成当前窗口的同一原等待器。
     * 合并进来的每个 requestId 都绑定到同一 waiter，故任一身份都能完成它；
     * 无窗口、身份未绑定或等待器已结束一律返回 false（安全默认，不误完成后继请求）。
     */
    internal fun respond(requestId: String, allowed: Boolean): Boolean {
        val waiter = current.get() ?: return false
        if (!waiter.holds(requestId)) return false
        val deferred = waiter.deferred
        if (!deferred.isActive) return false
        return deferred.complete(allowed)
    }

    /** 关闭当前窗口（外部显式清理）；仅释放当前 waiter，旧窗口不能清除后继。 */
    fun dismiss() {
        current.get()?.let { release(it) }
    }

    /**
     * 按捕获的 waiter 身份释放：仅当仍是当前窗口时原子清除 [current]；
     * view 移除用捕获的 wm/view，旧窗口的 finally 不清掉后来显示的窗口。
     */
    private fun release(waiter: Waiter) {
        current.compareAndSet(waiter, null)
        try {
            waiter.wm.removeViewImmediate(waiter.view)
        } catch (e: Exception) {
            Logger.w(TAG, "overlay removeView failed", e)
        }
    }

    // ============================================================
    // 布局：Shizuku confirmation_dialog.xml 结构
    // ============================================================

    private fun buildRoot(
        context: Context,
        request: ToolPermissionRequest,
        onDecision: (Boolean) -> Unit,
    ): View {
        val density = context.resources.displayMetrics.density
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(DIM_ALPHA, 0, 0, 0))
            isClickable = true
            setOnClickListener { onDecision(false) }
        }

        val card = buildCard(context, density, request, onDecision)
        val cardLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER
            width = minOf(
                (CARD_MAX_WIDTH_DP * density).toInt(),
                context.resources.displayMetrics.widthPixels -
                        (2 * CARD_SIDE_MARGIN_DP * density).toInt(),
            )
        }
        root.addView(card, cardLp)
        return root
    }

    private fun buildCard(
        context: Context,
        density: Float,
        request: ToolPermissionRequest,
        onDecision: (Boolean) -> Unit,
    ): LinearLayout {
        val isNight = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val surface = if (isNight) SURFACE_DARK else SURFACE_LIGHT
        val onSurface = if (isNight) ON_SURFACE_DARK else ON_SURFACE_LIGHT
        val onSurfaceVariant = if (isNight) ON_SURFACE_VARIANT_DARK else ON_SURFACE_VARIANT_LIGHT
        val commandBg = if (isNight) SURFACE_CONTAINER_HIGHEST_DARK else SURFACE_CONTAINER_HIGHEST_LIGHT
        val ruleColor = if (isNight) ERROR_DARK else ERROR_LIGHT

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 卡片背景：M3 dialog surface + 28dp 圆角（缺它文字直接浮在黑幕上）
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = CARD_CORNER_DP * density
            }
            isClickable = true // 消费点击，防止穿透到 dim 层
            // 根容器 paddingTop/Bottom 24dp（confirmation_dialog.xml）
            setPadding(0, (ROOT_PAD_TOP_DP * density).toInt(), 0, (ROOT_PAD_BOTTOM_DP * density).toInt())

            // 图标 24dp 居中（内容用应用自己的 icon）
            addView(ImageView(context).apply {
                setImageResource(R.mipmap.ic_launcher)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                val size = (ICON_SIZE_DP * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            })

            // 标题 20sp 居中，padding 24/16/24/24（confirmation_dialog.xml）
            addView(TextView(context).apply {
                text = context.getString(R.string.tool_permission_dialog_title)
                setTextColor(onSurface)
                textSize = TITLE_TEXT_SIZE_SP
                gravity = Gravity.CENTER
                setPadding(
                    (TITLE_PAD_H_DP * density).toInt(),
                    (TITLE_PAD_TOP_DP * density).toInt(),
                    (TITLE_PAD_H_DP * density).toInt(),
                    (TITLE_PAD_BOTTOM_DP * density).toInt(),
                )
            })

            // 工具介绍（带应用名）
            addView(introText(context, request, onSurfaceVariant, density))

            // 命令块（等宽，surfaceContainerHighest 圆角块）
            addView(commandBlock(context, request, onSurface, commandBg, density))

            // 命中规则
            addView(ruleText(context, request, ruleColor, density))

            // 允许（上）—— GrantPermissionsButtons.Top
            addView(
                button(
                    context, density,
                    text = context.getString(R.string.tool_permission_allow),
                    bgColor = PRIMARY_CONTAINER,
                    textColor = ON_PRIMARY_CONTAINER,
                    topLeft = BUTTON_CORNER_OUT_DP, topRight = BUTTON_CORNER_OUT_DP,
                    bottomLeft = BUTTON_CORNER_IN_DP, bottomRight = BUTTON_CORNER_IN_DP,
                    bottomMarginDp = BUTTON_GAP_DP,
                ) { onDecision(true) },
            )

            // 拒绝（下）—— GrantPermissionsButtons.Buttom
            addView(
                button(
                    context, density,
                    text = context.getString(R.string.tool_permission_deny),
                    bgColor = PRIMARY_CONTAINER,
                    textColor = ON_PRIMARY_CONTAINER,
                    topLeft = BUTTON_CORNER_IN_DP, topRight = BUTTON_CORNER_IN_DP,
                    bottomLeft = BUTTON_CORNER_OUT_DP, bottomRight = BUTTON_CORNER_OUT_DP,
                ) { onDecision(false) },
            )
        }
    }

    // ============================================================
    // 内容元素
    // ============================================================

    private fun introText(
        context: Context,
        request: ToolPermissionRequest,
        color: Int,
        density: Float,
    ): TextView = TextView(context).apply {
        text = context.getString(R.string.tool_permission_request_intro, request.toolName)
        setTextColor(color)
        textSize = 14f
        setPadding(
            (TITLE_PAD_H_DP * density).toInt(), 0, (TITLE_PAD_H_DP * density).toInt(),
            (TITLE_PAD_BOTTOM_DP * density).toInt() / 2,
        )
    }

    private fun commandBlock(
        context: Context,
        request: ToolPermissionRequest,
        textColor: Int,
        bg: Int,
        density: Float,
    ): TextView = TextView(context).apply {
        text = request.command
        setTextColor(textColor)
        textSize = 13f
        typeface = Typeface.MONOSPACE
        val pad = (12f * density).toInt()
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = 12f * density
        }
        // 单行省略比 10 行截断更接近"对话框预览"语义
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            setMargins(
                (TITLE_PAD_H_DP * density).toInt(), 0, (TITLE_PAD_H_DP * density).toInt(),
                (TITLE_PAD_BOTTOM_DP * density).toInt() / 2,
            )
        }
    }

    private fun ruleText(
        context: Context,
        request: ToolPermissionRequest,
        color: Int,
        density: Float,
    ): TextView = TextView(context).apply {
        text = context.getString(R.string.tool_permission_matched_rule, request.matchedRuleName)
        setTextColor(color)
        textSize = 14f
        setPadding(
            (TITLE_PAD_H_DP * density).toInt(), 0, (TITLE_PAD_H_DP * density).toInt(),
            (24f * density).toInt(),
        )
    }

    /** Shizuku GrantPermissionsButtons：match_parent / minHeight 56dp / padding 16 / 14sp medium / 居中 */
    private fun button(
        context: Context,
        density: Float,
        text: String,
        bgColor: Int,
        textColor: Int,
        topLeft: Float,
        topRight: Float,
        bottomLeft: Float,
        bottomRight: Float,
        bottomMarginDp: Float = 0f,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        this.text = text
        setTextColor(textColor)
        textSize = BUTTON_TEXT_SIZE_SP
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        minHeight = (BUTTON_MIN_HEIGHT_DP * density).toInt()
        val pad = (BUTTON_PAD_DP * density).toInt()
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadii = floatArrayOf(
                topLeft * density, topLeft * density,
                topRight * density, topRight * density,
                bottomRight * density, bottomRight * density,
                bottomLeft * density, bottomLeft * density,
            )
        }
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            setMargins(
                (BUTTON_MARGIN_H_DP * density).toInt(), 0,
                (BUTTON_MARGIN_H_DP * density).toInt(),
                (bottomMarginDp * density).toInt(),
            )
        }
    }
}
