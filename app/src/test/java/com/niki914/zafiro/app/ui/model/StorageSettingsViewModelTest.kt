package com.niki914.zafiro.app.ui.model

import com.niki914.zafiro.app.util.SilentLoggerRule
import com.niki914.zafiro.repo.StorageKind
import com.niki914.zafiro.repo.StorageUsage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StorageSettingsViewModelTest {
    @get:Rule
    val silentLoggerRule = SilentLoggerRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun load_withUsage_showsCategoriesAndSizes() = runTest {
        val viewModel = StorageSettingsViewModel(
            FakeStorageUsageProvider(
                usage = listOf(
                    StorageUsage(StorageKind.ToolOutput, 1024L),
                    StorageUsage(StorageKind.ImageCache, 2_097_152L),
                ),
            ),
        )

        viewModel.sendIntent(StorageSettingsIntent.Load)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertFalse(state.isLoading)
        assertEquals(4, state.categories.size)
        assertEquals(1024L, state.categories[0].bytes)
        assertEquals(2_097_152L, state.categories[1].bytes)
        assertEquals(0L, state.categories[2].bytes)
        assertEquals(0L, state.categories[3].bytes)
        assertNull(state.inlineError)
    }

    @Test
    fun load_whenProviderThrows_setsLoadFailed() = runTest {
        val viewModel = StorageSettingsViewModel(
            FakeStorageUsageProvider(loadThrowable = IllegalStateException("boom")),
        )

        viewModel.sendIntent(StorageSettingsIntent.Load)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertFalse(state.isLoading)
        assertTrue(state.inlineError is StorageInlineError.LoadFailed)
    }

    @Test
    fun confirmClear_clearsCategoryAndReloads() = runTest {
        val provider = FakeStorageUsageProvider(
            usage = listOf(StorageUsage(StorageKind.Downloads, 4096L)),
        )
        val viewModel = StorageSettingsViewModel(provider)
        viewModel.sendIntent(StorageSettingsIntent.Load)
        advanceUntilIdle()

        viewModel.sendIntent(StorageSettingsIntent.RequestClear(StorageCategory.Downloads))
        advanceUntilIdle()
        viewModel.sendIntent(StorageSettingsIntent.ConfirmClear)
        advanceUntilIdle()

        assertEquals(listOf(StorageKind.Downloads), provider.cleared)
        val state = viewModel.uiStateFlow.value
        assertNull(state.confirmation)
        assertFalse(state.categories[2].isClearing)
        assertNull(state.inlineError)
    }

    @Test
    fun confirmClear_whenProviderThrows_setsClearFailed() = runTest {
        val viewModel = StorageSettingsViewModel(
            FakeStorageUsageProvider(clearThrowable = IllegalStateException("boom")),
        )
        viewModel.sendIntent(StorageSettingsIntent.Load)
        advanceUntilIdle()

        viewModel.sendIntent(StorageSettingsIntent.RequestClear(StorageCategory.OtherCache))
        advanceUntilIdle()
        viewModel.sendIntent(StorageSettingsIntent.ConfirmClear)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertNull(state.confirmation)
        assertTrue(state.inlineError is StorageInlineError.ClearFailed)
    }

    @Test
    fun formatStorageBytes_formatsUnits() {
        assertEquals("0 B", formatStorageBytes(0L))
        assertEquals("512 B", formatStorageBytes(512L))
        assertEquals("1.0 KB", formatStorageBytes(1024L))
        assertEquals("12.0 MB", formatStorageBytes(12_582_912L))
        assertEquals("2.0 GB", formatStorageBytes(2_147_483_648L))
    }

    private class FakeStorageUsageProvider(
        private val usage: List<StorageUsage> = emptyList(),
        private val loadThrowable: Throwable? = null,
        private val clearThrowable: Throwable? = null,
    ) : StorageUsageProvider {
        val cleared = mutableListOf<StorageKind>()

        override suspend fun usage(): List<StorageUsage> {
            loadThrowable?.let { throw it }
            return usage
        }

        override suspend fun clear(kind: StorageKind) {
            clearThrowable?.let { throw it }
            cleared += kind
        }
    }
}
