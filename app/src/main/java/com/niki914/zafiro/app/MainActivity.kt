package com.niki914.zafiro.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.niki914.zafiro.app.ui.ZafiroApp
import com.niki914.zafiro.app.ui.model.AppLaunchDecision
import com.niki914.zafiro.app.ui.model.ThemeController
import com.niki914.zafiro.chat.LLMController
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// tag:niki914 | tag:nexus-x-log | message:niki914 | message:nexus-x-log
class MainActivity : AppCompatActivity() {
    private fun applyLanguageTag(tag: String) {
        // 始终显式设置：空 tag = 清除应用内语言，回落系统；否则用户指定优先
        AppCompatDelegate.setApplicationLocales(
            if (tag.isBlank()) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(tag)
            },
        )
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        NotificationPermissionGate.init(notificationPermissionLauncher)
        val startupAssistantUi = resolveStartupAssistantUi()
        val launchDecision = runBlocking {
            val decision = AppLaunchDecision.resolve(startupAssistantUi)
            // 同步读主题偏好：深色模式冷启动首帧不能闪白
            ThemeController.load()
            decision
        }
        applyLanguageTag(launchDecision.languageTag)

        setContent {
            ZafiroApp(
                startupAssistantUi = startupAssistantUi,
                launchDecision = launchDecision,
            )
        }
        observeKeepScreenOn()
    }

    /**
     * Keep Alive：设置开关 && 回合进行中 → FLAG_KEEP_SCREEN_ON。
     * flag 只在 Activity 可见时生效，回桌面/锁屏自动失效，无泄漏风险。
     */
    private fun observeKeepScreenOn() {
        lifecycleScope.launch {
            // 设置开关在 Activity 创建时读一次；切换后下次冷启动生效
            // （ponytail: 不做设置变化热更新，低价值场景不值得一条额外流）
            val settingOn = runCatching { XRepo.keepScreenOn() }.getOrDefault(true)
            LLMController.keepScreenOn
                .collect { turnActive ->
                    val keepOn = settingOn && turnActive
                    if (keepOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
        }
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        ToolPermissionCoordinator.isUiResumed = true
    }

    override fun onPause() {
        super.onPause()
        isResumed = false
        ToolPermissionCoordinator.isUiResumed = false
    }

    companion object {

        /** 前后台标记：确认请求在后台时尝试 overlay 弹窗，无权限则静默拒绝。 */
        @Volatile
        var isResumed: Boolean = false
            private set
    }
}
