package com.niki914.zafiro.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.niki914.zafiro.app.ui.ZafiroApp
import com.niki914.zafiro.app.ui.model.AppLaunchDecision
import com.niki914.uikit.base.BaseTheme
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
            AppLaunchDecision.resolve(startupAssistantUi)
        }
        applyLanguageTag(launchDecision.languageTag)

        setContent {
            BaseTheme {
                ZafiroApp(
                    startupAssistantUi = startupAssistantUi,
                    launchDecision = launchDecision,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
    }

    override fun onPause() {
        super.onPause()
        isResumed = false
    }

    companion object {

        /** 前后台标记：确认请求在后台时改为发通知（纯通知，无决策入口）。 */
        @Volatile
        var isResumed: Boolean = false
            private set
    }
}
