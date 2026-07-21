package com.niki914.nexus.agentic.app.ui.nexus.route

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.nexus.agentic.app.BuildConfig
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.app.ui.infra.ConfirmationLiquidDialog
import com.niki914.nexus.agentic.app.ui.infra.ProvideLiquidScreenContentForPreview
import com.niki914.nexus.agentic.app.ui.nexus.content.StartupPageContent
import com.niki914.nexus.agentic.app.ui.nexus.model.StartupAssistantUi
import com.niki914.nexus.agentic.app.ui.nexus.nav.NexusPage
import com.niki914.nexus.agentic.app.ui.nexus.nav.ProviderPickPage
import com.niki914.nexus.agentic.mod.WebSettings
import com.niki914.nexus.agentic.repo.WebSettingsFailureReason
import com.niki914.nexus.agentic.repo.WebSettingsResult
import com.niki914.nexus.agentic.repo.WebSettingsSource
import com.niki914.nexus.agentic.repo.XRepo
import com.niki914.nexus.base.BaseTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal typealias WebSettingsLoader = suspend (forceRetry: Boolean) -> WebSettingsResult

@Composable
internal fun StartupPageRoute(
    startupAssistantUi: StartupAssistantUi,
    onPush: (NexusPage) -> Unit,
    loadWebSettings: WebSettingsLoader = defaultWebSettingsLoader(),
    initialLoading: Boolean = false,
    initialDialog: StartupWebSettingsDialog? = debugStartupWebSettingsInitialDialog(),
) {
    val scope = rememberCoroutineScope()
    var isCheckingWebSettings by rememberSaveable {
        mutableStateOf(initialLoading)
    }
    var webSettingsDialog by rememberSaveable {
        mutableStateOf(initialDialog)
    }
    var retainedWebSettingsDialog by rememberSaveable {
        mutableStateOf(initialDialog)
    }
    var isWebSettingsDialogVisible by rememberSaveable {
        mutableStateOf(false)
    }

    LaunchedEffect(webSettingsDialog) {
        val dialog = webSettingsDialog
        if (dialog == null) {
            isWebSettingsDialogVisible = false
        } else {
            retainedWebSettingsDialog = dialog
            isWebSettingsDialogVisible = false
            withFrameNanos { }
            isWebSettingsDialogVisible = true
        }
    }

    fun enterNextPage() {
        webSettingsDialog = null
        onPush(ProviderPickPage)
    }

    fun handleWebSettingsResult(result: WebSettingsResult) {
        isCheckingWebSettings = false
        when (result) {
            is WebSettingsResult.Success -> {
                webSettingsDialog = when {
                    result.isFallbackVersion -> StartupWebSettingsDialog.UnsupportedVersion
                    result.settings.isBeta -> StartupWebSettingsDialog.Beta
                    else -> null
                }
                if (webSettingsDialog == null) {
                    enterNextPage()
                }
            }

            is WebSettingsResult.RequestFailed -> {
                webSettingsDialog = when (result.reason) {
                    WebSettingsFailureReason.NetworkUnavailable -> StartupWebSettingsDialog.NetworkError
                    WebSettingsFailureReason.ServerError,
                    WebSettingsFailureReason.UnsupportedVersion,
                    WebSettingsFailureReason.InvalidConfig -> StartupWebSettingsDialog.FetchFailed

                    WebSettingsFailureReason.IpcUnreachable -> StartupWebSettingsDialog.NetworkError
                }
            }

            is WebSettingsResult.IpcUnreachable -> {
                webSettingsDialog = StartupWebSettingsDialog.NetworkError
            }
        }
    }

    fun requestWebSettings(forceRetry: Boolean) {
        if (isCheckingWebSettings) {
            return
        }
        isCheckingWebSettings = true
        webSettingsDialog = null
        scope.launch {
            val result = loadWebSettings(forceRetry)
            handleWebSettingsResult(result)
        }
    }

    StartupPageContent(
        assistantUi = startupAssistantUi,
        isLoading = isCheckingWebSettings,
        onContinue = {
            if (startupAssistantUi == StartupAssistantUi.ChatOnly) {
                enterNextPage()
            } else {
                requestWebSettings(forceRetry = false)
            }
        },
    )

    retainedWebSettingsDialog?.let { dialog ->
        StartupWebSettingsDialogContent(
            dialog = dialog,
            visible = isWebSettingsDialogVisible,
            onEnterNextPage = ::enterNextPage,
            onRetry = {
                requestWebSettings(forceRetry = true)
            },
            onDismiss = {
                webSettingsDialog = null
            },
        )
    }
}

