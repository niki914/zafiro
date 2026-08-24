package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.stream.LocalToolResultClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalToolResultClassifierTest {
    @Test
    fun failureMessage_returnsNullForZeroExitCode() {
        val message = LocalToolResultClassifier.failureMessage(
            """{"exit_code":0,"stdout":"ok"}"""
        )

        assertNull(message)
    }

    @Test
    fun failureMessage_usesStderrForNonZeroExitCode() {
        val message = LocalToolResultClassifier.failureMessage(
            """{"exit_code":1,"stderr":"not found"}"""
        )

        assertEquals("not found", message)
    }

    @Test
    fun failureMessage_usesNestedErrorMessageForStructuredToolError() {
        val message = LocalToolResultClassifier.failureMessage(
            """{"error":{"code":"SESSION_NOT_FOUND","message":"Call open first"}}"""
        )

        assertEquals("Call open first", message)
    }

    @Test
    fun failureMessage_usesNestedErrorCodeWhenErrorMessageMissing() {
        val message = LocalToolResultClassifier.failureMessage(
            """{"error":{"code":"TIMEOUT"}}"""
        )

        assertEquals("TIMEOUT", message)
    }

    @Test
    fun failureMessage_ignoresPlainErrorString() {
        val message = LocalToolResultClassifier.failureMessage(
            """{"error":"not structured"}"""
        )

        assertNull(message)
    }

    @Test
    fun failureMessage_describesNonZeroExitWithoutStatusText() {
        val message = LocalToolResultClassifier.failureMessage(
            """{"exit_code":2,"stdout":""}"""
        )

        assertEquals("Command completed with non-zero exit code 2.", message)
    }

    @Test
    fun failureMessage_usesMessageForExplicitOkFalse() {
        val message = LocalToolResultClassifier.failureMessage(
            """{"ok":false,"message":"blocked"}"""
        )

        assertEquals("blocked", message)
    }

    @Test
    fun failureMessage_ignoresUnstructuredToolOutput() {
        val message = LocalToolResultClassifier.failureMessage("plain text")

        assertNull(message)
    }
}
