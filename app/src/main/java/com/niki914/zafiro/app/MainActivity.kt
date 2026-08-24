package com.niki914.zafiro.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.niki914.zafiro.app.ui.nexus.NexusApp
import com.niki914.zafiro.app.ui.nexus.model.AppLaunchDecision
import com.niki914.uikit.base.BaseTheme
import kotlinx.coroutines.runBlocking

// tag:niki914 | tag:nexus-x-log | message:niki914 | message:nexus-x-log
class MainActivity : ComponentActivity() {
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

        setContent {
            BaseTheme {
                NexusApp(
                    startupAssistantUi = startupAssistantUi,
                    launchDecision = launchDecision,
                )
            }
        }
    }

}
