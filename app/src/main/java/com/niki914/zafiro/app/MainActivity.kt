package com.niki914.zafiro.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.niki914.logging.Logger
import com.niki914.zafiro.app.ui.ZafiroApp
import com.niki914.zafiro.app.ui.model.AppLaunchDecision
import com.niki914.zafiro.app.ui.model.ThemeController
import com.niki914.zafiro.business.application.ApplicationService
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.findService
import com.niki914.zafiro.service.requireService
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

    // launcher 必须在 STARTED 前注册：MainActivity 预注册 → ApplicationService 持有结果路由
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        requireService<ApplicationService>().onRuntimePermissionResult(granted)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // launcher 必须在 STARTED 前注册：预注册后装进 ApplicationService
        requireService<ApplicationService>()
            .installPermissionLauncher(permissionLauncher)
        val startupAssistantUi = resolveStartupAssistantUi()
        val launchDecision = runBlocking {
            val decision = AppLaunchDecision.resolve(startupAssistantUi)
            // 同步读主题偏好：深色模式冷启动首帧不能闪白
            ThemeController.load()
            // 回填型设置 flow 的冷启动回填（首帧真值，防“进设置页才生效”类 bug）
            runCatching { XRepo.hydrateSettings() }
                .onFailure { Logger.w("niki914_zafiro_Main", "hydrate failed ${it.message}") }
            decision
        }
        applyLanguageTag(launchDecision.languageTag)

        handleIncomingIntent(intent)

        setContent {
            ZafiroApp(
                startupAssistantUi = startupAssistantUi,
                launchDecision = launchDecision,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val targetConversationId = intent?.getStringExtra(ApplicationService.EXTRA_TARGET_CONVERSATION_ID)
        findService<ApplicationService>()?.handleNavigationIntent(targetConversationId)
    }

    override fun onResume() {
        super.onResume()
        // 前台跟踪由 ApplicationService 经 lifecycle callbacks 自动维护，无需手动转发
    }
}