@Composable
private fun StartupWebSettingsDialogContent(
    dialog: StartupWebSettingsDialog,
    visible: Boolean,
    onEnterNextPage: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val titleRes = when (dialog) {
        StartupWebSettingsDialog.Beta -> R.string.ui_onboard_web_beta_title
        StartupWebSettingsDialog.FetchFailed -> R.string.ui_onboard_web_fetch_failed_title
        StartupWebSettingsDialog.UnsupportedVersion -> R.string.ui_onboard_web_unsupported_title
        StartupWebSettingsDialog.NetworkError -> R.string.ui_onboard_web_network_error_title
    }
    val bodyRes = when (dialog) {
        StartupWebSettingsDialog.Beta -> R.string.ui_onboard_web_beta_body
        StartupWebSettingsDialog.FetchFailed -> R.string.ui_onboard_web_fetch_failed_body
        StartupWebSettingsDialog.UnsupportedVersion -> R.string.ui_onboard_web_unsupported_body
        StartupWebSettingsDialog.NetworkError -> R.string.ui_onboard_web_network_error_body
    }
    val positiveTextRes = when (dialog) {
        StartupWebSettingsDialog.Beta,
        StartupWebSettingsDialog.UnsupportedVersion -> R.string.ui_onboard_web_confirm

        StartupWebSettingsDialog.FetchFailed,
        StartupWebSettingsDialog.NetworkError -> R.string.ui_onboard_web_enter_directly
    }
    val negativeTextRes = when (dialog) {
        StartupWebSettingsDialog.Beta,
        StartupWebSettingsDialog.UnsupportedVersion -> R.string.ui_onboard_web_cancel

        StartupWebSettingsDialog.FetchFailed,
        StartupWebSettingsDialog.NetworkError -> R.string.ui_onboard_web_retry
    }
    val onPositiveClick = when (dialog) {
        StartupWebSettingsDialog.Beta,
        StartupWebSettingsDialog.UnsupportedVersion,
        StartupWebSettingsDialog.FetchFailed,
        StartupWebSettingsDialog.NetworkError -> onEnterNextPage
    }
    val onNegativeClick = when (dialog) {
        StartupWebSettingsDialog.Beta,
        StartupWebSettingsDialog.UnsupportedVersion -> onDismiss

        StartupWebSettingsDialog.FetchFailed,
        StartupWebSettingsDialog.NetworkError -> onRetry
    }

    ConfirmationLiquidDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = stringResource(titleRes),
        text = stringResource(bodyRes),
        positiveButtonText = stringResource(positiveTextRes),
        negativeButtonText = stringResource(negativeTextRes),
        onPositiveClick = onPositiveClick,
        onNegativeClick = onNegativeClick,
        dismissOnBackgroundTap = false,
    )
}

internal enum class StartupWebSettingsDialog {
    Beta,
    FetchFailed,
    UnsupportedVersion,
    NetworkError,
}

private enum class StartupWebSettingsMockCase {
    Beta,
    FetchFailed,
    UnsupportedVersion,
    NetworkError,
}

// 调首屏弹窗时只在 Debug 包启用；改成 null 即恢复真实 WebSettings 请求。
private val DEBUG_STARTUP_WEB_SETTINGS_MOCK_CASE: StartupWebSettingsMockCase? =
    null // TODO P1 这个后面要删掉，之前都不记得有做了这个东西，结果调试包查了半天，才发现是这个问题

private fun defaultWebSettingsLoader(): WebSettingsLoader {
    val mockCase = DEBUG_STARTUP_WEB_SETTINGS_MOCK_CASE
    return if (BuildConfig.DEBUG && mockCase != null) {
        debugStartupWebSettingsLoader(mockCase)
    } else {
        realWebSettingsLoader()
    }
}

private fun realWebSettingsLoader(): WebSettingsLoader = { forceRetry ->
    if (forceRetry) {
        XRepo.web.retry()
    } else {
        XRepo.web.await()
    }
}

private fun debugStartupWebSettingsInitialDialog(): StartupWebSettingsDialog? {
    if (!BuildConfig.DEBUG) {
        return null
    }
    return DEBUG_STARTUP_WEB_SETTINGS_MOCK_CASE?.toDialog()
}

private fun debugStartupWebSettingsLoader(mockCase: StartupWebSettingsMockCase): WebSettingsLoader =
    {
        delay(60_000L)
        mockCase.toWebSettingsResult()
    }

private fun StartupWebSettingsMockCase.toDialog(): StartupWebSettingsDialog {
    return when (this) {
        StartupWebSettingsMockCase.Beta -> StartupWebSettingsDialog.Beta
        StartupWebSettingsMockCase.FetchFailed -> StartupWebSettingsDialog.FetchFailed
        StartupWebSettingsMockCase.UnsupportedVersion -> StartupWebSettingsDialog.UnsupportedVersion
        StartupWebSettingsMockCase.NetworkError -> StartupWebSettingsDialog.NetworkError
    }
}

