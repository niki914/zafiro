package com.niki914.zafiro.app.ui.model

import androidx.test.core.app.ApplicationProvider
import com.niki914.zafiro.repo.FakeDomainSettingsStore
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AboutSettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule =
        MainDispatcherRule()

    @Before
    fun setUp() {
        XRepo.installStoreForTest(FakeDomainSettingsStore())
        XRepo.init(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        XRepo.resetForTest()
    }

    @Test
    fun openItem_withGithub_emitsOpenUri() = runTest {
        val viewModel = AboutSettingsViewModel()
        val effectDeferred = async { viewModel.uiEffect.first() }

        viewModel.sendIntent(AboutSettingsIntent.OpenItem(AboutSettingsItemId.Github))
        advanceUntilIdle()

        assertEquals(
            AboutSettingsEffect.OpenUri("https://github.com/niki914/zafiro"),
            effectDeferred.await(),
        )
    }

    @Test
    fun openItem_withFeatureFeedback_emitsOpenFeedbackIssue() = runTest {
        val viewModel = AboutSettingsViewModel()
        val effectDeferred = async { viewModel.uiEffect.first() }

        viewModel.sendIntent(AboutSettingsIntent.OpenItem(AboutSettingsItemId.FeatureFeedback))
        advanceUntilIdle()

        val effect = effectDeferred.await() as AboutSettingsEffect.OpenFeedbackIssue
        assertEquals("[FEATURE] ", effect.title)
    }

    @Test
    fun openItem_withBugFeedback_emitsOpenFeedbackIssue() = runTest {
        val viewModel = AboutSettingsViewModel()
        val effectDeferred = async { viewModel.uiEffect.first() }

        viewModel.sendIntent(AboutSettingsIntent.OpenItem(AboutSettingsItemId.BugFeedback))
        advanceUntilIdle()

        val effect = effectDeferred.await() as AboutSettingsEffect.OpenFeedbackIssue
        assertEquals("[BUG] ", effect.title)
    }

    @Test
    fun toggleTelemetry_updatesStateAndPersists() = runTest {
        val viewModel = AboutSettingsViewModel()
        assertTrue(viewModel.uiStateFlow.value.telemetryEnabled)

        viewModel.sendIntent(AboutSettingsIntent.ToggleTelemetry(false))
        advanceUntilIdle()

        assertFalse(viewModel.uiStateFlow.value.telemetryEnabled)
        assertFalse(XRepo.telemetryEnabled())
    }

    @Test
    fun load_readsTelemetryEnabledFromRepo() = runTest {
        XRepo.setTelemetryEnabled(false)
        val viewModel = AboutSettingsViewModel()

        viewModel.sendIntent(AboutSettingsIntent.Load)
        advanceUntilIdle()

        assertFalse(viewModel.uiStateFlow.value.telemetryEnabled)
    }

    @Test
    fun showAndDismissPrivacyDialog_transitionsState() = runTest {
        val viewModel = AboutSettingsViewModel()
        assertFalse(viewModel.uiStateFlow.value.showPrivacyDialog)

        viewModel.sendIntent(AboutSettingsIntent.ShowPrivacyDialog)
        advanceUntilIdle()
        assertTrue(viewModel.uiStateFlow.value.showPrivacyDialog)

        viewModel.sendIntent(AboutSettingsIntent.DismissPrivacyDialog)
        advanceUntilIdle()
        assertFalse(viewModel.uiStateFlow.value.showPrivacyDialog)
    }

    @Test
    fun openTelemetryDoc_closesDialogAndEmitsUri() = runTest {
        val viewModel = AboutSettingsViewModel()
        viewModel.sendIntent(AboutSettingsIntent.ShowPrivacyDialog)
        advanceUntilIdle()
        assertTrue(viewModel.uiStateFlow.value.showPrivacyDialog)

        val effectDeferred = async { viewModel.uiEffect.first() }
        viewModel.sendIntent(AboutSettingsIntent.OpenTelemetryDoc)
        advanceUntilIdle()

        assertFalse(viewModel.uiStateFlow.value.showPrivacyDialog)
        assertEquals(
            AboutSettingsEffect.OpenUri(TELEMETRY_DOC_URL),
            effectDeferred.await(),
        )
    }
}
