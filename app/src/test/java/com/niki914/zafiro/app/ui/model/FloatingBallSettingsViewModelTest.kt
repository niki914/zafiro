package com.niki914.zafiro.app.ui.model

import android.content.Context
import android.content.ContextWrapper
import com.niki914.xposed.api.util.ContextProvider
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

// 保护：悬浮球设置的加载、开关落盘、权限门禁与参数范围约束。
@OptIn(ExperimentalCoroutinesApi::class)
class FloatingBallSettingsViewModelTest {

    @get:Rule
    val silentLoggerRule = SilentLoggerRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val tempDir = Files.createTempDirectory("test_files").toFile()

    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = tempDir
    }

    private fun installPermission(state: PermissionState) {
        installService<PermissionManager>(FakePermissionManager(state))
    }

    @Before
    fun setUp() {
        ContextProvider.provide(context)
        XRepo.installStoreForTest(FakeDomainSettingsStore())
        XRepo.init(context)
    }

    @After
    fun tearDown() {
        XRepo.resetForTest()
        ServiceRegistry.clearForTest()
    }

    @Test
    fun load_populatesSettingsFromRepo() = runTest {
        XRepo.setFloatingBallEnabled(true)
        XRepo.setFloatingBallAutoExpand(false)
        XRepo.setFloatingBallOpacityPercent(80)
        XRepo.setFloatingBallSubmergedOffsetDp(15)

        val viewModel = FloatingBallSettingsViewModel()
        viewModel.sendIntent(FloatingBallSettingsIntent.Load)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertTrue(state.enabled)
        assertFalse(state.autoExpand)
        assertEquals(80, state.opacityPercent)
        assertEquals(15, state.submergedOffsetDp)
    }

    @Test
    fun toggleEnabled_whenPermissionGranted_updatesStateAndRepo() = runTest {
        installPermission(PermissionState.GRANTED)
        val viewModel = FloatingBallSettingsViewModel()

        assertFalse(viewModel.uiStateFlow.value.enabled)
        assertFalse(XRepo.floatingBallEnabled())

        viewModel.sendIntent(FloatingBallSettingsIntent.ToggleEnabled(true))
        advanceUntilIdle()

        assertTrue(viewModel.uiStateFlow.value.enabled)
        assertTrue(XRepo.floatingBallEnabled())

        viewModel.sendIntent(FloatingBallSettingsIntent.ToggleEnabled(false))
        advanceUntilIdle()

        assertFalse(viewModel.uiStateFlow.value.enabled)
        assertFalse(XRepo.floatingBallEnabled())
    }

    @Test
    fun toggleEnabled_whenPermissionMissing_staysOffAndEmitsNoChange() = runTest {
        installPermission(PermissionState.DENIED_BY_USER)
        val viewModel = FloatingBallSettingsViewModel()

        viewModel.sendIntent(FloatingBallSettingsIntent.ToggleEnabled(true))
        advanceUntilIdle()

        assertFalse(viewModel.uiStateFlow.value.enabled)
        assertFalse(XRepo.floatingBallEnabled())
    }

    @Test
    fun toggleAutoExpand_updatesStateAndRepo() = runTest {
        val viewModel = FloatingBallSettingsViewModel()

        assertTrue(viewModel.uiStateFlow.value.autoExpand)
        assertTrue(XRepo.floatingBallAutoExpand())

        viewModel.sendIntent(FloatingBallSettingsIntent.ToggleAutoExpand(false))
        advanceUntilIdle()

        assertFalse(viewModel.uiStateFlow.value.autoExpand)
        assertFalse(XRepo.floatingBallAutoExpand())
    }

    @Test
    fun selectOpacity_coercesAndWritesToRepo() = runTest {
        val viewModel = FloatingBallSettingsViewModel()

        viewModel.sendIntent(FloatingBallSettingsIntent.SelectOpacity(75))
        advanceUntilIdle()

        assertEquals(75, viewModel.uiStateFlow.value.opacityPercent)
        assertEquals(75, XRepo.floatingBallOpacityPercent())

        viewModel.sendIntent(FloatingBallSettingsIntent.SelectOpacity(5))
        advanceUntilIdle()

        assertEquals(10, viewModel.uiStateFlow.value.opacityPercent)
        assertEquals(10, XRepo.floatingBallOpacityPercent())

        viewModel.sendIntent(FloatingBallSettingsIntent.SelectOpacity(150))
        advanceUntilIdle()

        assertEquals(100, viewModel.uiStateFlow.value.opacityPercent)
        assertEquals(100, XRepo.floatingBallOpacityPercent())
    }

    @Test
    fun selectSubmergedOffset_coercesAndWritesToRepo() = runTest {
        val viewModel = FloatingBallSettingsViewModel()

        viewModel.sendIntent(FloatingBallSettingsIntent.SelectSubmergedOffset(20))
        advanceUntilIdle()

        assertEquals(20, viewModel.uiStateFlow.value.submergedOffsetDp)
        assertEquals(20, XRepo.floatingBallSubmergedOffsetDp())

        viewModel.sendIntent(FloatingBallSettingsIntent.SelectSubmergedOffset(50))
        advanceUntilIdle()

        assertEquals(35, viewModel.uiStateFlow.value.submergedOffsetDp)
        assertEquals(35, XRepo.floatingBallSubmergedOffsetDp())
    }

    private class FakePermissionManager(
        private val state: PermissionState,
    ) : PermissionManager {
        override fun status(permission: Permission): PermissionState = state

        override suspend fun request(permission: Permission): PermissionResult =
            PermissionResult(permission, state, emptyList())

        override suspend fun request(
            permission: Permission,
            vararg channels: Channel,
        ): PermissionResult = PermissionResult(permission, state, emptyList())

        override fun applyScope(vararg channels: Channel): PermissionScope =
            throw UnsupportedOperationException("not needed in test")
    }
}
