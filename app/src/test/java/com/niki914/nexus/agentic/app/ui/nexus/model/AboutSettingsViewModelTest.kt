package com.niki914.nexus.agentic.app.ui.nexus.model

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AboutSettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun openItem_withGithub_emitsOpenUri() = runTest {
        val viewModel = AboutSettingsViewModel()
        val effectDeferred = async { viewModel.uiEffect.first() }

        viewModel.sendIntent(AboutSettingsIntent.OpenItem(AboutSettingsItemId.Github))
        advanceUntilIdle()

        assertEquals(
            AboutSettingsEffect.OpenUri("https://github.com/niki914/agentic-nexus"),
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
}
