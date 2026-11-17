// 保护：快捷提示词按 name 合并或覆盖。包里没有这个分区时不动本地；导出导入往返保持内容一致。
package com.niki914.zafiro.backup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QuickPromptBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val harness get() = BackupApplyHarness(folder)

    @Test
    fun roundTripRestoresSavedQuickPrompts() {
        val store = harness.memoryStore()
        val prompts = """{"prompts":[{"name":"btw","prompt":"by the way","enabled":true}]}"""
        val file = harness.archive(
            harness.request(BackupGroup.Tools),
            BackupSnapshot(quickPromptsJson = prompts),
        )
        store.quickPromptsJson = """{"prompts":[]}"""

        harness.apply(file, setOf(BackupGroup.Tools), BackupStrategy.Overwrite, store).succeeded()

        assertEquals(prompts, store.quickPromptsJson)
    }

    @Test
    fun mergeStrategyCombinesAndDeduplicatesByName() {
        val store = harness.memoryStore()
        store.quickPromptsJson =
            """{"prompts":[{"name":"local","prompt":"local prompt","enabled":true},{"name":"shared","prompt":"local shared","enabled":true}]}"""
        val imported =
            """{"prompts":[{"name":"shared","prompt":"imported shared","enabled":true},{"name":"incoming","prompt":"incoming prompt","enabled":true}]}"""
        val file = harness.archive(
            harness.request(BackupGroup.Tools),
            BackupSnapshot(quickPromptsJson = imported),
        )

        harness.apply(file, setOf(BackupGroup.Tools), BackupStrategy.Merge, store).succeeded()

        val parsed = promptPairs(store.quickPromptsJson)
        assertEquals(
            listOf("local" to "local prompt", "shared" to "local shared", "incoming" to "incoming prompt"),
            parsed,
        )
    }

    private fun promptPairs(json: String): List<Pair<String, String>> {
        val prompts = parseObjectOrNull(json)!!["prompts"] as JsonArray
        return prompts.map { element ->
            val obj = element as JsonObject
            (obj["name"] as JsonPrimitive).content to (obj["prompt"] as JsonPrimitive).content
        }
    }
}