private fun StartupWebSettingsMockCase.toWebSettingsResult(): WebSettingsResult {
    return when (this) {
        StartupWebSettingsMockCase.Beta -> mockWebSettingsSuccess(
            isBeta = true,
            isFallbackVersion = false
        )

        StartupWebSettingsMockCase.UnsupportedVersion -> mockWebSettingsSuccess(
            isBeta = false,
            isFallbackVersion = true,
        )

        StartupWebSettingsMockCase.FetchFailed -> {
            WebSettingsResult.RequestFailed(WebSettingsFailureReason.ServerError)
        }

        StartupWebSettingsMockCase.NetworkError -> {
            WebSettingsResult.RequestFailed(WebSettingsFailureReason.NetworkUnavailable)
        }
    }
}

private fun mockWebSettingsSuccess(
    isBeta: Boolean,
    isFallbackVersion: Boolean,
): WebSettingsResult.Success {
    val requestedVersionCode = 507013003L
    val resolvedVersionCode = if (isFallbackVersion) {
        507012000L
    } else {
        requestedVersionCode
    }
    return WebSettingsResult.Success(
        settings = WebSettings(
            JsonObject(
                mapOf(
                    "package_name" to JsonPrimitive("com.miui.voiceassist"),
                    "version_code" to JsonPrimitive(requestedVersionCode),
                    "requested_version_code" to JsonPrimitive(requestedVersionCode),
                    "resolved_version_code" to JsonPrimitive(resolvedVersionCode),
                    "is_beta" to JsonPrimitive(isBeta),
                    "config" to JsonObject(emptyMap()),
                )
            )
        ),
        requestedVersionCode = requestedVersionCode,
        resolvedVersionCode = resolvedVersionCode,
        source = WebSettingsSource.Network,
        isFallbackVersion = isFallbackVersion,
    )
}

@Preview(name = "Startup Normal", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun StartupPageRouteNormalPreview() {
    StartupPageRoutePreview()
}

@Preview(name = "Startup Loading", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun StartupPageRouteLoadingPreview() {
    StartupPageRoutePreview(initialLoading = true)
}

@Preview(name = "Startup Dialog Cycle", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun StartupPageRouteDialogCyclePreview() {
    StartupPageRouteDialogCyclePreviewContent()
}

@Composable
private fun StartupPageRoutePreview(
    darkTheme: Boolean = false,
    initialLoading: Boolean = false,
    initialDialog: StartupWebSettingsDialog? = null,
) {
    BaseTheme(darkTheme = darkTheme, dynamicColor = false) {
        Surface {
            ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
                StartupPageRoute(
                    startupAssistantUi = StartupAssistantUi.XiaoAi,
                    onPush = {},
                    loadWebSettings = previewLoadingWebSettingsLoader(),
                    initialLoading = initialLoading,
                    initialDialog = initialDialog,
                )
            }
        }
    }
}

@Composable
private fun StartupPageRouteDialogCyclePreviewContent() {
    val dialogCases = remember {
        listOf(
            StartupWebSettingsDialog.Beta,
            StartupWebSettingsDialog.UnsupportedVersion,
            StartupWebSettingsDialog.FetchFailed,
            StartupWebSettingsDialog.NetworkError,
        )
    }
    var nextCaseIndex by rememberSaveable {
        mutableStateOf(0)
    }
    var currentDialog by rememberSaveable {
        mutableStateOf<StartupWebSettingsDialog?>(null)
    }
    var retainedDialog by rememberSaveable {
        mutableStateOf<StartupWebSettingsDialog?>(null)
    }
    var isDialogVisible by rememberSaveable {
        mutableStateOf(false)
    }

    LaunchedEffect(currentDialog) {
        val dialog = currentDialog
        if (dialog == null) {
            isDialogVisible = false
        } else {
            retainedDialog = dialog
            isDialogVisible = false
            withFrameNanos { }
            isDialogVisible = true
        }
    }

    BaseTheme(darkTheme = false, dynamicColor = false) {
        Surface {
            ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
                StartupPageContent(
                    assistantUi = StartupAssistantUi.XiaoAi,
                    isLoading = false,
                    onContinue = {
                        currentDialog = dialogCases[nextCaseIndex]
                        nextCaseIndex = (nextCaseIndex + 1) % dialogCases.size
                    },
                )

                retainedDialog?.let { dialog ->
                    StartupWebSettingsDialogContent(
                        dialog = dialog,
                        visible = isDialogVisible,
                        onEnterNextPage = {
                            currentDialog = null
                        },
                        onRetry = {
                            currentDialog = null
                        },
                        onDismiss = {
                            currentDialog = null
                        },
                    )
                }
            }
        }
    }
}

private fun previewLoadingWebSettingsLoader(): WebSettingsLoader = {
    delay(60_000L)
    WebSettingsResult.RequestFailed(WebSettingsFailureReason.NetworkUnavailable)
}
