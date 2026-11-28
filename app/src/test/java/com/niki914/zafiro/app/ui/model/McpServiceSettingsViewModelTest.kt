package com.niki914.zafiro.app.ui.model

import android.content.Context
import android.content.ContextWrapper
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.api.McpService
import com.niki914.zafiro.api.McpServiceStatus
import com.niki914.zafiro.app.util.SilentLoggerRule
import com.niki914.zafiro.business.permission.Channel
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionResult
import com.niki914.zafiro.business.permission.PermissionScope
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.repo.FakeDomainSettingsStore
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.ServiceRegistry
import com.niki914.zafiro.service.installService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

// 保护：MCP 常亮开关打开或进入已开启的设置页时申请电池豁免，拒绝仍可打开，关闭不申请。
@OptIn(ExperimentalCoroutinesApi::class)
class McpServiceSettingsViewModelTest {

    @get:Rule
    val silentLoggerRule = SilentLoggerRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val tempDir = Files.createTempDirectory("test_mcp_keep_awake").toFile()

    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = tempDir
    }

    @Before
    fun setUp() {
        ContextProvider.provide(context)
        XRepo.installStoreForTest(FakeDomainSettingsStore())
        XRepo.init(context)
        installService<McpService>(FakeMcpService())
    }

    @After
    fun tearDown() {
        XRepo.resetForTest()
        ServiceRegistry.clearForTest()
    }

    @Test
    fun toggleKeepScreenOn_onRequestsExemptionAndStillEnablesWhenDenied() = runTest {
        val permissions = RecordingPermissionManager(PermissionState.DENIED_BY_USER)
        installService<PermissionManager>(permissions)
        XRepo.mcpService.setKeepScreenOnWhileRunning(false)

        val viewModel = McpServiceSettingsViewModel()
        viewModel.sendIntent(McpServiceSettingsIntent.Load)
        advanceUntilIdle()
        assertFalse(viewModel.uiStateFlow.value.keepScreenOn)
        assertTrue(permissions.requested.isEmpty())

        viewModel.sendIntent(McpServiceSettingsIntent.ToggleKeepScreenOn(true))
        advanceUntilIdle()

        assertEquals(listOf(Permission.BATTERY_OPTIMIZATION), permissions.requested)
        assertTrue(XRepo.mcpService.get().keepScreenOn)
    }

    @Test
    fun toggleKeepScreenOn_offDoesNotRequestExemption() = runTest {
        val permissions = RecordingPermissionManager(PermissionState.DENIED_BY_USER)
        installService<PermissionManager>(permissions)
        XRepo.mcpService.setKeepScreenOnWhileRunning(true)

        val viewModel = McpServiceSettingsViewModel()
        viewModel.sendIntent(McpServiceSettingsIntent.ToggleKeepScreenOn(false))
        advanceUntilIdle()

        assertTrue(permissions.requested.isEmpty())
        assertFalse(XRepo.mcpService.get().keepScreenOn)
    }

    @Test
    fun load_whenKeepScreenOnRequestsExemptionIfMissing() = runTest {
        val permissions = RecordingPermissionManager(PermissionState.DENIED_BY_USER)
        installService<PermissionManager>(permissions)
        XRepo.mcpService.setKeepScreenOnWhileRunning(true)

        val viewModel = McpServiceSettingsViewModel()
        viewModel.sendIntent(McpServiceSettingsIntent.Load)
        advanceUntilIdle()

        assertEquals(listOf(Permission.BATTERY_OPTIMIZATION), permissions.requested)
        assertTrue(viewModel.uiStateFlow.value.keepScreenOn)
    }

    @Test
    fun load_whenKeepScreenOnAlreadyGrantedDoesNotRequest() = runTest {
        val permissions = RecordingPermissionManager(PermissionState.GRANTED)
        installService<PermissionManager>(permissions)
        XRepo.mcpService.setKeepScreenOnWhileRunning(true)

        val viewModel = McpServiceSettingsViewModel()
        viewModel.sendIntent(McpServiceSettingsIntent.Load)
        advanceUntilIdle()

        assertTrue(permissions.requested.isEmpty())
        assertTrue(viewModel.uiStateFlow.value.keepScreenOn)
    }

    private class FakeMcpService : McpService {
        override val status = MutableStateFlow<McpServiceStatus>(McpServiceStatus.Stopped)
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun restart() = Unit
    }

    private class RecordingPermissionManager(
        private val state: PermissionState,
        private val requestResult: PermissionState = state,
    ) : PermissionManager {
        val requested = mutableListOf<Permission>()

        override fun status(permission: Permission): PermissionState = state

        override suspend fun request(permission: Permission): PermissionResult {
            requested += permission
            return PermissionResult(permission, requestResult, emptyList())
        }

        override suspend fun request(
            permission: Permission,
            vararg channels: Channel,
        ): PermissionResult = request(permission)

        override fun applyScope(vararg channels: Channel): PermissionScope =
            throw UnsupportedOperationException("not needed in test")
    }
}
