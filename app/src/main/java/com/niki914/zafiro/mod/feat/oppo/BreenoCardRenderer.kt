package com.niki914.zafiro.mod.feat.oppo

import com.niki914.logging.Logger
import com.niki914.zafiro.chat.ActiveTurnStore
import com.niki914.zafiro.runtime.client.AssistantFrame
import com.niki914.zafiro.runtime.ipc.ToolStatus
import de.robv.android.xposed.XposedHelpers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.reflect.Method
import java.util.UUID

/**
 * Breeno（小布助手）专属的底层卡片渲染驱动。
 *
 * 职责：
 * 1. 负责反射类名解析与方法调用脏活（AIChatViewBean、AIChatDataCenter 等）；
 * 2. 注入官方原生 Thinking Block 字段（reasoningContent, reasoningState, reasoningStart 等）；
 * 3. 维护单个 Turn 的卡片会话生命周期与插入/刷新；
 * 4. 彻底将反射与宿主脏逻辑从上层胶水（BreenoHook）中剥离。
 */
class BreenoCardRenderer {

    companion object {
        const val LOG_TAG = "niki914_zafiro_BreenoCardRenderer"
    }

    private var dataCenterInstance: Any? = null
    private var viewBeanClass: Class<*>? = null
    private var resolvedInsertMethod: Method? = null
    private var resolvedUpdateMethod: Method? = null

    private val renderSessionMutex = Mutex()
    private var currentRenderSession: BreenoRenderSession? = null

    private data class BreenoRenderSession(
        val turnId: Long,
        val roomId: String,
        val recordId: String,
        var bean: Any? = null,
        var isFirstInsertDone: Boolean = false,
        var isThinkingAnimInitialized: Boolean = false,
    )

    /**
     * 初始化宿主 Class 与 Method 探测
     */
    fun init(classLoader: ClassLoader) {
        val beanClassName = BreenoConfigProvider.RenderCard.viewBeanClass
        try {
            viewBeanClass = classLoader.loadClass(beanClassName)
            Logger.i(LOG_TAG, "Resolved viewBeanClass=$beanClassName")
        } catch (e: Throwable) {
            Logger.e(LOG_TAG, "Failed resolving viewBeanClass=$beanClassName: ${e.message}", e)
        }

        resolveDataCenter(classLoader)
    }

    /**
     * 允许从 CaptureInputHook 回调中注入已捕获的 DataCenter 实例
     */
    fun setDataCenterInstance(instance: Any) {
        if (dataCenterInstance == null) {
            dataCenterInstance = instance
            Logger.i(LOG_TAG, "DataCenter instance set via hook: ${instance.javaClass.name}")
            resolveDataCenterMethods(instance.javaClass)
        }
    }

    private fun resolveDataCenter(classLoader: ClassLoader) {
        try {
            val dcClass = classLoader.loadClass("com.heytap.speechassist.aichat.AIChatDataCenter")
            val field = dcClass.getField("INSTANCE")
            val instance = field.get(null)
            if (instance != null) {
                dataCenterInstance = instance
                Logger.i(LOG_TAG, "DataCenter instance resolved via AIChatDataCenter.INSTANCE")
                resolveDataCenterMethods(dcClass)
            }
        } catch (e: Throwable) {
            Logger.w(LOG_TAG, "Could not eagerly resolve AIChatDataCenter.INSTANCE: ${e.message}")
        }
    }

    private fun resolveDataCenterMethods(dcClass: Class<*>) {
        val insertName = BreenoConfigProvider.RenderCard.dataCenterInsertMessageMethod
        val updateName = BreenoConfigProvider.RenderCard.dataCenterUpdateMessageMethod

        resolvedInsertMethod = dcClass.methods.firstOrNull {
            it.name == insertName && it.parameterTypes.size == 1
        } ?: dcClass.methods.firstOrNull {
            it.parameterTypes.size == 1 && viewBeanClass?.isAssignableFrom(it.parameterTypes[0]) == true
        }

        resolvedUpdateMethod = dcClass.methods.firstOrNull {
            it.name == updateName && it.parameterTypes.size == 2
        } ?: dcClass.methods.firstOrNull {
            it.parameterTypes.size == 2 &&
                    viewBeanClass?.isAssignableFrom(it.parameterTypes[0]) == true &&
                    it.parameterTypes[1] == java.lang.Boolean.TYPE
        }

        Logger.i(
            LOG_TAG,
            "Resolved DataCenter methods: insert=${resolvedInsertMethod?.name ?: "null"}, " +
                    "update=${resolvedUpdateMethod?.name ?: "null"}"
        )
    }

