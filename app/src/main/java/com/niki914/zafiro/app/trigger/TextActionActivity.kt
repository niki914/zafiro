package com.niki914.zafiro.app.trigger

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import com.niki914.zafiro.repo.RuntimeTextAction
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

        val actions = runBlocking {
            runCatching { XRepo.textActions.list() }
                .onFailure { Logger.w(LOG_TAG, "load actions failed reason=${it.message}") }
                .getOrDefault(emptyList())
        }.filter { it.enabled }

        showActionSheet(actions, selectedText)
    }

    private fun showActionSheet(actions: List<RuntimeTextAction>, selectedText: String) {
        setContent {
            BaseTheme(paintWindowBackground = false) {
                OptionSheet(
                    visible = true,
                    title = stringResource(R.string.text_actions_sheet_title),
                    onDismissRequest = {
                        if (!dispatched) finish()
                    },
                ) { dismissThen ->
                    if (actions.isEmpty()) {
                        Text(
                            text = stringResource(R.string.text_actions_sheet_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                        )
                    }
                    actions.forEach { action ->
                        OptionRow(
                            title = action.name,
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
    private fun dispatch(action: RuntimeTextAction, selectedText: String, clickedAt: Long) {
        scope.launch {
            val capture =
                if (action.requiresScreenshot) captureScreenshotSafely(clickedAt) else null
            val captureErr = capture as? ScreenCaptureIngestor.CaptureResult.Err
            val readyImage = (capture as? ScreenCaptureIngestor.CaptureResult.Ok)
                ?.let { DraftImage.Ready(Attachment(path = it.path, mimeType = "image/jpeg")) }

            val prompt = TextActionPromptComposer.compose(
                template = action.prompt,
                selectedText = selectedText,
                captureFailed = captureErr != null,
                captureFailure = captureErr?.message,
            )
            val conversationId = UUID.randomUUID().toString()
            val prepared = runCatching {
                ConversationRepo.createConversation(
                    id = conversationId,
                    firstUserInput = prompt,
                    title = action.name,
                )
            }
            if (prepared.isFailure) {
                Logger.w(
                    LOG_TAG,
                    "create conversation failed reason=${prepared.exceptionOrNull()?.message}"
                )
                runOnUiThread { finish() }
                return@launch
            }

            val agentManager = requireService<AgentManager>()
            val taskAgent = agentManager.createTaskAgent(isPersistent = true)
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
                "dispatch action=${action.name} result=$startResult runInBackground=${action.runInBackground} promptLen=${prompt.length}",
            )

            runOnUiThread {
                if (!action.runInBackground) {
                    requireService<ApplicationService>().launchMainActivity(conversationId)
                }
                finish()
            }
        }
    }

    /** 失败原因原样带回：文案（含开无障碍的指引）要进 prompt 交给模型转告用户。 */
    private suspend fun captureScreenshotSafely(clickedAt: Long): ScreenCaptureIngestor.CaptureResult {
        val elapsed = SystemClock.uptimeMillis() - clickedAt
        if (elapsed < MIN_DISMISS_DELAY_MS) {
            delay(MIN_DISMISS_DELAY_MS - elapsed)
        }

        return ScreenCaptureIngestor.captureAndIngest().also { result ->
            if (result is ScreenCaptureIngestor.CaptureResult.Err) {
                Logger.w(
                    LOG_TAG,
                    "screenshot failed stage=${result.stage} message=${result.message}"
                )
            }
        }
    }

    private companion object {
        private const val LOG_TAG = "niki914_zafiro_TextActionActivity"
        private const val MIN_DISMISS_DELAY_MS = 350L
    }
}
