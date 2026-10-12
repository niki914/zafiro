package com.niki914.zafiro.app.trigger

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.res.stringResource
import com.niki914.logging.Logger
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.infra.component.OptionRow
import com.niki914.uikit.infra.component.OptionSheet
import com.niki914.zafiro.api.AgentManager
import com.niki914.zafiro.api.model.Attachment
import com.niki914.zafiro.api.model.ConversationId
import com.niki914.zafiro.api.model.DraftImage
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.conversation.ConversationRepo
import com.niki914.zafiro.business.application.ApplicationService
import com.niki914.zafiro.chat.agentic.ScreenCaptureIngestor
import com.niki914.zafiro.chat.agentic.accessibility.AccessibilityController
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ACTION_PROCESS_TEXT 入口：系统文本选择菜单里的「Zafiro」。
 *
 * 一次性透明 Activity，不驻留、不进最近任务。流程：
 * 解析选中文本 → 弹 OptionSheet 选动作 → 截图（按需及状态机补偿）→ 建新会话并启动持久化 Task Agent
 * → 依动作配置执行后台静默或通过统一入口置前 MainActivity。
 */
class TextActionActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 已选中动作并进入投递：dismissThen 会先回调 onDismissRequest，不能再顺手 finish。 */
    @Volatile
    private var dispatched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selectedText = TextSelectionInput.parse(
            intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        )
        if (selectedText == null) {
            Logger.i(LOG_TAG, "no usable selection, finishing")
            finish()
            return
        }

        showActionSheet(BuiltinTextAction.ALL, selectedText)
    }

    private fun showActionSheet(actions: List<BuiltinTextAction>, selectedText: String) {
        setContent {
            BaseTheme(paintWindowBackground = false) {
                OptionSheet(
                    visible = true,
                    title = stringResource(R.string.text_actions_sheet_title),
                    onDismissRequest = {
                        if (!dispatched) finish()
                    },
                ) { dismissThen ->
                    actions.forEach { action ->
                        OptionRow(
                            title = stringResource(action.titleRes),
                            onClick = {
                                val clickedAt = SystemClock.uptimeMillis()
                                dispatched = true
                                dismissThen {
                                    dispatch(action, selectedText, clickedAt)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * 分发选中的动作：
     * 1. 截图状态机（即时权限预检 + 动画耗时差额补偿，直接写入私有沙箱）；
     * 2. 会话建档并启动持久化 Task Agent；
     * 3. 前台动作经 ApplicationService 统一入口置前；后台动作直接静默完成。
     */
    private fun dispatch(action: BuiltinTextAction, selectedText: String, clickedAt: Long) {
        scope.launch {
            val readyImage = if (action.requiresScreenshot) {
                captureScreenshotSafely(clickedAt)
            } else {
                null
            }

            val prompt = TextActionPromptComposer.compose(action.promptTemplate, selectedText)
            val conversationId = UUID.randomUUID().toString()
            val prepared = runCatching {
                ConversationRepo.createConversation(
                    id = conversationId,
                    firstUserInput = prompt,
                    title = action.title,
                )
            }
            if (prepared.isFailure) {
                Logger.w(LOG_TAG, "create conversation failed reason=${prepared.exceptionOrNull()?.message}")
                runOnUiThread { finish() }
                return@launch
            }

            val agentManager = requireService<AgentManager>()
            val taskAgent = agentManager.createTaskAgent(tag = "text-action", isPersistent = true)
            taskAgent.load(ConversationId(conversationId))
            val initialImages = if (readyImage != null) listOf(readyImage) else emptyList()
            taskAgent.updateDraft {
                it.copy(
                    text = prompt,
                    images = initialImages,
                    files = emptyList(),
                )
            }

            val startResult = taskAgent.stream()
            Logger.i(
                LOG_TAG,
                "dispatch action=${action.id} result=$startResult runInBackground=${action.runInBackground} promptLen=${prompt.length}",
            )

            runOnUiThread {
                if (!action.runInBackground) {
                    requireService<ApplicationService>().launchMainActivity(conversationId)
                }
                finish()
            }
        }
    }

    private suspend fun captureScreenshotSafely(clickedAt: Long): DraftImage.Ready? {
        val hasPermission = AccessibilityController.ensureAccessibility().isSuccess
        if (!hasPermission) {
            Logger.w(LOG_TAG, "screenshot skipped: accessibility permission not granted")
            return null
        }

        val elapsed = SystemClock.uptimeMillis() - clickedAt
        if (elapsed < MIN_DISMISS_DELAY_MS) {
            delay(MIN_DISMISS_DELAY_MS - elapsed)
        }

        val path = ScreenCaptureIngestor.captureAndIngest() ?: run {
            Logger.w(LOG_TAG, "ScreenCaptureIngestor captureAndIngest returned null")
            return null
        }

        return DraftImage.Ready(Attachment(path = path, mimeType = "image/jpeg"))
    }

    private companion object {
        private const val LOG_TAG = "niki914_zafiro_TextActionActivity"
        private const val MIN_DISMISS_DELAY_MS = 350L
    }
}
