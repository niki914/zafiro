package com.niki914.zafiro.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupScopeTest {
    @Test
    fun agentWorkspacesAreBackedUp() {
        assertTrue(BackupManager.includesFile("zafiro-autonomous/engine/zaf/api.py"))
        assertTrue(BackupManager.includesFile("substack/01_CONTENT_PLAN.md"))
        assertTrue(BackupManager.includesFile("journal.md"))
        assertTrue(BackupManager.includesFile("settings/agents/main/memory.json"))
        assertTrue(BackupManager.includesFile("skills/substack-notes/SKILL.md"))
    }

    @Test
    fun scratchAndRestoreMachineryAreSkipped() {
        assertFalse(BackupManager.includesFile("tool_output/autonomy.db"))
        assertFalse(BackupManager.includesFile("logs/a.log"))
        assertFalse(BackupManager.includesFile("pending_restore/files/x"))
        assertFalse(BackupManager.includesFile("restore_undo/1/x"))
        assertFalse(BackupManager.includesFile("settings/llm/model_facts.json"))
    }
}
