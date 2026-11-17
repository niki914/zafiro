// 保护：部件主题风格覆盖表导出导入往返保持一致；导入覆盖时替换本地配置。
package com.niki914.zafiro.backup

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UiStylesBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val harness get() = BackupApplyHarness(folder)

    @Test
    fun roundTripRestoresSavedUiStyles() {
        val store = harness.memoryStore()
        val styles = """{"overrides":{"composer":"md3","top_bar_plate":"liquid_glass"}}"""
        val file = harness.archive(
            harness.request(BackupGroup.Config),
            BackupSnapshot(uiStylesJson = styles),
        )
        store.uiStylesJson = """{"overrides":{}}"""

        harness.apply(file, setOf(BackupGroup.Config), BackupStrategy.Overwrite, store).succeeded()

        assertEquals(styles, store.uiStylesJson)
    }
}