    /**
     * 流式渲染语义更新帧 [AssistantFrame.Update]。
     */
    suspend fun renderUpdate(
        turnId: Long,
        roomId: String,
        frame: AssistantFrame.Update,
    ) {
        val startedAtMs = System.currentTimeMillis()
        if (!ActiveTurnStore.isActiveInjection(turnId)) {
            Logger.d(LOG_TAG, "renderUpdate skipped inactive turnId=$turnId")
            if (frame.isFinal) {
                clearSession(turnId)
            }
            return
        }

        val beanClass = viewBeanClass ?: run {
            Logger.w(LOG_TAG, "renderUpdate skipped: viewBeanClass not resolved")
            return
        }

        val session = obtainRenderSession(turnId, roomId)
        val isFirst = frame.isFirst || session.bean == null

        if (isFirst) {
            val bean = try {
                beanClass.getDeclaredConstructor().newInstance()
            } catch (e: Throwable) {
                Logger.e(LOG_TAG, "Failed instantiating $beanClass: ${e.message}", e)
                return
            }

            // 基础元信息配置
            invokeCall(bean, BreenoConfigProvider.RenderCard.beanSetChatTypeMethod, BreenoConfigProvider.RenderCard.chatTypeAnswer)
            invokeCall(bean, BreenoConfigProvider.RenderCard.beanSetRoomIdMethod, roomId)
            invokeCall(bean, BreenoConfigProvider.RenderCard.beanSetRecordIdMethod, session.recordId)

            // 默认 Mock 方法集注入
            BreenoConfigProvider.RenderCard.mockBeanMethodsUnit.forEach { (methodName, value) ->
                invokeCall(bean, methodName, value)
            }

            // 默认 Mock LocalData 注入（标记自注入、防拦截）
            BreenoConfigProvider.RenderCard.mockBeanLocalDataUnit.forEach { (key, value) ->
                invokeCall(bean, BreenoConfigProvider.RenderCard.beanAddClientLocalDataMethod, key, value)
            }

            // 挂载点赞与复制操作栏
            BreenoFeedbackAssembler.attachIfNeeded(bean)
            session.bean = bean
            Logger.i(LOG_TAG, "Instantiated bean for turnId=$turnId recordId=${session.recordId}")
        }

        val bean = session.bean ?: return

        // 1. 装配 Thinking Block 原生字段
        if (frame.thinking != null) {
            val thinkingText = frame.thinking.text
            val isThinkingComplete = frame.thinking.isComplete

            // 首次出现思考内容时，重置动画标志并触发官方 reasoningStart 状态
            if (!session.isThinkingAnimInitialized) {
                invokeCall(bean, BreenoConfigProvider.RenderCard.beanSetHasReasoningAnimPlayedMethod, false)
                invokeCall(bean, BreenoConfigProvider.RenderCard.beanAddClientLocalDataMethod, BreenoConfigProvider.RenderCard.reasoningLocalDataStartKey, true)
                invokeCall(bean, BreenoConfigProvider.RenderCard.beanAddClientLocalDataMethod, BreenoConfigProvider.RenderCard.reasoningLocalDataExposureKey, true)
                session.isThinkingAnimInitialized = true
                Logger.i(LOG_TAG, "Initialized thinking animation for turnId=$turnId")
            }

            // 注入思考正文
            invokeCall(bean, BreenoConfigProvider.RenderCard.beanSetReasoningContentMethod, thinkingText)

            // 注入思考状态标题
            val runningTool = frame.tools.lastOrNull { it.status == ToolStatus.RUNNING }
            val reasoningStateText = when {
                runningTool != null -> "正在调用 [${runningTool.name}]..."
                isThinkingComplete -> BreenoConfigProvider.RenderCard.reasoningStateCompleteDefault
                else -> BreenoConfigProvider.RenderCard.reasoningStateThinkingDefault
            }
            invokeCall(bean, BreenoConfigProvider.RenderCard.beanSetReasoningStateMethod, reasoningStateText)

            Logger.d(
                LOG_TAG,
                "Thinking injected turnId=$turnId len=${thinkingText.length} " +
                        "state='$reasoningStateText' isComplete=$isThinkingComplete"
            )
        }

        // 2. 装配正文内容与切片标志
        invokeCall(bean, BreenoConfigProvider.RenderCard.beanSetContentMethod, frame.content)
        invokeCall(bean, BreenoConfigProvider.RenderCard.beanSetFinalMethod, frame.isFinal)
        invokeCall(bean, BreenoConfigProvider.RenderCard.beanSetFirstSliceMethod, frame.isFirst)

        // 重放需在每次 update 刷新的方法（如打字机动画已播放标记）
        BreenoConfigProvider.RenderCard.mockBeanMethodsUnit
            .filter { it.first in BreenoConfigProvider.RenderCard.mockBeanMethodsReplayOnUpdate }
            .forEach { (methodName, value) ->
                invokeCall(bean, methodName, value)
            }

        // 3. 提交给 DataCenter 渲染
        if (!session.isFirstInsertDone) {
            dispatchInsert(bean)
            session.isFirstInsertDone = true
            Logger.i(LOG_TAG, "Inserted card turnId=$turnId elapsedMs=${System.currentTimeMillis() - startedAtMs}")
        } else {
            dispatchUpdate(bean, false)
            Logger.d(
                LOG_TAG,
                "Updated card turnId=$turnId contentLen=${frame.content.length} " +
                        "isFinal=${frame.isFinal} elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
        }

        // 4. 终态收尾
        if (frame.isFinal) {
            val hideKey = BreenoConfigProvider.RenderCard.hideFeedbackViewLocalDataKey
            invokeCall(bean, BreenoConfigProvider.RenderCard.beanAddClientLocalDataMethod, hideKey, false)
            dispatchUpdate(bean, false)
            clearSession(turnId)
            Logger.i(
                LOG_TAG,
                "Finalized card turnId=$turnId contentLen=${frame.content.length} " +
                        "totalElapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
        }
    }

    /**
     * 渲染错误异常卡片
     */
    suspend fun renderError(
        turnId: Long,
        roomId: String,
        message: String,
    ) {
        val beanClass = viewBeanClass ?: return
        val session = obtainRenderSession(turnId, roomId)
        val bean = session.bean ?: try {
            beanClass.getDeclaredConstructor().newInstance().also {
                invokeCall(it, BreenoConfigProvider.RenderCard.beanSetChatTypeMethod, BreenoConfigProvider.RenderCard.chatTypeAnswer)
                invokeCall(it, BreenoConfigProvider.RenderCard.beanSetRoomIdMethod, roomId)
                invokeCall(it, BreenoConfigProvider.RenderCard.beanSetRecordIdMethod, session.recordId)
                BreenoConfigProvider.RenderCard.mockBeanLocalDataUnit.forEach { (key, value) ->
                    invokeCall(it, BreenoConfigProvider.RenderCard.beanAddClientLocalDataMethod, key, value)
                }
            }
        } catch (e: Throwable) {
            Logger.e(LOG_TAG, "Failed creating error bean: ${e.message}", e)
            return
        }

        invokeCall(bean, BreenoConfigProvider.RenderCard.beanSetContentMethod, message)
        invokeCall(bean, BreenoConfigProvider.RenderCard.beanSetFinalMethod, true)

        if (!session.isFirstInsertDone) {
            dispatchInsert(bean)
        } else {
            dispatchUpdate(bean, false)
        }
        clearSession(turnId)
        Logger.e(LOG_TAG, "Rendered error card turnId=$turnId: $message")
    }

    /**
     * 清理会话状态
     */
    suspend fun clearSession(turnId: Long? = null) {
        renderSessionMutex.withLock {
            if (turnId == null || currentRenderSession?.turnId == turnId) {
                currentRenderSession = null
                Logger.d(LOG_TAG, "RenderSession cleared (turnId=$turnId)")
            }
        }
    }

    private suspend fun obtainRenderSession(turnId: Long, roomId: String): BreenoRenderSession =
        renderSessionMutex.withLock {
            currentRenderSession?.takeIf { it.turnId == turnId } ?: BreenoRenderSession(
                turnId = turnId,
                roomId = roomId,
                recordId = UUID.randomUUID().toString().replace("-", ""),
            ).also { currentRenderSession = it }
        }

    private fun dispatchInsert(bean: Any) {
        val dc = dataCenterInstance
        if (dc == null) {
            Logger.w(LOG_TAG, "Cannot dispatch insert: dataCenterInstance is null")
            return
        }
        val method = resolvedInsertMethod
        if (method != null) {
            try {
                method.invoke(dc, bean)
                return
            } catch (e: Throwable) {
                Logger.e(LOG_TAG, "Failed invoking resolvedInsertMethod: ${e.message}", e)
            }
        }
        // 反射兜底
        invokeCall(dc, BreenoConfigProvider.RenderCard.dataCenterInsertMessageMethod, bean)
    }

    private fun dispatchUpdate(bean: Any, notifyOnly: Boolean) {
        val dc = dataCenterInstance
        if (dc == null) {
            Logger.w(LOG_TAG, "Cannot dispatch update: dataCenterInstance is null")
            return
        }
        val method = resolvedUpdateMethod
        if (method != null) {
            try {
                method.invoke(dc, bean, notifyOnly)
                return
            } catch (e: Throwable) {
                Logger.e(LOG_TAG, "Failed invoking resolvedUpdateMethod: ${e.message}", e)
            }
        }
        // 反射兜底
        invokeCall(dc, BreenoConfigProvider.RenderCard.dataCenterUpdateMessageMethod, bean, notifyOnly)
    }

    private fun invokeCall(target: Any, methodName: String, vararg args: Any?) {
        try {
            XposedHelpers.callMethod(target, methodName, *args)
        } catch (e: Throwable) {
            Logger.e(
                LOG_TAG,
                "invokeCall failed target=${target.javaClass.simpleName} " +
                        "method=$methodName args=[${args.joinToString()}]: ${e.message}",
                e
            )
        }
    }
}
