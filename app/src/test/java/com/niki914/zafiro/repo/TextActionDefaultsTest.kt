package com.niki914.zafiro.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextActionDefaultsTest {
    @Test
    fun namesAreUniqueAndPromptsNotBlank() {
        assertEquals(TextActionDefaults.all.size, TextActionDefaults.all.map { it.name }.toSet().size)
        assertTrue(TextActionDefaults.all.all { it.prompt.isNotBlank() && !it.requiresScreenshot })
    }

    @Test
    fun existingAndPreviouslyOfferedAreNotAddedAgain() {
        val toAdd = TextActionDefaults.toAdd(existingNames = setOf("Summarize"), alreadyOffered = setOf("Fix grammar"))
        assertTrue(toAdd.none { it.name == "Summarize" || it.name == "Fix grammar" })
        assertEquals(TextActionDefaults.all.size - 2, toAdd.size)
    }

    @Test
    fun nothingLeftToAddOnceEverythingWasOffered() {
        assertTrue(TextActionDefaults.toAdd(emptySet(), TextActionDefaults.all.map { it.name }.toSet()).isEmpty())
    }
}
