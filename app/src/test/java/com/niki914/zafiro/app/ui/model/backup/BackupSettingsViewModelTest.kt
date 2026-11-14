package com.niki914.zafiro.app.ui.model.backup

import com.niki914.zafiro.app.ui.model.MainDispatcherRule
import com.niki914.zafiro.backup.BackupGroup
import com.niki914.zafiro.backup.BackupStrategy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

// 保护：导入进行时不能把单关掉或改选项；取消回到选择且不弹失败；
// 导入成功停在重启且关不掉；失败回到导入，还原没成功时不说数据未动；
// 不合法的文件不出单；导出做好后才去保存，取消保存则单还在。
@OptIn(ExperimentalCoroutinesApi::class)
class BackupSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun restoringIgnoresDismissEditsAndAnotherOpen() = runTest {
        val work = HoldingBackupWork()
        val viewModel = BackupSettingsViewModel(work)
        try {
            viewModel.sendIntent(BackupSettingsIntent.OpenExport)
            viewModel.sendIntent(BackupSettingsIntent.Confirm)
            advanceUntilIdle()

            viewModel.sendIntent(BackupSettingsIntent.Dismiss)
            viewModel.sendIntent(BackupSettingsIntent.SetIncludeSecrets(true))
            viewModel.sendIntent(BackupSettingsIntent.SelectNone)
            viewModel.sendIntent(BackupSettingsIntent.OpenImport)
            advanceUntilIdle()

            val sheet = viewModel.uiStateFlow.value.sheet
            assertEquals(BackupSheetKind.Export, sheet?.kind)
            assertEquals(BackupSheetPhase.Restoring, sheet?.phase)
            assertEquals(false, sheet?.includeSecrets)
            assertEquals(backupSheetOptions.toSet(), sheet?.selected)
        } finally {
            work.release()
            advanceUntilIdle()
        }
    }

    @Test
    fun cancelImportReturnsToChoosingWithoutAFailure() = runTest {
        val work = HoldingBackupWork()
        work.inspect = InspectLook.Ok(listOf(BackupGroup.Conversations, BackupGroup.Tools))
        val viewModel = BackupSettingsViewModel(work)
        try {
            viewModel.sendIntent(BackupSettingsIntent.ImportStaged(scratch()))
            advanceUntilIdle()
            viewModel.sendIntent(BackupSettingsIntent.ToggleGroup(BackupGroup.Tools))
            viewModel.sendIntent(BackupSettingsIntent.Confirm)
            advanceUntilIdle()
            viewModel.sendIntent(BackupSettingsIntent.CancelRestore)
            advanceUntilIdle()
            work.release()
            advanceUntilIdle()

            val sheet = viewModel.uiStateFlow.value.sheet
            assertEquals(BackupSheetKind.Import, sheet?.kind)
            assertEquals(BackupSheetPhase.Choosing, sheet?.phase)
            assertEquals(setOf(BackupGroup.Conversations), sheet?.selected)
            assertNull(viewModel.uiStateFlow.value.failure)
        } finally {
            work.release()
        }
    }

    @Test
    fun successfulImportStaysOpenUntilRestartAndDismissDoesNothing() = runTest {
        val work = HoldingBackupWork()
        val viewModel = BackupSettingsViewModel(work)
        val effects = mutableListOf<BackupSettingsEffect>()
        val collect = launch { viewModel.uiEffect.collect { effects += it } }
        try {
            viewModel.sendIntent(BackupSettingsIntent.ImportStaged(scratch()))
            advanceUntilIdle()
            viewModel.sendIntent(BackupSettingsIntent.Confirm)
            advanceUntilIdle()
            work.release()
            advanceUntilIdle()

            viewModel.sendIntent(BackupSettingsIntent.Dismiss)
            viewModel.sendIntent(BackupSettingsIntent.Restart)
            advanceUntilIdle()

            val sheet = viewModel.uiStateFlow.value.sheet
            assertEquals(BackupSheetPhase.RestartRequired, sheet?.phase)
            assertTrue(effects.contains(BackupSettingsEffect.Relaunch))
        } finally {
            work.release()
            collect.cancel()
        }
    }

    @Test
    fun importFailureReturnsToImportAndOmitsTheUnchangedLineWhenRollbackFailed() = runTest {
        val work = HoldingBackupWork()
        work.applyResult = ImportOutcome.Failed(dataUnmodified = false, stackTrace = "boom")
        val viewModel = BackupSettingsViewModel(work)
        try {
            viewModel.sendIntent(BackupSettingsIntent.ImportStaged(scratch()))
            advanceUntilIdle()
            viewModel.sendIntent(BackupSettingsIntent.Confirm)
            advanceUntilIdle()
            work.release()
            advanceUntilIdle()

            val state = viewModel.uiStateFlow.value
            assertEquals(BackupSheetPhase.Choosing, state.sheet?.phase)
            assertEquals(BackupSheetKind.Import, state.sheet?.kind)
            assertEquals(false, state.failure?.dataUnmodified)
            assertEquals(BackupSheetKind.Import, state.failure?.kind)
        } finally {
            work.release()
        }
    }

    @Test
    fun rolledBackImportFailureSaysDataWasUnchanged() = runTest {
        val work = HoldingBackupWork()
        work.applyResult = ImportOutcome.Failed(dataUnmodified = true, stackTrace = "boom")
        val viewModel = BackupSettingsViewModel(work)
        try {
            viewModel.sendIntent(BackupSettingsIntent.ImportStaged(scratch()))
            advanceUntilIdle()
            viewModel.sendIntent(BackupSettingsIntent.Confirm)
            advanceUntilIdle()
            work.release()
            advanceUntilIdle()

            assertEquals(true, viewModel.uiStateFlow.value.failure?.dataUnmodified)
            assertEquals(BackupSheetPhase.Choosing, viewModel.uiStateFlow.value.sheet?.phase)
        } finally {
            work.release()
        }
    }

    @Test
    fun invalidFileDoesNotOpenTheSheet() = runTest {
        val work = HoldingBackupWork()
        work.inspect = InspectLook.Bad
        val viewModel = BackupSettingsViewModel(work)
        val effects = mutableListOf<BackupSettingsEffect>()
        val collect = launch { viewModel.uiEffect.collect { effects += it } }
        advanceUntilIdle()
        viewModel.sendIntent(BackupSettingsIntent.ImportStaged(scratch()))
        advanceUntilIdle()
        collect.cancel()

        assertNull(viewModel.uiStateFlow.value.sheet)
        assertTrue(effects.any { it is BackupSettingsEffect.ShowToast })
    }

    @Test
    fun importSheetListsOnlyGroupsPresentInTheFile() = runTest {
        val work = HoldingBackupWork()
        work.inspect = InspectLook.Ok(listOf(BackupGroup.Skills, BackupGroup.Conversations))
        val viewModel = BackupSettingsViewModel(work)
        viewModel.sendIntent(BackupSettingsIntent.ImportStaged(scratch()))
        advanceUntilIdle()

        val sheet = viewModel.uiStateFlow.value.sheet
        assertEquals(listOf(BackupGroup.Skills, BackupGroup.Conversations), sheet?.options)
        assertEquals(setOf(BackupGroup.Skills, BackupGroup.Conversations), sheet?.selected)
        viewModel.sendIntent(BackupSettingsIntent.SelectNone)
        viewModel.sendIntent(BackupSettingsIntent.SelectAll)
        advanceUntilIdle()
        assertEquals(
            setOf(BackupGroup.Skills, BackupGroup.Conversations),
            viewModel.uiStateFlow.value.sheet?.selected,
        )
    }

    @Test
    fun exportOpensTheSavePickerAndCancelKeepsTheSheet() = runTest {
        val work = HoldingBackupWork()
        work.export = ExportPrep.Ready("/tmp/zafiro-export.zafiro")
        val viewModel = BackupSettingsViewModel(work)
        val effects = mutableListOf<BackupSettingsEffect>()
        val collect = launch { viewModel.uiEffect.collect { effects += it } }
        try {
            viewModel.sendIntent(BackupSettingsIntent.OpenExport)
            viewModel.sendIntent(BackupSettingsIntent.Confirm)
            advanceUntilIdle()
            work.release()
            advanceUntilIdle()

            assertEquals(BackupSheetPhase.Restoring, viewModel.uiStateFlow.value.sheet?.phase)
            assertTrue(effects.contains(BackupSettingsEffect.OpenSavePicker))

            viewModel.sendIntent(BackupSettingsIntent.SaveCancelled)
            advanceUntilIdle()
            assertEquals(BackupSheetPhase.Choosing, viewModel.uiStateFlow.value.sheet?.phase)
            assertTrue(effects.none { it is BackupSettingsEffect.ShowToast })
        } finally {
            work.release()
            collect.cancel()
        }
    }

    @Test
    fun exportSaveClosesTheSheetAndToasts() = runTest {
        val work = HoldingBackupWork()
        val viewModel = BackupSettingsViewModel(work)
        val effects = mutableListOf<BackupSettingsEffect>()
        val collect = launch { viewModel.uiEffect.collect { effects += it } }
        try {
            viewModel.sendIntent(BackupSettingsIntent.OpenExport)
            viewModel.sendIntent(BackupSettingsIntent.Confirm)
            advanceUntilIdle()
            work.release()
            advanceUntilIdle()
            viewModel.sendIntent(BackupSettingsIntent.SaveCompleted)
            advanceUntilIdle()

            assertNull(viewModel.uiStateFlow.value.sheet)
            assertTrue(effects.any { it is BackupSettingsEffect.ShowToast })
        } finally {
            work.release()
            collect.cancel()
        }
    }

    @Test
    fun emptySelectionDoesNotStart() = runTest {
        val viewModel = BackupSettingsViewModel(HoldingBackupWork())
        viewModel.sendIntent(BackupSettingsIntent.OpenExport)
        viewModel.sendIntent(BackupSettingsIntent.SelectNone)
        viewModel.sendIntent(BackupSettingsIntent.Confirm)
        advanceUntilIdle()

        assertEquals(BackupSheetPhase.Choosing, viewModel.uiStateFlow.value.sheet?.phase)
    }

    private fun scratch(): String = File.createTempFile("backup-stage", ".zafiro").apply {
        deleteOnExit()
    }.absolutePath
}

private class HoldingBackupWork : BackupSettingsWork {
    var inspect: InspectLook = InspectLook.Ok(backupSheetOptions)
    var export: ExportPrep = ExportPrep.Ready("/tmp/zafiro-export.zafiro")
    var applyResult: ImportOutcome = ImportOutcome.Success
    private var waiting: Continuation<Unit>? = null

    override suspend fun prepareExport(
        groups: Set<BackupGroup>,
        includeSecrets: Boolean,
        isCancelled: () -> Boolean,
    ): ExportPrep {
        hold()
        if (isCancelled()) return ExportPrep.Cancelled
        return export
    }

    override suspend fun inspect(file: File): InspectLook = inspect

    override suspend fun apply(
        file: File,
        groups: Set<BackupGroup>,
        strategy: BackupStrategy,
        isCancelled: () -> Boolean,
    ): ImportOutcome {
        hold()
        if (isCancelled()) return ImportOutcome.Cancelled
        return applyResult
    }

    private suspend fun hold() {
        suspendCancellableCoroutine { continuation ->
            waiting = continuation
        }
    }

    fun release() {
        val pending = waiting
        waiting = null
        pending?.resume(Unit)
    }
}
