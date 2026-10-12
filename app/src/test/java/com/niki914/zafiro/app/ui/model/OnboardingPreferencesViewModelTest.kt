// 保护：Onboarding 一键开启及完成向导时，悬浮球与常驻通知功能开关与授权状态同步写入持久化存储（避免授权后功能静默未开启）。
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingPreferencesViewModelTest {

    @get:Rule
    val silentLoggerRule = SilentLoggerRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val tempDir = Files.createTempDirectory("test_onboarding_prefs").toFile()

    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = tempDir
    }

    @Before
    fun setUp() {
        ContextProvider.provide(context)
        XRepo.installStoreForTest(FakeDomainSettingsStore())
        XRepo.init(context)
    }

    @After
    fun tearDown() {
        ServiceRegistry.clearForTest()
        XRepo.resetForTest()
    }

    @Test
    fun requestAll_enablesFloatingBallAndResidentNotificationWhenGranted() = runTest {
        installService<PermissionManager>(FakePermissionManager(PermissionState.GRANTED))
        val viewModel = OnboardingPreferencesViewModel()

        assertFalse(XRepo.floatingBallEnabled())
        assertFalse(XRepo.residentNotificationEnabled())

        viewModel.sendIntent(OnboardingPreferencesIntent.RequestAll)
        advanceUntilIdle()

        assertTrue(XRepo.floatingBallEnabled())
        assertTrue(XRepo.residentNotificationEnabled())
    }

    @Test
    fun complete_enablesFloatingBallAndResidentNotificationWhenPermissionsGranted() = runTest {
        installService<PermissionManager>(FakePermissionManager(PermissionState.GRANTED))
        val viewModel = OnboardingPreferencesViewModel()

        assertFalse(XRepo.floatingBallEnabled())
        assertFalse(XRepo.residentNotificationEnabled())

        viewModel.sendIntent(OnboardingPreferencesIntent.Complete)
        advanceUntilIdle()

        assertTrue(XRepo.floatingBallEnabled())
        assertTrue(XRepo.residentNotificationEnabled())
        assertTrue(XRepo.onboardingCompleted())
    }

    private class FakePermissionManager(
        private val state: PermissionState,
    ) : PermissionManager {
        override fun status(permission: Permission): PermissionState = state
        override suspend fun request(permission: Permission): PermissionResult =
            PermissionResult(permission, state, emptyList())

        override suspend fun request(
            permission: Permission,
            vararg channels: Channel
        ): PermissionResult =
            PermissionResult(permission, state, emptyList())

        override fun applyScope(vararg channels: Channel): PermissionScope =
            object : PermissionScope {
                override suspend fun request(permission: Permission): PermissionResult =
                    PermissionResult(permission, state, emptyList())
            }
    }
}
