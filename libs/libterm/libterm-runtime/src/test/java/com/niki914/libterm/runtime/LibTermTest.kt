package com.niki914.libterm.runtime

import android.content.Context
import com.niki914.libterm.OutputChunk
import com.niki914.libterm.OutputStream
import com.niki914.libterm.TerminalBytes
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibTermTest {

    @Test
    fun `runtime returns runtime facade`() {
        val runtime: Any = LibTerm.runtime(scope = TestScope())

        assertIs<LibTermRuntime>(runtime)
    }

    @Test
    fun `libterm exposes ssh simple term entry`() {
        val method = LibTerm::class.java.getDeclaredMethod(
            "openSshTerm",
            String::class.java,
            Int::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
        )

        assertEquals(Term::class.java, method.returnType)
    }

    @Test
    fun `runtime config defaults to ansi stripping decoder`() {
        val config = LibTermRuntimeConfig()

        assertIs<AnsiStrippingTerminalOutputDecoder>(config.outputDecoder)
        assertNull(config.outputDecode)
    }

    @Test
    fun `output chunk keeps raw terminal bytes`() {
        val rawBytes = byteArrayOf(0x00, 0xC3.toByte(), 0x0A)
        val chunk = OutputChunk(
            stream = OutputStream.STDOUT,
            bytes = TerminalBytes.of(rawBytes),
            timestampMillis = 100L,
        )

        rawBytes[0] = 0x7F

        assertContentEquals(byteArrayOf(0x00, 0xC3.toByte(), 0x0A), chunk.bytes.toByteArray())
    }

    @Test
    fun `libterm facade still exposes simple term entries`() {
        assertTrue(LibTerm.openUserTerm() is Term)
        assertTrue(LibTerm.openSuTerm() is Term)
        assertTrue(
            LibTerm.openSshTerm(
                host = "127.0.0.1",
                port = 2222,
                username = "bytedance",
                password = "secret",
            ) is Term,
        )
        assertEquals(
            Term::class.java,
            LibTerm::class.java.getDeclaredMethod(
                "openShizukuTerm",
                Context::class.java,
                String::class.java,
            ).returnType,
        )
    }
}
